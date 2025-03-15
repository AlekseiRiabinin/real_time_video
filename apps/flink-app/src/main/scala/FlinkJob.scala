import org.apache.flink.api.common.serialization.{SerializationSchema, DeserializationSchema}
import org.apache.flink.api.common.typeinfo.{TypeInformation, Types}
import org.apache.flink.streaming.api.scala._
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment
import org.apache.flink.streaming.connectors.kafka.{
  FlinkKafkaConsumer,
  FlinkKafkaProducer,
  KafkaSerializationSchema
}
import org.apache.flink.api.common.ExecutionMode
import org.apache.flink.configuration.{Configuration, RestOptions, RestartStrategyOptions}
import java.util.Properties
import java.time.Duration
import java.io.FileInputStream
import org.bytedeco.javacv.{Java2DFrameConverter, OpenCVFrameConverter}
import org.bytedeco.opencv.global.opencv_core._
import org.bytedeco.opencv.global.opencv_imgproc._
import org.bytedeco.opencv.opencv_core._
import org.apache.kafka.clients.producer.ProducerRecord


class ByteArraySchema extends KafkaSerializationSchema[Array[Byte]] {
  override def serialize(
    element: Array[Byte],
    timestamp: java.lang.Long
  ): ProducerRecord[Array[Byte], Array[Byte]] = {
    new ProducerRecord[Array[Byte], Array[Byte]]("processed-data", null, element)
  }
}

object FlinkJob {
  def main(args: Array[String]): Unit = {
    // Set up the execution environment
    val config = new Configuration()
    config.setString(RestartStrategyOptions.RESTART_STRATEGY, "fixed-delay")
    config.setInteger(RestartStrategyOptions.RESTART_STRATEGY_FIXED_DELAY_ATTEMPTS, 3)
    config.set(RestartStrategyOptions.RESTART_STRATEGY_FIXED_DELAY_DELAY, Duration.ofSeconds(10))
    
    val env = StreamExecutionEnvironment.getExecutionEnvironment(config)
    env.getConfig.setExecutionMode(ExecutionMode.PIPELINED)
    env.setParallelism(2)

    // Load Kafka consumer properties
    val properties = new Properties()
    properties.load(new FileInputStream("/app/config/consumer.properties"))

    // Create the Kafka consumer
    val kafkaConsumer = new FlinkKafkaConsumer[Array[Byte]](
      "video-stream",
      new DeserializationSchema[Array[Byte]] {
        override def deserialize(message: Array[Byte]): Array[Byte] = message
        override def isEndOfStream(nextElement: Array[Byte]): Boolean = false
        override def getProducedType: TypeInformation[Array[Byte]] = TypeInformation.of(classOf[Array[Byte]])
      },
      properties
    )

    // Add the Kafka consumer as a source to the Flink job
    val stream = env.addSource(kafkaConsumer)

    // Process the stream for image resizing
    val processedStream = stream.map { bytes =>
      // Convert byte array to OpenCV Mat
      val mat = new Mat(new Size(256, 256), CV_8UC3) // Assuming the input image is 256x256
      mat.data().put(bytes: _*)

      // Resize the image to 64x64
      val resized = new Mat()
      resize(mat, resized, new Size(64, 64))

      // Convert the resized Mat back to byte array
      val resizedBytes = new Array[Byte](resized.total().toInt * resized.elemSize().toInt)
      resized.data().get(resizedBytes)

      resizedBytes
    }

    // Create the Kafka producer
    val kafkaProducer = new FlinkKafkaProducer[Array[Byte]](
      "processed-data",
      new ByteArraySchema(),
      properties,
      FlinkKafkaProducer.Semantic.EXACTLY_ONCE
    )

    // Add the Kafka producer as a sink to the Flink job
    processedStream.addSink(kafkaProducer)

    // Execute the Flink job
    env.execute("FlinkJob Kafka Consumer with Image Resizing")
  }
}
