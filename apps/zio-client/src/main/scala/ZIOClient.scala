import zio._
import zio.kafka.producer._
import zio.kafka.serde._
import zio.config._
import zio.config.magnolia._
import zio.config.typesafe._
import zio.stream.ZStream
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path, FSDataInputStream}
import java.net.URI
import org.bytedeco.javacv.{FFmpegFrameGrabber, Java2DFrameConverter}
import io.prometheus.client.exporter.HTTPServer
import io.prometheus.client.hotspot.DefaultExports
import io.prometheus.client.{CollectorRegistry, Counter, Gauge, Histogram}


object ZIOClient extends ZIOAppDefault {

  // Constants for labels
  private val APPLICATION_LABEL = "application"
  private val INSTANCE_LABEL = "instance"
  private val JOB_LABEL = "job"

  // Label values
  private val APPLICATION_VALUE = "zio-client"
  private val INSTANCE_VALUE = "zio-client:9084"
  private val JOB_VALUE = "zio-client"

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

  // Prometheus metrics with labels
  val framesProduced: Counter = Counter.build()
    .name("frames_produced_total")
    .help("Total number of frames produced")
    .labelNames(APPLICATION_LABEL, INSTANCE_LABEL, JOB_LABEL)
    .register()

  val frameProductionTime: Histogram = Histogram.build()
    .name("frame_production_time_seconds")
    .help("Time taken to produce each frame")
    .labelNames(APPLICATION_LABEL, INSTANCE_LABEL, JOB_LABEL)
    .register()

  val frameProductionErrors: Counter = Counter.build()
    .name("frame_production_errors_total")
    .help("Total number of frame production errors")
    .labelNames(APPLICATION_LABEL, INSTANCE_LABEL, JOB_LABEL)
    .register()

  val frameSize: Gauge = Gauge.build()
    .name("frame_size_bytes")
    .help("Size of each frame in bytes")
    .labelNames(APPLICATION_LABEL, INSTANCE_LABEL, JOB_LABEL)
    .register()

  val kafkaProducerErrors: Counter = Counter.build()
    .name("kafka_producer_errors_total")
    .help("Total number of Kafka producer errors")
    .labelNames(APPLICATION_LABEL, INSTANCE_LABEL, JOB_LABEL)
    .register()

  val hdfsReadErrors: Counter = Counter.build()
    .name("hdfs_read_errors_total")
    .help("Total number of HDFS read errors")
    .labelNames(APPLICATION_LABEL, INSTANCE_LABEL, JOB_LABEL)
    .register()

  // Function to process video frames and send them to Kafka
  def processVideoFrames(producer: Producer, config: AppConfig): ZIO[Any, Throwable, Unit] = {
    for {
      // Open HDFS input stream
      hdfsInputStream <- ZIO.attemptBlocking {
        val conf = new Configuration()
        conf.set("fs.defaultFS", config.hdfs.uri)
        conf.addResource(new Path("/etc/hadoop/core-site.xml"))
        conf.addResource(new Path("/etc/hadoop/hdfs-site.xml"))
        val fs = FileSystem.get(new URI(config.hdfs.uri), conf)
        fs.open(new Path(config.hdfs.videoPath))
      }.catchAll { ex =>
        hdfsReadErrors.labels(APPLICATION_VALUE, INSTANCE_VALUE, JOB_VALUE).inc()
        ZIO.fail(ex)
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

          // Update Prometheus metrics with application label
          framesProduced.labels(APPLICATION_VALUE, INSTANCE_VALUE, JOB_VALUE).inc()
          frameSize.labels(APPLICATION_VALUE, INSTANCE_VALUE, JOB_VALUE).set(byteArray.length)
          frameProductionTime
            .labels(APPLICATION_VALUE, INSTANCE_VALUE, JOB_VALUE)
            .observe((java.lang.System.nanoTime() - startTime) / 1e9)

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
              kafkaProducerErrors.labels(APPLICATION_VALUE, INSTANCE_VALUE, JOB_VALUE).inc()
              ZIO.attempt(println(s"Error sending frame to Kafka: ${ex.getMessage}"))
            }
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
        new HTTPServer(9084)
        println("Prometheus HTTP server started on port 9084")
      }
      producer <- ZIO.service[Producer]
      _ <- processVideoFrames(producer, config) // Process the video file once
      _ <- ZIO.attempt(println("Video processing completed. Keeping the application running..."))
      _ <- ZIO.never // Keep the application running indefinitely
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
