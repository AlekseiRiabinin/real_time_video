import scala.concurrent.duration._
import cats.effect.{IO, IOApp, Resource}
import fs2.kafka._
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path, FSDataInputStream}
import java.net.URI
import org.bytedeco.javacv.{FFmpegFrameGrabber, Java2DFrameConverter}
import io.prometheus.client.exporter.HTTPServer
import io.prometheus.client.hotspot.DefaultExports
import io.prometheus.client.{CollectorRegistry, Counter, Gauge, Histogram}
import com.typesafe.config.ConfigFactory
import fs2.Stream


object FS2Client extends IOApp.Simple {

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
      videoPath = config.getString("hdfs.video-path")
    ),
    KafkaConfig(
      bootstrapServers = config.getString("kafka.bootstrap-servers"),
      topic = config.getString("kafka.topic")
    ),
    VideoConfig(
      frameWidth = config.getInt("video.frame-width"),
      frameHeight = config.getInt("video.frame-height"),
      frameRate = config.getInt("video.frame-rate")
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
      hdfsReadErrors.inc()
      System.exit(1) // Exit the program if HDFS connection fails
      throw ex // This line is unreachable but required for type safety
  }

  // Kafka Producer Settings
  val producerSettings: ProducerSettings[IO, Array[Byte], Array[Byte]] =
    ProducerSettings[IO, Array[Byte], Array[Byte]]
      .withBootstrapServers(appConfig.kafka.bootstrapServers)

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

  val kafkaProducerErrors: Counter = Counter.build()
    .name("kafka_producer_errors_total")
    .help("Total number of Kafka producer errors")
    .register()

  val hdfsReadErrors: Counter = Counter.build()
    .name("hdfs_read_errors_total")
    .help("Total number of HDFS read errors")
    .register()

  // Resource for Kafka Producer
  def kafkaProducerResource: Resource[IO, KafkaProducer[IO, Array[Byte], Array[Byte]]] =
    KafkaProducer.resource(producerSettings)

  // Resource for HDFS InputStream
  def hdfsInputStreamResource: Resource[IO, FSDataInputStream] =
    Resource.make(IO(fs.open(new Path(appConfig.hdfs.videoPath))))(stream => IO(stream.close()))

  // Function to process video frames and send them to Kafka
  def processVideoFrames(producer: KafkaProducer[IO, Array[Byte], Array[Byte]]): IO[Unit] = {
    hdfsInputStreamResource.use { hdfsInputStream =>
      IO.blocking {
        val grabber = new FFmpegFrameGrabber(hdfsInputStream)
        grabber.setImageWidth(appConfig.video.frameWidth)
        grabber.setImageHeight(appConfig.video.frameHeight)
        grabber.setFrameRate(appConfig.video.frameRate)
        grabber.start()

        val converter = new Java2DFrameConverter()

        // FS2 Stream to process frames and send them to Kafka
        val frameStream = Stream.unfold(()) { _ =>
          val frame = grabber.grab()
          if (frame != null) {
            val startTime = System.nanoTime()
            val bufferedImage = converter.convert(frame)
            val byteArray = new Array[Byte](bufferedImage.getWidth * bufferedImage.getHeight * 3)
            val raster = bufferedImage.getRaster
            raster.getDataElements(0, 0, bufferedImage.getWidth, bufferedImage.getHeight, byteArray)

            // Update Prometheus metrics
            framesProduced.inc()
            frameSize.set(byteArray.length)
            frameProductionTime.observe((System.nanoTime() - startTime) / 1e9)

            Some((byteArray, ()))
          } else {
            None
          }
        }

        frameStream
          .evalMap { byteArray =>
            val record = ProducerRecord(appConfig.kafka.topic, Array.empty[Byte], byteArray)
            producer.produceOne(record).flatten
              .flatTap(_ => IO(println("Frame sent to Kafka")))
              .handleErrorWith { ex =>
                kafkaProducerErrors.inc()
                IO(println(s"Error sending frame to Kafka: ${ex.getMessage}"))
              }
          }
          .compile
          .drain
      }.flatten
    }.handleErrorWith { ex =>
      frameProductionErrors.inc()
      IO(println(s"Error processing video: ${ex.getMessage}"))
    }
  }

  // Main entry point with continuous loop
  override def run: IO[Unit] = {
    for {
      _ <- IO(DefaultExports.initialize()) // Initialize Prometheus default metrics
      _ <- IO(new HTTPServer(9083)) // Start Prometheus HTTP server
      _ <- IO.println("Prometheus HTTP server started on port 9083")
      _ <- kafkaProducerResource.use { producer =>
        def loop: IO[Unit] = IO.defer {
          processVideoFrames(producer).attempt.flatMap {
            case Right(_) =>
              IO.println("Finished processing video file. Restarting...") >>
              IO.sleep(5.seconds) >> loop
            case Left(ex) =>
              IO.println(s"Error processing video file: ${ex.getMessage}") >>
              IO.sleep(5.seconds) >> loop
          }
        }
        loop
      }
    } yield ()
  }
}
