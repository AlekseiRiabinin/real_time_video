import akka.actor.ActorSystem
import akka.kafka.ProducerSettings
import akka.kafka.scaladsl.Producer
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{ActorMaterializer, Materializer}
import com.typesafe.config.ConfigFactory
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.bytedeco.javacv.{FFmpegFrameGrabber, Java2DFrameConverter}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.slf4j.LoggerFactory
import java.net.URI
import io.prometheus.client.exporter.HTTPServer
import io.prometheus.client.hotspot.DefaultExports
import io.prometheus.client.{CollectorRegistry, Counter, Gauge, Histogram}


object AkkaClient extends App {
  implicit val system: ActorSystem = ActorSystem("AkkaClientSystem")
  implicit val materializer: Materializer = Materializer(system)

  val log = LoggerFactory.getLogger(getClass)

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

  // Kafka producer settings
  val producerSettings = ProducerSettings(system, new ByteArraySerializer, new ByteArraySerializer)
    .withBootstrapServers(appConfig.kafka.bootstrapServers)
    .withProperty("acks", "all")
    .withProperty("retries", "3")
    .withProperty("linger.ms", "5")
    .withProperty("compression.type", "snappy")

  // Prometheus metrics for producer
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

  // Start Prometheus HTTP server
  DefaultExports.initialize()
  val server = new HTTPServer(9091)
  log.info("Prometheus HTTP server started on port 9091")

  // Shutdown hook
  sys.addShutdownHook {
    log.info("Shutting down AkkaClient...")
    fs.close()
    system.terminate()
    server.stop()
    log.info("AkkaClient stopped")
  }

  try {
    // Open an InputStream to read the video file directly from HDFS
    val hdfsInputStream = fs.open(new Path(appConfig.hdfs.videoPath))
    log.info(s"Video file opened from HDFS: ${appConfig.hdfs.videoPath}")

    // Initialize frame grabber with the HDFS InputStream
    val grabber = new FFmpegFrameGrabber(hdfsInputStream)
    grabber.setImageWidth(appConfig.video.frameWidth)
    grabber.setImageHeight(appConfig.video.frameHeight)
    grabber.setFrameRate(appConfig.video.frameRate)
    grabber.start()

    val converter = new Java2DFrameConverter()

    // Akka Stream to process frames and send them to Kafka
    val frameSource = Source.unfold(()) { _ =>
      val frame = grabber.grab()
      if (frame != null) {
        val bufferedImage = converter.convert(frame)
        val byteArray = new Array[Byte](bufferedImage.getWidth * bufferedImage.getHeight * 3)
        val raster = bufferedImage.getRaster
        raster.getDataElements(0, 0, bufferedImage.getWidth, bufferedImage.getHeight, byteArray)
        Some(((), byteArray))
      } else {
        None
      }
    }

    val kafkaSink = Producer.plainSink(producerSettings)

    // Run the stream and update metrics during frame processing
    frameSource
      .map { byteArray =>
        val startTime = System.nanoTime()
        framesProduced.inc() // Increment frames produced counter
        frameSize.set(byteArray.length) // Update frame size gauge

        val record = new ProducerRecord[Array[Byte], Array[Byte]](appConfig.kafka.topic, byteArray)

        val endTime = System.nanoTime()
        frameProductionTime.observe((endTime - startTime) / 1e9) // Update production time histogram

        record
      }
      .recover {
        case ex: Exception =>
          frameProductionErrors.inc() // Increment error counter
          log.error("Error producing frame", ex)
          null
      }
      .filter(_ != null)
      .runWith(kafkaSink)

    log.info("Stream started. Sending frames to Kafka...")
  } catch {
    case ex: Exception =>
      log.error(s"Error processing video: ${ex.getMessage}")
  }
}
