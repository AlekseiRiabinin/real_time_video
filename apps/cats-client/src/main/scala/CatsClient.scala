import scala.concurrent.duration._
import cats.effect.{IO, IOApp, Resource}
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerConfig, ProducerRecord}
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path, FSDataInputStream}
import java.net.URI
import org.bytedeco.javacv.{FFmpegFrameGrabber, Java2DFrameConverter}
import io.prometheus.client.exporter.HTTPServer
import io.prometheus.client.hotspot.DefaultExports
import io.prometheus.client.{CollectorRegistry, Counter, Gauge, Histogram}
import com.typesafe.config.ConfigFactory
import java.util.Properties


object CatsClient extends IOApp.Simple {

  // Constants for labels
  private val APPLICATION_LABEL = "application"
  private val CATS_CLIENT_LABEL = "cats-client"

  // Configuration case classes
  case class HdfsConfig(uri: String, videoPath: String)
  case class KafkaConfig(bootstrapServers: String, topic: String)
  case class VideoConfig(frameWidth: Int, frameHeight: Int, frameRate: Int)
  case class AppConfig(hdfs: HdfsConfig, kafka: KafkaConfig, video: VideoConfig)

  // Load configuration from application.conf
  val config = ConfigFactory.load()
  val appConfig = AppConfig(
    HdfsConfig(
      uri = config.getString("hdfs.uri"),
      videoPath = config.getString("hdfs.videoPath")
    ),
    KafkaConfig(
      bootstrapServers = config.getString("kafka.bootstrapServers"),
      topic = config.getString("kafka.topic")
    ),
    VideoConfig(
      frameWidth = config.getInt("video.frameWidth"),
      frameHeight = config.getInt("video.frameHeight"),
      frameRate = config.getInt("video.frameRate")
    )
  )

  // Validate configuration
  require(appConfig.hdfs.uri.nonEmpty, "HDFS URI must not be empty")
  require(appConfig.kafka.bootstrapServers.nonEmpty, "Kafka bootstrap servers must not be empty")
  require(appConfig.video.frameWidth > 0, "Frame width must be positive")
  require(appConfig.video.frameHeight > 0, "Frame height must be positive")
  require(appConfig.video.frameRate > 0, "Frame rate must be positive")

  // HDFS configuration
  val conf = new Configuration()
  conf.set("fs.defaultFS", appConfig.hdfs.uri)
  conf.addResource(new Path("/etc/hadoop/core-site.xml"))
  conf.addResource(new Path("/etc/hadoop/hdfs-site.xml"))

  // Initialize HDFS FileSystem
  val fs: FileSystem = try {
    FileSystem.get(new URI(appConfig.hdfs.uri), conf)
  } catch {
    case ex: Exception =>
      println(s"Failed to connect to HDFS: ${ex.getMessage}")
      hdfsReadErrors.labels(CATS_CLIENT_LABEL).inc()
      System.exit(1) // Exit the program if HDFS connection fails
      throw ex // This line is unreachable but required for type safety
  }

  // Kafka Producer Properties
  val kafkaProps: Properties = new Properties()
  kafkaProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, appConfig.kafka.bootstrapServers)
  kafkaProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, classOf[ByteArraySerializer].getName)
  kafkaProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, classOf[ByteArraySerializer].getName)

  // Prometheus metrics with labels
  val framesProduced: Counter = Counter.build()
    .name("frames_produced_total")
    .help("Total number of frames produced")
    .labelNames(APPLICATION_LABEL)
    .register()

  val frameProductionTime: Histogram = Histogram.build()
    .name("frame_production_time_seconds")
    .help("Time taken to produce each frame")
    .labelNames(APPLICATION_LABEL)
    .register()

  val frameProductionErrors: Counter = Counter.build()
    .name("frame_production_errors_total")
    .help("Total number of frame production errors")
    .labelNames(APPLICATION_LABEL)
    .register()

  val frameSize: Gauge = Gauge.build()
    .name("frame_size_bytes")
    .help("Size of each frame in bytes")
    .labelNames(APPLICATION_LABEL)
    .register()

  val kafkaProducerErrors: Counter = Counter.build()
    .name("kafka_producer_errors_total")
    .help("Total number of Kafka producer errors")
    .labelNames(APPLICATION_LABEL)
    .register()

  val hdfsReadErrors: Counter = Counter.build()
    .name("hdfs_read_errors_total")
    .help("Total number of HDFS read errors")
    .labelNames(APPLICATION_LABEL)
    .register()

  // Resource for Kafka Producer
  def kafkaProducerResource: Resource[IO, KafkaProducer[Array[Byte], Array[Byte]]] =
    Resource.make(IO(new KafkaProducer[Array[Byte], Array[Byte]](kafkaProps)))(producer => IO(producer.close()))

  // Resource for HDFS InputStream
  def hdfsInputStreamResource: Resource[IO, FSDataInputStream] =
    Resource.make(IO(fs.open(new Path(appConfig.hdfs.videoPath))))(stream => IO(stream.close()))

  // Function to process video frames and send them to Kafka
  def processVideoFrames(producer: KafkaProducer[Array[Byte], Array[Byte]]): IO[Unit] = {
    hdfsInputStreamResource.use { hdfsInputStream =>
      IO.blocking {
        val grabber = new FFmpegFrameGrabber(hdfsInputStream)
        grabber.setImageWidth(appConfig.video.frameWidth)
        grabber.setImageHeight(appConfig.video.frameHeight)
        grabber.setFrameRate(appConfig.video.frameRate)
        grabber.start()

        val converter = new Java2DFrameConverter()

        var frame = grabber.grab()
        while (frame != null) {
          val startTime = System.nanoTime()
          try {
            val bufferedImage = converter.convert(frame)
            val byteArray = new Array[Byte](bufferedImage.getWidth * bufferedImage.getHeight * 3)
            val raster = bufferedImage.getRaster
            raster.getDataElements(0, 0, bufferedImage.getWidth, bufferedImage.getHeight, byteArray)

            // Update Prometheus metrics with application label
            framesProduced.labels(CATS_CLIENT_LABEL).inc()
            frameSize.labels(CATS_CLIENT_LABEL).set(byteArray.length)
            frameProductionTime.labels(CATS_CLIENT_LABEL).observe((System.nanoTime() - startTime) / 1e9)

            // Send the frame to Kafka
            val record = new ProducerRecord[Array[Byte], Array[Byte]](appConfig.kafka.topic, byteArray)
            producer.send(record).get() // Blocking call, but wrapped in IO.blocking
            println("Frame sent to Kafka")
          } catch {
            case ex: Exception =>
              frameProductionErrors.labels(CATS_CLIENT_LABEL).inc()
              println(s"Error processing frame: ${ex.getMessage}")
          }
          frame = grabber.grab()
        }

        println("End of video file reached")
      }.handleErrorWith { ex =>
        frameProductionErrors.labels(CATS_CLIENT_LABEL).inc()
        IO.println(s"Error processing video: ${ex.getMessage}")
      }
    }
  }

  // Main entry point
  override def run: IO[Unit] = {
    for {
      _ <- IO(DefaultExports.initialize()) // Initialize Prometheus default metrics
      _ <- IO(new HTTPServer(9082)) // Start Prometheus HTTP server
      _ <- IO.println("Prometheus HTTP server started on port 9082")
      _ <- kafkaProducerResource.use { producer =>
        processVideoFrames(producer) // Process the video file once
      }
      _ <- IO.println("Video processing completed. Keeping the application running...")
      _ <- IO.never // Keep the application running indefinitely
    } yield ()
  }
}
