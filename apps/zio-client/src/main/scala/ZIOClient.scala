import zio._
import zio.kafka.producer._
import zio.kafka.producer.Producer
import zio.kafka.serde._
import zio.config._
import zio.config.magnolia._
import zio.config.typesafe._
import zio.stream.ZStream
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path, FSDataInputStream}
import org.apache.kafka.clients.producer.ProducerRecord
import java.net.URI
import org.bytedeco.javacv.{FFmpegFrameGrabber, Java2DFrameConverter, FFmpegLogCallback}
import io.prometheus.client.exporter.HTTPServer
import io.prometheus.client.hotspot.DefaultExports
import io.prometheus.client.{CollectorRegistry, Counter, Gauge, Histogram}
import com.typesafe.config.ConfigFactory
import org.slf4j.{Logger, LoggerFactory}
import java.util.concurrent.atomic.AtomicInteger


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
  case class VideoConfig(frameWidth: Int, frameHeight: Int, frameRate: Int, format: String)
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

  // Resource for HDFS FileSystem
  def fileSystem(config: AppConfig): ZIO[Scope, Throwable, FileSystem] =
    ZIO.acquireRelease {
      ZIO.attemptBlocking {
        val conf = new Configuration()
        conf.set("fs.defaultFS", config.hdfs.uri)
        conf.addResource(new Path("/etc/hadoop/core-site.xml"))
        conf.addResource(new Path("/etc/hadoop/hdfs-site.xml"))
        FileSystem.get(new URI(config.hdfs.uri), conf)
      }.catchAll { ex =>
        hdfsReadErrors.labels(APPLICATION_VALUE, INSTANCE_VALUE, JOB_VALUE).inc()
        ZIO.fail(ex)
      }
    } { fs =>
      ZIO.attemptBlocking(fs.close()).orDie
    }

  // Resource for HDFS InputStream
  def hdfsInputStream(fs: FileSystem, videoPath: Path): ZIO[Scope, Throwable, FSDataInputStream] =
    ZIO.acquireRelease(
      ZIO.attemptBlocking(fs.open(videoPath))
    )(stream => ZIO.attemptBlocking(stream.close()).orDie)

  // Resource for FFmpeg components
  def ffmpegResources(
    inputStream: FSDataInputStream,
    config: AppConfig
  ): ZIO[Scope, Throwable, (FFmpegFrameGrabber, Java2DFrameConverter)] = {
    val grabberResource = ZIO.acquireRelease {
      ZIO.attemptBlocking {
        val grabber = new FFmpegFrameGrabber(inputStream)
        grabber.setFormat(config.video.format)
        grabber.setImageWidth(config.video.frameWidth)
        grabber.setImageHeight(config.video.frameHeight)
        grabber.setFrameRate(config.video.frameRate)
        grabber.start()
        grabber
      }
    } { grabber =>
      ZIO.attemptBlocking(grabber.stop()).orDie
    }

    val converterResource = ZIO.acquireRelease(
      ZIO.attemptBlocking(new Java2DFrameConverter())
    )(converter => ZIO.attemptBlocking(converter.close()).orDie)

    grabberResource.zip(converterResource)
  }

  // Process a single video file
  def processVideoFile(
    producer: Producer,
    fs: FileSystem,
    videoPath: Path,
    config: AppConfig,
    logger: Logger
  ): ZIO[Scope, Throwable, Unit] = {
    ZIO.scoped {
      hdfsInputStream(fs, videoPath).flatMap { inputStream =>
        ffmpegResources(inputStream, config).flatMap { case (grabber, converter) =>
          val frameCounter = new AtomicInteger(0)

          ZStream.unfoldZIO(()) { _ =>
            ZIO.attemptBlocking {
              val frame = grabber.grab()
              if (frame != null) {
                val startTime = java.lang.System.nanoTime()
                try {
                  val bufferedImage = converter.convert(frame)
                  val byteArray = new Array[Byte](bufferedImage.getWidth * bufferedImage.getHeight * 3)
                  bufferedImage.getRaster.getDataElements(
                    0, 0, bufferedImage.getWidth, bufferedImage.getHeight, byteArray
                  )

                  // Update metrics
                  framesProduced.labels(APPLICATION_VALUE, INSTANCE_VALUE, JOB_VALUE).inc()
                  frameSize.labels(APPLICATION_VALUE, INSTANCE_VALUE, JOB_VALUE).set(byteArray.length)
                  frameProductionTime
                    .labels(APPLICATION_VALUE, INSTANCE_VALUE, JOB_VALUE)
                    .observe((java.lang.System.nanoTime() - startTime) / 1e9)

                  val newCount = frameCounter.incrementAndGet()
                  if (newCount % 100 == 0) {
                    logger.debug(s"Processed $newCount frames from ${videoPath.getName}")
                    java.lang.System.gc()
                  }

                  Some((byteArray, ()))
                } catch {
                  case ex: Exception =>
                    logger.error(s"Error processing frame: ${ex.getMessage}")
                    frameProductionErrors.labels(APPLICATION_VALUE, INSTANCE_VALUE, JOB_VALUE).inc()
                    Some((Array.emptyByteArray, ()))
                } finally {
                  frame.close()
                }
              } else {
                None
              }
            }
          }
          .filter(_.nonEmpty)
          .mapZIO { byteArray =>
            val record = new ProducerRecord[Array[Byte], Array[Byte]](
              config.kafka.topic, 
              Array.emptyByteArray, 
              byteArray
            )
            producer.produce(record, Serde.byteArray, Serde.byteArray)
              .catchAll { ex =>
                kafkaProducerErrors.labels(APPLICATION_VALUE, INSTANCE_VALUE, JOB_VALUE).inc()
                ZIO.attempt(logger.error(s"Error sending frame to Kafka: ${ex.getMessage}"))
              }
          }
          .runDrain
        }
      }.catchAll { ex =>
        ZIO.attempt(logger.error(s"Error processing video ${videoPath.getName}: ${ex.getMessage}")) *>
          ZIO.succeed(hdfsReadErrors.labels(APPLICATION_VALUE, INSTANCE_VALUE, JOB_VALUE).inc())
      }
    }
  }

  // Main processing loop with graceful shutdown
  def processVideoFiles(
    producer: Producer,
    fs: FileSystem,
    config: AppConfig,
    logger: Logger,
    shutdownSignal: Promise[Nothing, Unit]
  ): ZIO[Scope, Throwable, Unit] = {
    def listVideoFiles: ZIO[Any, Throwable, List[Path]] = ZIO.attemptBlocking {
      val videoDir = new Path(config.hdfs.videoPath)
      fs.listStatus(videoDir).toList
        .filter(_.isFile)
        .filter(_.getPath.getName.matches("video_\\d{2}\\.mp4"))
        .sortBy(_.getPath.getName)
        .map(_.getPath)
    }

    def processBatch(files: List[Path]): ZIO[Scope, Throwable, Unit] = {
      ZIO.foreachDiscard(files) { file =>
        processVideoFile(producer, fs, file, config, logger)
          .catchAll(e => ZIO.attempt(logger.error(s"Failed to process ${file.getName}: ${e.getMessage}")))
      }
    }

    for {
      _ <- ZIO.attempt(logger.info("Starting video processing loop"))
      files <- listVideoFiles
      _ <- if (files.isEmpty) {
        ZIO.attempt(logger.warn("No video files found. Waiting...")) *> 
        ZIO.sleep(5.seconds) *>
        ZIO.attempt(java.lang.System.gc())
      } else {
        ZIO.attempt(logger.info(s"Found ${files.size} video files to process")) *> 
        processBatch(files)
      }
      shouldContinue <- shutdownSignal.isDone.map(!_)
      _ <- if (shouldContinue) processVideoFiles(producer, fs, config, logger, shutdownSignal) 
          else ZIO.attempt(logger.info("Shutting down video processing"))
    } yield ()
  }

  // Main entry point with proper resource cleanup
  override def run: ZIO[ZIOAppArgs with Scope, Any, Any] = {
    val logger = LoggerFactory.getLogger(getClass)

    ZIO.scoped {
      (ZIO.attempt(FFmpegLogCallback.set()) *>
        ZIO.attempt(DefaultExports.initialize()) *>
        ZIO.acquireRelease(
          ZIO.attempt(new HTTPServer(9084))
        )(server => ZIO.attempt(server.stop()).orDie)
    )}.flatMap { _ =>
      ZIO.scoped {
        Promise.make[Nothing, Unit].flatMap { shutdownSignal =>
          ZIO.addFinalizer(
            ZIO.succeed(logger.info("Shutdown signal received")) *>
            shutdownSignal.succeed(()).unit
          ) *>
          ZIO.serviceWithZIO[AppConfig] { config =>
            ZIO.scoped {
              for {
                fs <- fileSystem(config)
                producer <- Producer.make(ProducerSettings(List(config.kafka.bootstrapServers)))
                _ <- processVideoFiles(
                  producer = producer,
                  fs = fs,
                  config = config,
                  logger = logger,
                  shutdownSignal = shutdownSignal
                )
              } yield ()
            }.catchAll(e => ZIO.succeed(logger.error(s"Fatal error: ${e.getMessage}")))
          }
        }
      }.provideSome[Scope](configLayer)
    }
  }
}
