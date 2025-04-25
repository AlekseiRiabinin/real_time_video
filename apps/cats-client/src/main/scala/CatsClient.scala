import scala.concurrent.duration._
import cats.effect.{IO, IOApp, Resource, Ref, Deferred}
import cats.effect.unsafe.implicits.global
import cats.implicits._
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerConfig, ProducerRecord}
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path, FSDataInputStream}
import java.net.URI
import org.bytedeco.javacv.{FFmpegFrameGrabber, Java2DFrameConverter, FFmpegLogCallback}
import io.prometheus.client.exporter.HTTPServer
import io.prometheus.client.hotspot.DefaultExports
import io.prometheus.client.{CollectorRegistry, Counter, Gauge, Histogram}
import com.typesafe.config.ConfigFactory
import java.util.Properties
import org.slf4j.LoggerFactory


object CatsClient extends IOApp.Simple {
  // Constants for labels
  private val APPLICATION_LABEL = "application"
  private val INSTANCE_LABEL = "instance"
  private val JOB_LABEL = "job"

  // Label values
  private val APPLICATION_VALUE = "cats-client"
  private val INSTANCE_VALUE = "cats-client:9082"
  private val JOB_VALUE = "cats-client"

  private val logger = LoggerFactory.getLogger(getClass)

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

  // Kafka Producer Properties
  val kafkaProps: Properties = new Properties()
  kafkaProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, appConfig.kafka.bootstrapServers)
  kafkaProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, classOf[ByteArraySerializer].getName)
  kafkaProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, classOf[ByteArraySerializer].getName)
  kafkaProps.put(ProducerConfig.LINGER_MS_CONFIG, "100") // Batch small sends
  kafkaProps.put(ProducerConfig.BATCH_SIZE_CONFIG, "16384") // 16KB batch size

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

  val avgFrameProductionTimeSeconds = Gauge.build()
    .name("avg_frame_production_time_seconds")
    .help("Latest frame production time in seconds (simplified for Grafana)")
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

  // Resource for Kafka Producer
  def kafkaProducerResource: Resource[IO, KafkaProducer[Array[Byte], Array[Byte]]] =
    Resource.make(IO(new KafkaProducer[Array[Byte], Array[Byte]](kafkaProps))) { producer =>
      IO(producer.close()).handleErrorWith(e => 
        IO(logger.error(s"Error closing Kafka producer: ${e.getMessage}"))
    )}

  // Resource for HDFS InputStream
  def hdfsInputStreamResource(videoPath: Path): Resource[IO, FSDataInputStream] =
    Resource.make(IO(fs.open(videoPath))) { stream =>
      IO(stream.close()).handleErrorWith(e => 
        IO(logger.error(s"Error closing HDFS stream: ${e.getMessage}"))
    )}

  // Resource for FFmpeg components
  def ffmpegResources(
    inputStream: FSDataInputStream
  ): Resource[IO, (FFmpegFrameGrabber, Java2DFrameConverter)] = {
    val grabberResource = Resource.make {
      IO {
        val grabber = new FFmpegFrameGrabber(inputStream)
        grabber.setFormat(appConfig.video.format)
        grabber.setImageWidth(appConfig.video.frameWidth)
        grabber.setImageHeight(appConfig.video.frameHeight)
        grabber.setFrameRate(appConfig.video.frameRate)
        grabber.start()
        grabber
      }
    } { grabber =>
      IO(grabber.stop()).handleErrorWith(e => 
        IO(logger.error(s"Error stopping FFmpeg grabber: ${e.getMessage}")))
    }

    val converterResource = Resource.make(IO(new Java2DFrameConverter())) { converter =>
      IO(converter.close()).handleErrorWith(e => 
        IO(logger.error(s"Error closing frame converter: ${e.getMessage}")))
    }

    for {
      grabber <- grabberResource
      converter <- converterResource
    } yield (grabber, converter)
  }

  // Process a single video file
  def processVideoFile(
    producer: KafkaProducer[Array[Byte], Array[Byte]],
    videoPath: Path
  ): IO[Unit] = {
    hdfsInputStreamResource(videoPath).use { inputStream =>
      ffmpegResources(inputStream).use { case (grabber, converter) =>
        Ref[IO].of(0).flatMap { frameCountRef =>
          IO.blocking {
            var frame = grabber.grab()
            while (frame != null) {
              val startTime = System.nanoTime()
              try {
                val bufferedImage = converter.convert(frame)
                val byteArray = new Array[Byte](bufferedImage.getWidth * bufferedImage.getHeight * 3)
                bufferedImage.getRaster.getDataElements(
                  0, 0, bufferedImage.getWidth, bufferedImage.getHeight, byteArray
                )

                val elapsedSeconds = (System.nanoTime() - startTime) / 1e9

                // Update metrics
                framesProduced.labels(APPLICATION_VALUE, INSTANCE_VALUE, JOB_VALUE).inc()
                frameSize.labels(APPLICATION_VALUE, INSTANCE_VALUE, JOB_VALUE).set(byteArray.length)
                frameProductionTime
                  .labels(APPLICATION_VALUE, INSTANCE_VALUE, JOB_VALUE)
                  .observe((System.nanoTime() - startTime) / 1e9)

                // Set the average time gauge
                avgFrameProductionTimeSeconds
                  .labels(APPLICATION_VALUE, INSTANCE_VALUE, JOB_VALUE)
                  .set(elapsedSeconds)

                // Send to Kafka
                val record = new ProducerRecord[Array[Byte], Array[Byte]](appConfig.kafka.topic, byteArray)
                producer.send(record).get()

                // Update frame count and log periodically
                val newCount = frameCountRef.modify(count => (count + 1, count + 1)).unsafeRunSync()
                if (newCount % 100 == 0) {
                  logger.debug(s"Processed $newCount frames from ${videoPath.getName}")
                  System.gc() // Manual GC to prevent OOM
                }

                // Clean up native resources
                frame.close()
              } catch {
                case ex: Exception =>
                  logger.error(s"Error processing frame: ${ex.getMessage}")
                  frameProductionErrors.labels(APPLICATION_VALUE, INSTANCE_VALUE, JOB_VALUE).inc()
              }
              frame = grabber.grab()
            }
            logger.info(s"Finished processing ${videoPath.getName}")
          }
        }
      }.handleErrorWith { ex =>
        IO(logger.error(s"Error processing video ${videoPath.getName}: ${ex.getMessage}")) >>
          IO(hdfsReadErrors.labels(APPLICATION_VALUE, INSTANCE_VALUE, JOB_VALUE).inc())
      }
    }
  }

  // Main processing loop with graceful shutdown
  def processVideoFiles(
    producer: KafkaProducer[Array[Byte], Array[Byte]],
    shutdownSignal: Deferred[IO, Unit]
  ): IO[Unit] = {
    def listVideoFiles: IO[List[Path]] = IO {
      val videoDir = new Path(appConfig.hdfs.videoPath)
      fs.listStatus(videoDir).toList
        .filter(_.isFile)
        .filter(_.getPath.getName.matches("video_\\d{2}\\.mp4"))
        .sortBy(_.getPath.getName)
        .map(_.getPath)
    }

    def processBatch(files: List[Path]): IO[Unit] = {
      files.parTraverse_(file => 
        processVideoFile(producer, file)
          .handleErrorWith(e => 
            IO(logger.error(s"Failed to process ${file.getName}: ${e.getMessage}")))
      )
    }

    for {
      _ <- IO(logger.info("Starting video processing loop"))
      files <- listVideoFiles
      _ <- if (files.isEmpty) {
        IO(logger.warn("No video files found. Waiting...")) >> IO.sleep(5.seconds)
      } else {
        IO(logger.info(s"Found ${files.size} video files to process")) >> 
          processBatch(files)
      }
      // Check for shutdown signal before continuing
      shouldContinue <- shutdownSignal.tryGet.map(_.isEmpty)
      _ <- if (shouldContinue) processVideoFiles(producer, shutdownSignal) else IO.unit
    } yield ()
  }

  // Main entry point with proper resource cleanup
  override def run: IO[Unit] = {
    // Initialize FFmpeg logging
    IO(FFmpegLogCallback.set()) >>
    // Initialize Prometheus
    IO(DefaultExports.initialize()) >>
    Resource.make(IO(new HTTPServer(9082)))(server => IO(server.stop())).use { _ =>
      IO(logger.info("Prometheus HTTP server started on port 9082")) >>
      // Setup shutdown handling
      Deferred[IO, Unit].flatMap { shutdownSignal =>
        // Register shutdown hook
        IO(sys.addShutdownHook {
          logger.info("Shutdown signal received")
          shutdownSignal.complete(()).unsafeRunSync()
        }) >>
        // Main processing with Kafka producer
        kafkaProducerResource.use { producer =>
          processVideoFiles(producer, shutdownSignal)
            .handleErrorWith(e => 
              IO(logger.error(s"Fatal error in processing loop: ${e.getMessage}")))
        }
      }
    }
  }
}
