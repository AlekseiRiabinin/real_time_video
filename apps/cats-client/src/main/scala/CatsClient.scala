import cats.effect.{IO, IOApp, Resource}
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerConfig, ProducerRecord}
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import java.net.URI
import org.bytedeco.javacv.{FFmpegFrameGrabber, Java2DFrameConverter}
import io.prometheus.client.exporter.HTTPServer
import io.prometheus.client.hotspot.DefaultExports
import io.prometheus.client.{CollectorRegistry, Counter, Gauge, Histogram}
import com.typesafe.config.ConfigFactory
import scala.concurrent.duration._
import java.util.Properties


object CatsClient extends IOApp.Simple {

  // Configuration case classes
  case class HdfsConfig(uri: String)
  case class KafkaConfig(bootstrapServers: String, topic: String)
  case class VideoConfig(frameWidth: Int, frameHeight: Int, frameRate: Int)
  case class AppConfig(hdfs: HdfsConfig, kafka: KafkaConfig, video: VideoConfig)

  // Load configuration from application.conf
  val config = ConfigFactory.load()
  val appConfig = AppConfig(
    HdfsConfig(config.getString("hdfs.uri")),
    KafkaConfig(config.getString("kafka.bootstrap-servers"), config.getString("kafka.topic")),
    VideoConfig(config.getInt("video.frame-width"), config.getInt("video.frame-height"), config.getInt("video.frame-rate"))
  )

  // HDFS configuration
  val conf = new Configuration()
  conf.set("fs.defaultFS", appConfig.hdfs.uri)
  val fs = FileSystem.get(new URI(appConfig.hdfs.uri), conf)

  // Kafka Producer Properties
  val kafkaProps: Properties = new Properties()
  kafkaProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, appConfig.kafka.bootstrapServers)
  kafkaProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, classOf[ByteArraySerializer].getName)
  kafkaProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, classOf[ByteArraySerializer].getName)

  // Prometheus metrics
  val framesProduced: Counter = Counter.build()
    .name("frames_produced_total")
    .help("Total number of frames produced")
    .register()

  val frameProductionTime: Histogram = Histogram.build()
    .name("frame_production_time_seconds")
    .help("Time taken to produce each frame")
    .register()

  val frameProductionErrors: Counter = Counter.build()
    .name("frame_production_errors_total")
    .help("Total number of frame production errors")
    .register()

  val frameSize: Gauge = Gauge.build()
    .name("frame_size_bytes")
    .help("Size of each frame in bytes")
    .register()

  // Resource for Kafka Producer
  def kafkaProducerResource: Resource[IO, KafkaProducer[Array[Byte], Array[Byte]]] =
    Resource.make(IO(new KafkaProducer[Array[Byte], Array[Byte]](kafkaProps)))(producer => IO(producer.close()))

  // Function to process video frames and send them to Kafka
  def processVideoFrames(producer: KafkaProducer[Array[Byte], Array[Byte]]): IO[Unit] = {
    val localVideoPath = "/tmp/video.mp4"

    // Download the video file from HDFS to a local temporary file
    IO(fs.copyToLocalFile(new Path(appConfig.hdfs.uri + "/videos/video.mp4"), new Path(localVideoPath))) *>
      IO(println(s"Video file downloaded from HDFS to $localVideoPath")) *>
      IO.blocking {
        val grabber = new FFmpegFrameGrabber(localVideoPath)
        grabber.setImageWidth(appConfig.video.frameWidth)
        grabber.setImageHeight(appConfig.video.frameHeight)
        grabber.setFrameRate(appConfig.video.frameRate)
        grabber.start()

        val converter = new Java2DFrameConverter()

        var frame = grabber.grab()
        while (frame != null) {
          val startTime = System.nanoTime()
          val bufferedImage = converter.convert(frame)
          val byteArray = new Array[Byte](bufferedImage.getWidth * bufferedImage.getHeight * 3)
          val raster = bufferedImage.getRaster
          raster.getDataElements(0, 0, bufferedImage.getWidth, bufferedImage.getHeight, byteArray)

          // Update Prometheus metrics
          framesProduced.inc()
          frameSize.set(byteArray.length)
          frameProductionTime.observe((System.nanoTime() - startTime) / 1e9)

          // Send the frame to Kafka
          val record = new ProducerRecord[Array[Byte], Array[Byte]](appConfig.kafka.topic, Array.empty[Byte], byteArray)
          producer.send(record).get() // Blocking call, but wrapped in IO.blocking
          println("Frame sent to Kafka")

          frame = grabber.grab()
        }

        println("End of video file reached")
      }.handleErrorWith { ex =>
        frameProductionErrors.inc()
        IO(println(s"Error processing video: ${ex.getMessage}"))
      }
  }

  // Main entry point
  override def run: IO[Unit] = {
    for {
      _ <- IO(DefaultExports.initialize()) // Initialize Prometheus default metrics
      _ <- IO(new HTTPServer(9091)) // Start Prometheus HTTP server
      _ <- IO(println("Prometheus HTTP server started on port 9091"))
      _ <- kafkaProducerResource.use { producer =>
        processVideoFrames(producer)
      }
    } yield ()
  }
}
