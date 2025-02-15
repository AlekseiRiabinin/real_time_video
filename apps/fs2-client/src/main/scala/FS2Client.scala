import cats.effect.{IO, IOApp, Resource}
import fs2.kafka._
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import java.net.URI
import org.bytedeco.javacv.{FFmpegFrameGrabber, Java2DFrameConverter}
import io.prometheus.client.exporter.HTTPServer
import io.prometheus.client.hotspot.DefaultExports
import io.prometheus.client.{CollectorRegistry, Counter, Gauge, Histogram}
import com.typesafe.config.ConfigFactory
import scala.concurrent.duration._


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

  // HDFS configuration
  val conf = new Configuration()
  conf.set("fs.defaultFS", appConfig.hdfs.uri)
  val fs = FileSystem.get(new URI(appConfig.hdfs.uri), conf)

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

  // Resource for Kafka Producer
  def kafkaProducerResource: Resource[IO, KafkaProducer[IO, Array[Byte], Array[Byte]]] =
    KafkaProducer.resource(producerSettings)

  // Function to process video frames and send them to Kafka
  def processVideoFrames(producer: KafkaProducer[IO, Array[Byte], Array[Byte]]): IO[Unit] = {
    val localVideoPath = "/tmp/video.mp4"

    // Download the video file from HDFS to a local temporary file
    IO(fs.copyToLocalFile(new Path(appConfig.hdfs.videoPath), new Path(localVideoPath))) *>
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
          val record = ProducerRecord(appConfig.kafka.topic, Array.empty[Byte], byteArray)
          producer.produceOne_(record).flatten.flatMap { _ =>
            IO(println("Frame sent to Kafka"))
          }
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
