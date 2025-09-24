import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import java.net.URI
import org.bytedeco.javacv.{FFmpegFrameGrabber, Java2DFrameConverter}
import akka.actor.ActorSystem
import akka.kafka.ProducerSettings
import akka.kafka.scaladsl.Producer
import akka.stream.scaladsl.Source
import akka.NotUsed
import akka.stream.Graph
import akka.stream.scaladsl.Sink
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.ByteArraySerializer
import com.typesafe.config.ConfigFactory
import io.prometheus.client.exporter.HTTPServer
import io.prometheus.client.hotspot.DefaultExports
import io.prometheus.client.{Counter, Gauge, Histogram}
import scala.util.{Try, Success, Failure}
import scala.concurrent.duration._
import org.slf4j.LoggerFactory
import scala.concurrent.ExecutionContext.Implicits.global


object AkkaClient {
  
  // Constants for labels
  private val APPLICATION_LABEL = "application"
  private val INSTANCE_LABEL = "instance"
  private val JOB_LABEL = "job"

  // Label values
  private val APPLICATION_VALUE = "kafka-client"
  private val INSTANCE_VALUE = "kafka-client:9081"
  private val JOB_VALUE = "kafka-client"

  // Logger
  private val logger = LoggerFactory.getLogger(getClass)

  // Configuration case classes
  case class HdfsConfig(uri: String, videoPath: String)
  case class KafkaConfig(bootstrapServers: String, topic: String)
  case class VideoConfig(frameWidth: Int, frameHeight: Int, frameRate: Int, format: String)
  case class AppConfig(hdfs: HdfsConfig, kafka: KafkaConfig, video: VideoConfig)

  // Prometheus metrics with labels
  private val framesProduced: Counter = Counter.build()
    .name("frames_produced_total")
    .help("Total number of frames produced")
    .labelNames(APPLICATION_LABEL, INSTANCE_LABEL, JOB_LABEL)
    .register()

  private val frameProductionTime: Histogram = Histogram.build()
    .name("frame_production_time_seconds")
    .buckets(0.01, 0.05, 0.1, 0.5, 1.0)
    .help("Time taken to produce each frame (histogram)")
    .labelNames(APPLICATION_LABEL, INSTANCE_LABEL, JOB_LABEL)
    .register()

  private val avgFrameProductionTimeSeconds = Gauge.build()
    .name("avg_frame_production_time_seconds")
    .help("Latest frame production time in seconds (simplified for Grafana)")
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

    // Initialize systems (now using nested config)
    implicit val system = ActorSystem("VideoProcessor")

    // HDFS configuration
    val conf = new Configuration()
    conf.set("fs.defaultFS", appConfig.hdfs.uri)
    conf.addResource(new Path("/etc/hadoop/core-site.xml"))
    conf.addResource(new Path("/etc/hadoop/hdfs-site.xml"))    

    val fs = FileSystem.get(new URI(appConfig.hdfs.uri), conf)

    val producerSettings = ProducerSettings(
      system,
      new ByteArraySerializer,
      new ByteArraySerializer
    ).withBootstrapServers(appConfig.kafka.bootstrapServers)

    // Prometheus setup (identical to KafkaClient)
    DefaultExports.initialize()
    val prometheusServer = new HTTPServer(9081)
    logger.info("Prometheus metrics on port 9081")

    sys.addShutdownHook {
      system.terminate()
      fs.close()
      prometheusServer.stop()
    }

    // Video processing with metrics
    def processVideo(path: Path): Unit = {
      val inputStream = fs.open(path)
      val grabber = new FFmpegFrameGrabber(inputStream)
      val converter = new Java2DFrameConverter()
      
      // Explicitly typed Kafka sink
      val kafkaSink = Producer.plainSink[Array[Byte], Array[Byte]](producerSettings)

      try {
        grabber.setFormat(appConfig.video.format)
        grabber.setImageWidth(appConfig.video.frameWidth)
        grabber.setImageHeight(appConfig.video.frameHeight)
        grabber.setFrameRate(appConfig.video.frameRate)
        grabber.start()

        Source.unfold(0) { _ =>
          Try {
            val frame = grabber.grab()
            if (frame != null) {
              val startTime = System.nanoTime()
              val img = converter.convert(frame)
              try {
                val bytes = new Array[Byte](img.getWidth * img.getHeight * 3)
                img.getRaster.getDataElements(0, 0, img.getWidth, img.getHeight, bytes)

                // Metrics
                framesProduced.labels(APPLICATION_VALUE, INSTANCE_VALUE, JOB_VALUE).inc()
                frameSize.labels(APPLICATION_VALUE, INSTANCE_VALUE, JOB_VALUE).set(bytes.length)
                val elapsedSec = (System.nanoTime() - startTime) / 1e9
                
                frameProductionTime
                  .labels(APPLICATION_VALUE, INSTANCE_VALUE, JOB_VALUE)
                  .observe(elapsedSec)
                
                avgFrameProductionTimeSeconds
                  .labels(APPLICATION_VALUE, INSTANCE_VALUE, JOB_VALUE)
                  .set(elapsedSec)

                Some((0, new ProducerRecord[Array[Byte], Array[Byte]](appConfig.kafka.topic, bytes)))
              } finally {
                img.flush()
              }
            } else None
          }.recover {
            case e =>
              frameProductionErrors.labels(APPLICATION_VALUE, INSTANCE_VALUE, JOB_VALUE).inc()
              logger.error(s"Frame error: ${e.getMessage}")
              None
          }.get
        }.runWith(kafkaSink)
        .onComplete { _ =>
          Try(grabber.close())
          Try(inputStream.close())
          Try(converter.close())
        }
      } catch {
        case e: Exception =>
          Try(grabber.close())
          Try(inputStream.close())
          Try(converter.close())
          throw e
      }
    }

    // Main loop (identical polling approach)
    while (true) {
      fs.listStatus(new Path(appConfig.hdfs.videoPath))
        .filter(_.isFile)
        .filter(_.getPath.getName.matches("video_\\d{2}\\.mp4"))
        .foreach { fileStatus =>
          processVideo(fileStatus.getPath) 
        }
      Thread.sleep(5000)
    }
  }
}
