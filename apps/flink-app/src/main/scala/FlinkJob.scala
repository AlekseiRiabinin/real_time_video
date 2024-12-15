import org.apache.flink.api.common.serialization.DeserializationSchema
import org.apache.flink.api.common.serialization.SerializationSchema
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.common.typeinfo.Types
import org.apache.flink.streaming.api.scala._
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer
import java.util.Properties


class ByteArraySchema extends DeserializationSchema[Array[Byte]] with SerializationSchema[Array[Byte]] {
  override def deserialize(message: Array[Byte]): Array[Byte] = message
  override def isEndOfStream(nextElement: Array[Byte]): Boolean = false
  override def serialize(element: Array[Byte]): Array[Byte] = element
  override def getProducedType: TypeInformation[Array[Byte]] = TypeInformation.of(classOf[Array[Byte]])
}


object FlinkJob {
  def main(args: Array[String]): Unit = {
    // Set up the execution environment
    val env = StreamExecutionEnvironment.getExecutionEnvironment

    // Set up the Kafka consumer properties
    val properties = new Properties()
    properties.setProperty("bootstrap.servers", "kafka-app-kafka-1-1:9092,kafka-app-kafka-2-1:9095")
    properties.setProperty("group.id", "video-processing-group")

    // Create the Kafka consumer
    val kafkaConsumer = new FlinkKafkaConsumer[Array[Byte]](
      "video-stream",
      new ByteArraySchema(),
      properties
    )

    // Add the Kafka consumer as a source to the Flink job
    val stream = env.addSource(kafkaConsumer)

    // Process the stream (for now, just print the size of the consumed messages)
    stream.map(bytes => s"Received message of size: ${bytes.length} bytes").print()

    // Execute the Flink job
    env.execute("FlinkJob Kafka Consumer")
  }
}