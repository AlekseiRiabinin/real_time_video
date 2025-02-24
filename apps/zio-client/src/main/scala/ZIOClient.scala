import zio._
import zio.kafka.producer._
import zio.kafka.serde._
import zio.config._
import zio.config.magnolia._
import zio.config.typesafe._
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path, FSDataInputStream}
import java.net.URI
import org.bytedeco.javacv.{FFmpegFrameGrabber, Java2DFrameConverter}
import io.prometheus.client.exporter.HTTPServer
import io.prometheus.client.hotspot.DefaultExports
import io.prometheus.client.{CollectorRegistry, Counter, Gauge, Histogram}
import zio.stream.ZStream


object ZIOClient extends ZIOAppDefault {

  // Configuration case classes
  case class HdfsConfig(uri: String, videoPath: String)
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
    for {
      // Open HDFS input stream
      hdfsInputStream <- ZIO.attemptBlocking {
        val conf = new Configuration()
        conf.set("fs.defaultFS", config.hdfs.uri)
        val fs = FileSystem.get(new URI(config.hdfs.uri), conf)
        fs.open(new Path(config.hdfs.videoPath))
      }
      _ <- ZIO.attempt(println(s"Video file opened from HDFS: ${config.hdfs.videoPath}"))

      // Initialize FFmpegFrameGrabber with the HDFS input stream
      grabber <- ZIO.attemptBlocking {
        val grabber = new FFmpegFrameGrabber(hdfsInputStream)
        grabber.setImageWidth(config.video.frameWidth)
        grabber.setImageHeight(config.video.frameHeight)
        grabber.setFrameRate(config.video.frameRate)
        grabber.start()
        grabber
      }

      converter = new Java2DFrameConverter()

      // ZStream to process frames and send them to Kafka
      frameStream = ZStream.unfold(()) { _ =>
        val frame = grabber.grab()
        if (frame != null) {
          val startTime = java.lang.System.nanoTime()
          val bufferedImage = converter.convert(frame)
          val byteArray = new Array[Byte](bufferedImage.getWidth * bufferedImage.getHeight * 3)
          val raster = bufferedImage.getRaster
          raster.getDataElements(0, 0, bufferedImage.getWidth, bufferedImage.getHeight, byteArray)

          // Update Prometheus metrics
          framesProduced.inc()
          frameSize.set(byteArray.length)
          frameProductionTime.observe((java.lang.System.nanoTime() - startTime) / 1e9)

          Some((byteArray, ()))
        } else {
          None
        }
      }

      // Process the stream and send frames to Kafka
      _ <- frameStream
        .mapZIO { byteArray =>
          val record = new ProducerRecord[Array[Byte], Array[Byte]](config.kafka.topic, byteArray)
          producer.produce(record, Serde.byteArray, Serde.byteArray)
            .catchAll { ex =>
              frameProductionErrors.inc()
              ZIO.attempt(println(s"Error sending frame to Kafka: ${ex.getMessage}"))
            }
            .forkDaemon // Run in the background
        }
        .runDrain
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
