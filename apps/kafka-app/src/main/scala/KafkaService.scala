import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import akka.actor.{ActorSystem, CoordinatedShutdown}
import akka.kafka.{ConsumerSettings, ProducerSettings, Subscriptions}
import akka.kafka.scaladsl.{Consumer, Producer}
import akka.stream.scaladsl.{Sink, Source}
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.{
  ByteArrayDeserializer,
  ByteArraySerializer,
  StringDeserializer,
  StringSerializer
}
import org.bytedeco.javacv.{
  CanvasFrame,
  // OpenCVFrameGrabber,
  // OpenCVFrameConverter
}
import org.bytedeco.opencv.global.opencv_imgcodecs._
import org.bytedeco.opencv.global.opencv_core._
import org.bytedeco.opencv.opencv_videoio.VideoCapture
import org.bytedeco.opencv.opencv_core.Mat
import org.slf4j.LoggerFactory
import javax.swing.WindowConstants


object KafkaService extends App {
  implicit val system: ActorSystem = ActorSystem("VideoProcessingSystem")
  val log = LoggerFactory.getLogger(getClass)

  // val bootstrapServers = "kafka-broker:9092" -> for cloud deployment (CHECK HOSTNAME!!!!)
  // val bootstrapServers = "192.168.56.1:9092"
  val bootstrapServers = "kafka-1:9092,kafka-2:9095"
  val topic = "video-stream"

  // Producer settings
  val producerSettings = ProducerSettings(system, new ByteArraySerializer, new ByteArraySerializer)
    .withBootstrapServers(bootstrapServers)
    .withProperty("acks", "all") // Ensure all replicas acknowledge
    .withProperty("batch.size", "16384")
    .withProperty("linger.ms", "5")
    .withProperty("retries", "10")
    .withProperty("retry.backoff.ms", "1000")
    .withProperty("enable.idempotence", "true") // Ensure idempotent producer
    .withProperty("connections.max.idle.ms", "10000")
    .withProperty("request.timeout.ms", "30000")
    

  // Consumer settings
  val consumerSettings = ConsumerSettings(system, new ByteArrayDeserializer, new StringDeserializer)
    .withBootstrapServers(bootstrapServers)
    .withGroupId("video-processing-group")
    .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    .withProperty(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, "50000")
    .withProperty(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, "500")
    .withProperty(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "45000")
    .withProperty(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, "15000")
    .withProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false") // Disable auto commit for better control

  // Initialize OpenCV frame grabber
  val capture = new VideoCapture(0) // 0 for default camera
    if (!capture.isOpened) {
    log.error("Failed to open camera")
    System.exit(1)
  }

  val mat = new Mat()
  capture.read(mat)
  if (mat.empty()) {
    log.error("Failed to grab a test frame. Camera might not be working.")
    System.exit(1)
  }
  log.info("Test frame grabbed successfully")

  // Initialize CanvasFrame
  val canvas = new CanvasFrame("Camera Test")
  canvas.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE)

  // Add shutdown hook to release resources
  def releaseResources(): Unit = {
    log.info("Shutting down...")
    try {
      capture.release()
      log.info("VideoCapture released")
    } catch {
      case ex: Exception => log.error("Error releasing VideoCapture", ex)
    }
    try {
      canvas.dispose()
      log.info("Canvas disposed")
    } catch {
      case ex: Exception => log.error("Error disposing canvas", ex)
    }
    system.terminate()
  }

  // Shutdown hook using sys.addShutdownHook
  sys.addShutdownHook {
    releaseResources()
  }

  // Producer
  val producer = Source
    .tick(0.seconds, 100.milliseconds, ())
    // .takeWithin(2.minutes) // Adjust the duration here
    .mapAsync(1) { _ =>
      Future {
        val frame = capture.synchronized {
          log.info("Attempting to grab frame")
          capture.read(mat)
          mat
        }
        if (!mat.empty()) {
          log.info("Frame grabbed")
          val byteArray = new Array[Byte](mat.total().toInt * mat.elemSize().toInt)
          mat.data().get(byteArray)
          new ProducerRecord[Array[Byte], Array[Byte]](topic, byteArray)
        } else {
          log.warn("Frame is null")
          null
        }
      }.recover {
        case ex: Exception =>
        log.error("Error grabbing frame", ex)
        null
      }        
    }
    .filter(_ != null)
    .runWith(Producer.plainSink(producerSettings))

  // Consumer
  val consumer = Consumer
    .plainSource(consumerSettings, Subscriptions.topics(topic))
    .mapAsync(1) { msg =>
      Future {
        val byteArray = msg.value()
        log.info(s"Consumed message of size: ${byteArray.length} bytes")
        log.debug(s"Hex dump: ${byteArray.map("%02X".format(_)).mkString(" ")}")
        msg
      }
    }
    .runWith(Sink.ignore)

  // Log application start
  log.info("Application started")

  // Keep the application running
  CoordinatedShutdown(system).addJvmShutdownHook {
    releaseResources()
  }
  system.whenTerminated.onComplete(_ => releaseResources())
}

