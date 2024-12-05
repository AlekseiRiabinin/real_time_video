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


object KafkaService extends App {
  implicit val system: ActorSystem = ActorSystem("VideoProcessingSystem")
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  val bootstrapServers = "kafka-broker:9092"
  val topic = "video-stream"

  // Producer settings
  val producerSettings = ProducerSettings(system, new ByteArraySerializer, new StringSerializer)
    .withBootstrapServers(bootstrapServers)

  // Consumer settings
  val consumerSettings = ConsumerSettings(system, new ByteArrayDeserializer, new StringDeserializer)
    .withBootstrapServers(bootstrapServers)
    .withGroupId("video-processing-group")
    .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")

  // Producer
  val producer = Source(1 to 100)
    .map(_.toString)
    .map { elem =>
      new ProducerRecord[Array[Byte], String](topic, elem)
    }
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
