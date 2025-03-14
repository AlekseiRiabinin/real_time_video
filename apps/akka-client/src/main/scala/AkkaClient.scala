import scala.concurrent.duration._
import scala.concurrent.{Await, Future, ExecutionContext}
import akka.actor.ActorSystem
import akka.kafka.ProducerSettings
import akka.kafka.scaladsl.Producer
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{ActorMaterializer, KillSwitch, KillSwitches, Materializer}
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
  implicit val ec: ExecutionContext = system.dispatcher

  // Constants for labels
  private val APPLICATION_LABEL = "application"
  private val INSTANCE_LABEL = "instance"
  private val JOB_LABEL = "job"

  // Label values
  private val APPLICATION_VALUE = "akka-client"
  private val INSTANCE_VALUE = "akka-client:9081"
  private val JOB_VALUE = "akka-client"

  val logger = LoggerFactory.getLogger(getClass)

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
      bootstrapServers = config.getString("kafka.bootstrapservers"),
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
      logger.error(s"Failed to connect to HDFS: ${ex.getMessage}")
      hdfsReadErrors.labels(APPLICATION_VALUE, INSTANCE_VALUE, JOB_VALUE).inc()
      System.exit(1) // Exit the program if HDFS connection fails
      throw ex // This line is unreachable but required for type safety
  }

  // Kafka producer settings
  val producerSettings = ProducerSettings(system, new ByteArraySerializer, new ByteArraySerializer)
    .withBootstrapServers(appConfig.kafka.bootstrapServers)

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

  // Initialize Prometheus default metrics and start HTTP server
  DefaultExports.initialize()
  val prometheusServer = new HTTPServer(9081)
  logger.info("Prometheus HTTP server started on port 9081")

  // Shutdown flag
  @volatile private var isShuttingDown = false

  // Shutdown hook
  sys.addShutdownHook {
    logger.info("Shutdown hook triggered. Setting shutdown flag.")
    isShuttingDown = true
    fs.close()
    system.terminate()
    prometheusServer.stop()
    logger.info("AkkaClient stopped")
  }

  // Process video files in a loop
  while (!isShuttingDown) {
    val videoDir = new Path(appConfig.hdfs.videoPath)
    val videoFiles = fs.listStatus(videoDir).filter(_.isFile)
    if (videoFiles.isEmpty) {
      logger.warn("No video files found in directory. Waiting for files...")
      Thread.sleep(5000) // Wait for 5 seconds before checking again
    } else {
      videoFiles.sortBy(_.getPath.getName).foreach { fileStatus =>
        val videoPath = fileStatus.getPath
        if (videoPath.getName.matches("video_\\d{2}\\.mp4")) {
          val processingFuture = processVideoFile(fs, videoPath, producerSettings, appConfig)
          Await.result(processingFuture, Duration.Inf)
        }
      }
    }
  }

  /** Processes a single video file and sends its frames to Kafka. */
  private def processVideoFile(
    fs: FileSystem,
    videoPath: Path,
    producerSettings: ProducerSettings[Array[Byte], Array[Byte]],
    appConfig: AppConfig
  ): Future[Unit] = {
    logger.info(s"Processing video file: ${videoPath.getName}")
    val hdfsInputStream = fs.open(videoPath)
    val grabber = new FFmpegFrameGrabber(hdfsInputStream)
    try {
      grabber.setImageWidth(appConfig.video.frameWidth)
      grabber.setImageHeight(appConfig.video.frameHeight)
      grabber.setFrameRate(appConfig.video.frameRate)
      grabber.start()

      val converter = new Java2DFrameConverter()

      val (killSwitch, streamCompletion) = Source.unfold(()) { _ =>
        if (isShuttingDown) {
          logger.info("Shutdown flag is set, stopping frame grabber.")
          None
        } else {
          try {
            val frame = grabber.grab()
            if (frame != null) {
              logger.debug(s"Grabbed frame: ${frame.timestamp}")
              val bufferedImage = converter.convert(frame)
              val byteArray = new Array[Byte](bufferedImage.getWidth * bufferedImage.getHeight * 3)
              val raster = bufferedImage.getRaster
              raster.getDataElements(0, 0, bufferedImage.getWidth, bufferedImage.getHeight, byteArray)
              Some(((), byteArray))
            } else {
              logger.info("Reached end of video file.")
              None
            }
          } catch {
            case ex: Exception =>
              logger.error(s"Error grabbing frame: ${ex.getMessage}")
              frameProductionErrors.labels(APPLICATION_VALUE, INSTANCE_VALUE, JOB_VALUE).inc()
              None
          }
        }
      }
      .viaMat(KillSwitches.single)(Keep.right)
      .map { byteArray =>
        val startTime = System.nanoTime()
        framesProduced.labels(APPLICATION_VALUE, INSTANCE_VALUE, JOB_VALUE).inc()
        frameSize.labels(APPLICATION_VALUE, INSTANCE_VALUE, JOB_VALUE).set(byteArray.length)

        val record = new ProducerRecord[Array[Byte], Array[Byte]](appConfig.kafka.topic, byteArray)

        val endTime = System.nanoTime()
        frameProductionTime
          .labels(APPLICATION_VALUE, INSTANCE_VALUE, JOB_VALUE)
          .observe((endTime - startTime) / 1e9)

        record
      }
      .recover {
        case ex: Exception =>
          frameProductionErrors.labels(APPLICATION_VALUE, INSTANCE_VALUE, JOB_VALUE).inc()
          logger.error("Error producing frame", ex)
          null
      }
      .filter(_ != null)
      .toMat(Producer.plainSink(producerSettings))(Keep.both)
      .run()

      // Ensure the program exits after processing the video file
      streamCompletion.map { _ =>
        logger.info(s"Finished processing video file: ${videoPath.getName}")
      }.recover { case ex =>
        logger.error(s"Stream failed: ${ex.getMessage}")
      }
    } finally {
      grabber.stop()
      hdfsInputStream.close()
    }
  }
}
