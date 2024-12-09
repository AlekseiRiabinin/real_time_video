import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import akka.actor.ActorSystem
import akka.kafka.{ConsumerSettings, ProducerSettings, Subscriptions}
import akka.kafka.scaladsl.{Consumer, Producer}
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.ActorMaterializer
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
  FrameGrabber,
  OpenCVFrameGrabber,
  OpenCVFrameConverter
}
import org.bytedeco.opencv.global.opencv_imgcodecs._
import org.bytedeco.opencv.global.opencv_core._
import org.bytedeco.opencv.opencv_core.Mat
import org.slf4j.LoggerFactory


object KafkaService extends App {
  implicit val system: ActorSystem = ActorSystem("VideoProcessingSystem")
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  val log = LoggerFactory.getLogger(getClass)

  // val bootstrapServers = "kafka-broker:9092" -> for cloud deployment (CHECK HOSTNAME!!!!)
  val bootstrapServers = "192.168.56.1:9092"
  val topic = "video-stream"

  // Producer settings
  val producerSettings = ProducerSettings(system, new ByteArraySerializer, new ByteArraySerializer)
    .withBootstrapServers(bootstrapServers)

  // Consumer settings
  val consumerSettings = ConsumerSettings(system, new ByteArrayDeserializer, new StringDeserializer)
    .withBootstrapServers(bootstrapServers)
    .withGroupId("video-processing-group")
    .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")

  // Initialize OpenCV frame grabber
  val grabber: FrameGrabber = new OpenCVFrameGrabber(1) // 0 for default camera
  grabber.start()
  log.info("Frame grabber started")

  // Initialize OpenCV frame converter
  val converter = new OpenCVFrameConverter.ToMat()

  // Producer
  val producer = Source
    .repeat(())
    .map { _ =>
      val frame = grabber.grab()
      if (frame != null) {
        log.info("Frame grabbed")
        // Convert frame to Mat
        val mat = converter.convert(frame)
        // Encode Mat to byte array
        val byteArray = new Array[Byte](mat.total().toInt * mat.elemSize().toInt)
        mat.data().get(byteArray)
        new ProducerRecord[Array[Byte], Array[Byte]](topic, byteArray)
      } else {
        log.warn("Frame is null")
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
}
