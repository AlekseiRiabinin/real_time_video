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
import org.bytedeco.javacv.{FFmpegFrameGrabber, Java2DFrameConverter, FFmpegLogCallback}
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
  case class VideoConfig(frameWidth: Int, frameHeight: Int, frameRate: Int, format: String)
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
      frameRate = config.getInt("video.frameRate"),
      format = config.getString("video.format")
    )
  )

  // Validate configuration
  require(appConfig.hdfs.uri.nonEmpty, "HDFS URI must not be empty")
  require(appConfig.kafka.bootstrapServers.nonEmpty, "Kafka bootstrap servers must not be empty")
  require(appConfig.video.frameWidth > 0, "Frame width must be positive")
  require(appConfig.video.frameHeight > 0, "Frame height must be positive")
  require(appConfig.video.frameRate > 0, "Frame rate must be positive")
  require(appConfig.video.format.nonEmpty, "Video format must not be empty")

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
      System.exit(1)
      throw ex
  }

  // Kafka producer settings
  val producerSettings = ProducerSettings(system, new ByteArraySerializer, new ByteArraySerializer)
    .withBootstrapServers(appConfig.kafka.bootstrapServers)

  // Prometheus metrics with labels
  private val framesProduced: Counter = Counter.build()
    .name("frames_produced_total")
    .help("Total number of frames produced")
    .labelNames(APPLICATION_LABEL, INSTANCE_LABEL, JOB_LABEL)
    .register()

  private val frameProductionTime: Histogram = Histogram.build()
    .name("frame_production_time_seconds")
    .help("Time taken to produce each frame")
    .labelNames(APPLICATION_LABEL, INSTANCE_LABEL, JOB_LABEL)
    .register()

  private val frameProductionErrors: Counter = Counter.build()
    .name("frame_production_errors_total")
    .help("Total number of frame production errors")
    .labelNames(APPLICATION_LABEL, INSTANCE_LABEL, JOB_LABEL)
    .register()

  private val frameSize: Gauge = Gauge.build()
    .name("frame_size_bytes")
    .help("Size of each frame in bytes")
    .labelNames(APPLICATION_LABEL, INSTANCE_LABEL, JOB_LABEL)
    .register()

  private val kafkaProducerErrors: Counter = Counter.build()
    .name("kafka_producer_errors_total")
    .help("Total number of Kafka producer errors")
    .labelNames(APPLICATION_LABEL, INSTANCE_LABEL, JOB_LABEL)
    .register()

  private val hdfsReadErrors: Counter = Counter.build()
    .name("hdfs_read_errors_total")
    .help("Total number of HDFS read errors")
    .labelNames(APPLICATION_LABEL, INSTANCE_LABEL, JOB_LABEL)
    .register()

  // Initialize Prometheus default metrics and start HTTP server
  DefaultExports.initialize()
  val prometheusServer = new HTTPServer(9081)
  logger.info("Prometheus HTTP server started on port 9081")

  // Shutdown flag and coordinator
  @volatile private var isShuttingDown = false
  private val shutdownCoordinator = new Object
  @volatile private var activeStreams = Set.empty[KillSwitch]

  // Enhanced shutdown hook
  sys.addShutdownHook {
    shutdownCoordinator.synchronized {
      logger.info("Shutdown initiated - setting flag and stopping streams")
      isShuttingDown = true
      
      // Stop all active streams
      activeStreams.foreach { killSwitch =>
        try {
          killSwitch.shutdown()
        } catch {
          case ex: Exception =>
            logger.error("Error stopping stream during shutdown", ex)
        }
      }
      
      // Give streams a brief moment to complete
      Thread.sleep(1000)
    }

    // Force cleanup if needed
    try {
      fs.close()
      Await.result(system.terminate(), 10.seconds)
      prometheusServer.stop()
      logger.info("Clean shutdown completed")
    } catch {
      case ex: Exception =>
        logger.error("Forceful shutdown with possible resource leaks", ex)
    }
  }

  // Initialize FFmpeg logging
  FFmpegLogCallback.set()

  // Process video files in a loop
  while (!isShuttingDown) {
    try {
      val videoDir = new Path(appConfig.hdfs.videoPath)
      val videoFiles = fs.listStatus(videoDir).filter { file =>
        file.isFile && file.getPath.getName.matches("video_\\d{2}\\.mp4")
      }
      
      if (videoFiles.isEmpty) {
        logger.warn("No valid video files found in /videos directory. Waiting...")
        Thread.sleep(5000)
      } else {
        videoFiles.foreach { fileStatus =>
          if (!isShuttingDown) {
            val videoPath = fileStatus.getPath
            logger.info(s"Starting processing: ${videoPath.getName}")
            
            processVideoFile(fs, videoPath, producerSettings, appConfig)
              .recover { case ex => 
                logger.error(s"Video processing failed: ${ex.getMessage}")
                hdfsReadErrors.labels(APPLICATION_VALUE, INSTANCE_VALUE, JOB_VALUE).inc()
              }
            
            if (!isShuttingDown) Thread.sleep(1000)
          }
        }
      }
    } catch {
      case ex: Exception if !isShuttingDown =>
        logger.error(s"Processing loop error: ${ex.getMessage}")
        Thread.sleep(5000)
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
    val converter = new Java2DFrameConverter()

    try {
      // Configure FFmpeg with explicit format first
      grabber.setFormat(appConfig.video.format)
      grabber.setImageWidth(appConfig.video.frameWidth)
      grabber.setImageHeight(appConfig.video.frameHeight)
      grabber.setFrameRate(appConfig.video.frameRate)

      // Start grabber with error handling
      try {
        grabber.start()
      } catch {
        case ex: Exception =>
          throw new Exception(s"Failed to start FFmpeg grabber: ${ex.getMessage}")
      }

      var frameCount = 0
      val (killSwitch, streamCompletion) = Source.unfold(()) { _ =>
        if (isShuttingDown) {
          logger.info("Shutdown detected - terminating stream early")
          None
        } else {
          try {
            val frame = grabber.grab()
            if (frame != null) {
              frameCount += 1
              if (frameCount % 100 == 0) {
                logger.debug(s"Processed $frameCount frames")
                System.gc()  // Manual GC to prevent OOM
              }

              val bufferedImage = converter.convert(frame)
              val byteArray = new Array[Byte](bufferedImage.getWidth * bufferedImage.getHeight * 3)
              bufferedImage.getRaster.getDataElements(
                0, 0, bufferedImage.getWidth, bufferedImage.getHeight, byteArray
              )
              
              // Explicitly clean up native resources
              frame.close()
              Some(((), byteArray))
            } else {
              logger.info(s"Completed ${videoPath.getName} ($frameCount frames)")
              None
            }
          } catch {
            case ex: Exception =>
              logger.error(s"Frame processing failed: ${ex.getMessage}")
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
          kafkaProducerErrors.labels(APPLICATION_VALUE, INSTANCE_VALUE, JOB_VALUE).inc()
          logger.error(s"Kafka production error: ${ex.getMessage}")
          null
      }
      .filter(_ != null)
      .toMat(Producer.plainSink(producerSettings))(Keep.both)
      .run()

      // Track active stream
      shutdownCoordinator.synchronized {
        activeStreams += killSwitch
      }

      // Handle stream completion with proper cleanup
      streamCompletion.map { _ =>
        shutdownCoordinator.synchronized {
          activeStreams -= killSwitch
        }
        logger.info(s"Successfully processed: ${videoPath.getName}")
      }.recover { case ex =>
        shutdownCoordinator.synchronized {
          activeStreams -= killSwitch
        }
        logger.error(s"Stream processing failed: ${ex.getMessage}")
      }
    } catch {
      case ex: Exception =>
        logger.error(s"Video processing failed for ${videoPath.getName}: ${ex.getMessage}")
        Future.failed(ex)
    } finally {
      // Guaranteed cleanup
      try {
        if (grabber != null) grabber.stop()
        if (converter != null) converter.close()
        if (hdfsInputStream != null) hdfsInputStream.close()
      } catch {
        case ex: Exception =>
          logger.error(s"Cleanup error for ${videoPath.getName}: ${ex.getMessage}")
      }
    }
  }
}
