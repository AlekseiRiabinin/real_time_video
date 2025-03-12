import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import java.net.URI
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord, Callback, RecordMetadata}
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.bytedeco.javacv.{FFmpegFrameGrabber, Java2DFrameConverter}
import io.prometheus.client.exporter.HTTPServer
import io.prometheus.client.hotspot.DefaultExports
import io.prometheus.client.{CollectorRegistry, Counter, Gauge, Histogram}
import com.typesafe.config.ConfigFactory
import java.util.Properties
import org.slf4j.LoggerFactory


object KafkaClient {

  // Constants for labels
  private val APPLICATION_LABEL = "application"
  private val INSTANCE_LABEL = "instance"
  private val JOB_LABEL = "job"

  // Label values
  private val APPLICATION_VALUE = "kafka-client"
  private val INSTANCE_VALUE = "kafka-client:9080"
  private val JOB_VALUE = "kafka-client"

  // Logger
  private val logger = LoggerFactory.getLogger(getClass)

  // Configuration case classes
  case class HdfsConfig(uri: String, videoPath: String)
  case class KafkaConfig(bootstrapServers: String, topic: String)
  case class VideoConfig(frameWidth: Int, frameHeight: Int, frameRate: Int)
  case class AppConfig(hdfs: HdfsConfig, kafka: KafkaConfig, video: VideoConfig)

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

  def main(args: Array[String]): Unit = {
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
        logger.error(s"Failed to connect to HDFS: ${ex.getMessage}")
        hdfsReadErrors.labels(APPLICATION_VALUE, INSTANCE_VALUE, JOB_VALUE).inc()
        System.exit(1) // Exit the program if HDFS connection fails
        throw ex // This line is unreachable but required for type safety
    }

    // Kafka configuration
    val kafkaProps = new Properties()
    kafkaProps.put("bootstrap.servers", appConfig.kafka.bootstrapServers)
    kafkaProps.put("key.serializer", classOf[ByteArraySerializer].getName)
    kafkaProps.put("value.serializer", classOf[ByteArraySerializer].getName)
    val kafkaProducer = new KafkaProducer[Array[Byte], Array[Byte]](kafkaProps)

    // Initialize Prometheus default metrics and start HTTP server
    DefaultExports.initialize()
    val prometheusServer = new HTTPServer(9080)
    logger.info("Prometheus HTTP server started on port 9080")

    // Add shutdown hook to ensure resources are closed properly
    sys.addShutdownHook {
      logger.info("Shutdown hook triggered. Closing resources...")
      kafkaProducer.close()
      fs.close()
      prometheusServer.stop()
      logger.info("KafkaClient stopped.")
    }

    // Process all video files in a loop
    try {
      while (true) { // Loop indefinitely
        val videoDir = new Path("/videos")
        val videoFiles = fs.listStatus(videoDir).filter(_.isFile)
        if (videoFiles.isEmpty) {
          logger.warn("No video files found in /videos directory. Waiting for files...")
          Thread.sleep(5000) // Wait for 5 seconds before checking again
        } else {
          videoFiles.sortBy(_.getPath.getName).foreach { fileStatus =>
            val videoPath = fileStatus.getPath
            // Match files like video_01.mp4, video_02.mp4, etc.
            if (videoPath.getName.matches("video_\\d{2}\\.mp4")) {
              processVideoFile(fs, videoPath, kafkaProducer, appConfig)
            }
          }
        }
      }
    } catch {
      case ex: Exception =>
        logger.error(s"Error processing video files: ${ex.getMessage}")
    }

    // Keep the application running to expose metrics
    logger.info("Application is running and waiting for termination...")
    while (true) {
      Thread.sleep(1000) // Sleep to avoid busy-waiting
    }
  }

  /** Processes a single video file and sends its frames to Kafka. */
  private def processVideoFile(
    fs: FileSystem,
    videoPath: Path,
    kafkaProducer: KafkaProducer[Array[Byte], Array[Byte]],
    appConfig: AppConfig
  ): Unit = {
    logger.info(s"Processing video file: ${videoPath.getName}")
    val hdfsInputStream = fs.open(videoPath)
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
        if (bufferedImage == null) {
          throw new Exception("Failed to convert frame to BufferedImage")
        }
        val byteArray = new Array[Byte](bufferedImage.getWidth * bufferedImage.getHeight * 3)
        val raster = bufferedImage.getRaster
        raster.getDataElements(0, 0, bufferedImage.getWidth, bufferedImage.getHeight, byteArray)

        // Update Prometheus metrics with application label
        framesProduced.labels(APPLICATION_VALUE, INSTANCE_VALUE, JOB_VALUE).inc()
        frameSize.labels(APPLICATION_VALUE, INSTANCE_VALUE, JOB_VALUE).set(byteArray.length)
        frameProductionTime
          .labels(APPLICATION_VALUE, INSTANCE_VALUE, JOB_VALUE)
          .observe((System.nanoTime() - startTime) / 1e9)

        // Send the frame to Kafka
        val record = new ProducerRecord[Array[Byte], Array[Byte]](appConfig.kafka.topic, byteArray)
        kafkaProducer.send(record, new Callback {
          override def onCompletion(metadata: RecordMetadata, exception: Exception): Unit = {
            if (exception != null) {
              kafkaProducerErrors.labels(APPLICATION_VALUE, INSTANCE_VALUE, JOB_VALUE).inc()
              logger.error(s"Failed to send frame to Kafka: ${exception.getMessage}")
            } else {
              logger.debug("Frame sent to Kafka")
            }
          }
        })
      } catch {
        case ex: Exception =>
          frameProductionErrors.labels(APPLICATION_VALUE, INSTANCE_VALUE, JOB_VALUE).inc()
          logger.error(s"Error processing frame: ${ex.getMessage}")
      }
      frame = grabber.grab()
    }
    grabber.stop()
    hdfsInputStream.close()
    logger.info(s"Finished processing video file: ${videoPath.getName}")
  }
}
