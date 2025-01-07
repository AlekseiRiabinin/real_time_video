import org.apache.flink.api.common.serialization.DeserializationSchema
import org.apache.flink.api.common.serialization.SerializationSchema
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.common.typeinfo.Types
import org.apache.flink.streaming.api.scala._
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer
import org.apache.flink.api.common.ExecutionMode
import org.apache.flink.configuration.Configuration
import java.util.Properties


class ByteArraySchema extends DeserializationSchema[Array[Byte]] with SerializationSchema[Array[Byte]] {
  override def deserialize(message: Array[Byte]): Array[Byte] = message
  override def isEndOfStream(nextElement: Array[Byte]): Boolean = false
  override def serialize(element: Array[Byte]): Array[Byte] = element
  override def getProducedType: TypeInformation[Array[Byte]] = TypeInformation.of(classOf[Array[Byte]])
}


object FlinkJob {
  def main(args: Array[String]): Unit = {
    // Create a configuration object
    val config = new Configuration()
    // config.setString("taskmanager.numberOfTaskSlots", "1")
    config.setString("taskmanager.memory.process.size", "1024m")
    // config.setString("taskmanager.memory.flink.size", "512m")
    config.setString("taskmanager.memory.framework.heap.size", "128m")
    // config.setString("taskmanager.memory.framework.off-heap.size", "128m")
    config.setString("taskmanager.memory.jvm-metaspace.size", "256m")
    config.setString("taskmanager.memory.jvm-overhead.min", "192m")
    config.setString("taskmanager.memory.jvm-overhead.max", "1g")
    // config.setString("taskmanager.memory.jvm-overhead.fraction", "0.1")
    config.setString("taskmanager.memory.network.min", "64m")
    config.setString("taskmanager.memory.network.max", "64m")
    // config.setString("taskmanager.cpu.cores", "2")
    config.setString("metrics.reporter.slf4j.factory.class", "org.apache.flink.metrics.slf4j.Slf4jReporterFactory")
    config.setString("metrics.reporter.slf4j.interval", "10 SECONDS")
    // config.setString("web.log.path", "logs/")

    // Set up the execution environment
    val env = StreamExecutionEnvironment.getExecutionEnvironment(config)
    env.getConfig.setExecutionMode(ExecutionMode.PIPELINED)
    env.setParallelism(1)

    // Set up the Kafka consumer properties
    val properties = new Properties()
    properties.setProperty("bootstrap.servers", "kafka-1:9092,kafka-2:9095")
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
