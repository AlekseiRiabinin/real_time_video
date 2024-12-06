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


object KafkaService extends App {
  implicit val system: ActorSystem = ActorSystem("VideoProcessingSystem")
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  val bootstrapServers = "kafka-broker:9092"
  val topic = "video-stream"

  // Producer settings
  val producerSettings = ProducerSettings(system, new ByteArraySerializer, new ByteArraySerializer)

  // Consumer settings
  val consumerSettings = ConsumerSettings(system, new ByteArrayDeserializer, new StringDeserializer)
    .withBootstrapServers(bootstrapServers)
    .withGroupId("video-processing-group")
    .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")

  // Initialize OpenCV frame grabber
  val grabber: FrameGrabber = new OpenCVFrameGrabber(0) // 0 for default camera
  grabber.start()

  // Initialize OpenCV frame converter
  val converter = new OpenCVFrameConverter.ToMat()

  // Producer
  val producer = Source
    .repeat(())
    .map { _ =>
      val frame = grabber.grab()
      if (frame != null) {
        // Convert frame to Mat
        val mat = converter.convert(frame)
        // Encode Mat to byte array
        val byteArray = new Array[Byte](mat.total().toInt * mat.elemSize().toInt)
        mat.data().get(byteArray)
        new ProducerRecord[Array[Byte], Array[Byte]](topic, byteArray)
      } else {
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
        println(s"Consumed message: ${msg.value}")
        // Process the message
        // ...
        msg
      }
    }
    .runWith(Sink.ignore)
}
