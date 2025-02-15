import zio._
import zio.kafka.producer._
import zio.kafka.serde._
import zio.config._
import zio.config.magnolia._
import zio.config.typesafe._
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import java.net.URI
import org.bytedeco.javacv.{FFmpegFrameGrabber, Java2DFrameConverter}
import io.prometheus.client.exporter.HTTPServer
import io.prometheus.client.hotspot.DefaultExports
import io.prometheus.client.{CollectorRegistry, Counter, Gauge, Histogram}


object ZIOClient extends ZIOAppDefault {

  // Configuration case classes
  case class HdfsConfig(uri: String)
  case class KafkaConfig(bootstrapServers: String, topic: String)
  case class VideoConfig(frameWidth: Int, frameHeight: Int, frameRate: Int)
  case class AppConfig(hdfs: HdfsConfig, kafka: KafkaConfig, video: VideoConfig)

  // Load configuration from application.conf
  val configLayer: ZLayer[Any, ReadError[String], AppConfig] = 
    TypesafeConfig.fromResourcePath(
      descriptor[AppConfig]
    )

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

  // Function to process video frames and send them to Kafka
  def processVideoFrames(producer: Producer, config: AppConfig): ZIO[Any, Throwable, Unit] = {
    val localVideoPath = "/tmp/video.mp4"

    for {
      _ <- ZIO.attempt {
        val conf = new Configuration()
        conf.set("fs.defaultFS", config.hdfs.uri)
        val fs = FileSystem.get(new URI(config.hdfs.uri), conf)
        fs.copyToLocalFile(new Path(config.hdfs.uri + "/videos/video.mp4"), new Path(localVideoPath))
      }
      _ <- ZIO.attempt(println(s"Video file downloaded from HDFS to $localVideoPath"))
      _ <- ZIO.attemptBlocking {
        val grabber = new FFmpegFrameGrabber(localVideoPath)
        grabber.setImageWidth(config.video.frameWidth)
        grabber.setImageHeight(config.video.frameHeight)
        grabber.setFrameRate(config.video.frameRate)
        grabber.start()

        val converter = new Java2DFrameConverter()

        var frame = grabber.grab()
        while (frame != null) {
          val startTime = java.lang.System.nanoTime()
          val bufferedImage = converter.convert(frame)
          val byteArray = new Array[Byte](bufferedImage.getWidth * bufferedImage.getHeight * 3)
          val raster = bufferedImage.getRaster
          raster.getDataElements(0, 0, bufferedImage.getWidth, bufferedImage.getHeight, byteArray)

          // Update Prometheus metrics
          framesProduced.inc()
          frameSize.set(byteArray.length)
          frameProductionTime.observe((java.lang.System.nanoTime() - startTime) / 1e9)

          // Send the frame to Kafka
          val record = new ProducerRecord[Array[Byte], Array[Byte]](config.kafka.topic, Array.empty[Byte], byteArray)
          producer.produce(record, Serde.byteArray, Serde.byteArray).catchAll { ex =>
            frameProductionErrors.inc()
            ZIO.attempt(println(s"Error sending frame to Kafka: ${ex.getMessage}"))
          }.forkDaemon // Run in the background
          println("Frame sent to Kafka")

          frame = grabber.grab()
        }

        println("End of video file reached")
      }.catchAll { ex =>
        frameProductionErrors.inc()
        ZIO.attempt(println(s"Error processing video: ${ex.getMessage}"))
      }
    } yield ()
  }

  // Main entry point
  override def run: ZIO[Any, Throwable, Unit] = {
    for {
      config <- ZIO.service[AppConfig]
      _ <- ZIO.attempt {
        // Start Prometheus HTTP server
        DefaultExports.initialize()
        new HTTPServer(9091)
        println("Prometheus HTTP server started on port 9091")
      }
      producer <- ZIO.service[Producer]
      _ <- processVideoFrames(producer, config)
    } yield ()
  }.provide(
    configLayer,
    ZLayer.scoped {
      for {
        config <- ZIO.service[AppConfig]
        producer <- Producer.make(ProducerSettings(List(config.kafka.bootstrapServers)))
      } yield producer
    }
  )
}
