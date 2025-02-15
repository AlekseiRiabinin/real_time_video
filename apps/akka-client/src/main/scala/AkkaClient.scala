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

  // Load configuration
  val config = ConfigFactory.load()
  val hdfsURI = config.getString("hdfs.uri")
  val bootstrapServers = config.getString("kafka.bootstrap-servers")
  val topic = config.getString("kafka.topic")
  val frameWidth = config.getInt("video.frame-width")
  val frameHeight = config.getInt("video.frame-height")
  val frameRate = config.getInt("video.frame-rate")

  // HDFS configuration
  val conf = new Configuration()
  conf.set("fs.defaultFS", hdfsURI)
  val fs = FileSystem.get(new URI(hdfsURI), conf)

  // Kafka producer settings
  val producerSettings = ProducerSettings(system, new ByteArraySerializer, new ByteArraySerializer)
    .withBootstrapServers(bootstrapServers)
    .withProperty("acks", "all")
    .withProperty("retries", "3")
    .withProperty("linger.ms", "5")
    .withProperty("compression.type", "snappy")

  // Prometheus metrics for producer
  val framesProduced = Counter.build()
    .name("frames_produced_total")
    .help("Total number of frames produced")
    .register()

  val frameProductionTime = Histogram.build()
    .name("frame_production_time_seconds")
    .help("Time taken to produce each frame")
    .register()

  val frameProductionErrors = Counter.build()
    .name("frame_production_errors_total")
    .help("Total number of frame production errors")
    .register()

  val frameSize = Gauge.build()
    .name("frame_size_bytes")
    .help("Size of each frame in bytes")
    .register()

  // Start Prometheus HTTP server
  DefaultExports.initialize()
  val server = new HTTPServer(9091)
  log.info("Prometheus HTTP server started on port 9091")

  // Path to the video file in HDFS
  val hdfsVideoPath = "/videos/video.mp4"

  // Shutdown hook
  sys.addShutdownHook {
    log.info("Shutting down AkkaClient...")
    fs.close()
    system.terminate()
    server.stop()
    log.info("AkkaClient stopped")
  }

  try {
    // Download the video file from HDFS to a local temporary file
    val localVideoPath = "/tmp/video.mp4"
    fs.copyToLocalFile(new Path(hdfsVideoPath), new Path(localVideoPath))
    log.info(s"Video file downloaded from HDFS to $localVideoPath")

    // Initialize frame grabber for the local video file
    val grabber = new FFmpegFrameGrabber(localVideoPath)
    grabber.setImageWidth(frameWidth)
    grabber.setImageHeight(frameHeight)
    grabber.setFrameRate(frameRate)
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

        val record = new ProducerRecord[Array[Byte], Array[Byte]](topic, byteArray)

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

// METRICS:

// frames_produced_total: Total number of frames produced.

// frame_production_time_seconds: Time taken to produce each frame.

// frame_production_errors_total: Total number of frame production errors.

// frame_size_bytes: Size of each frame in bytes.

// resource_usage: CPU, memory, and network usage (use default Prometheus JVM metrics).