import org.apache.flink.api.common.serialization.{SerializationSchema, DeserializationSchema}
import org.apache.flink.api.common.typeinfo.{TypeInformation, Types}
import org.apache.flink.streaming.api.scala._
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment
import org.apache.flink.streaming.connectors.kafka.{FlinkKafkaConsumer, FlinkKafkaProducer}
import org.apache.flink.api.common.ExecutionMode
import org.apache.flink.configuration.Configuration
import java.util.Properties
import org.bytedeco.javacv.{Java2DFrameConverter, OpenCVFrameConverter}
import org.bytedeco.opencv.global.opencv_core._
import org.bytedeco.opencv.global.opencv_imgproc._
import org.bytedeco.opencv.opencv_core._


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
    env.getConfig.setExecutionMode(ExecutionMode.PIPELINED)
    env.setParallelism(2)

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

    // ---------------------------------------------------------------- 

    // // Add the Kafka consumer as a source to the Flink job
    // val stream = env.addSource(kafkaConsumer)

    // // Process the stream for anomaly detection
    // val processedStream = stream.map { bytes =>
    //   // Convert byte array to OpenCV Mat
    //   val mat = new Mat(bytes.length, 1, CV_8UC1)
    //   mat.data().put(bytes)

    //   // Convert Mat to BufferedImage
    //   val converter = new Java2DFrameConverter()
    //   val frameConverter = new OpenCVFrameConverter.ToMat()
    //   val frame = frameConverter.convert(mat)
    //   val bufferedImage = converter.convert(frame)

    //   // Perform anomaly detection (example: detect edges)
    //   val gray = new Mat()
    //   cvtColor(mat, gray, COLOR_BGR2GRAY)
    //   val edges = new Mat()
    //   Canny(gray, edges, 50, 150)

    //   // Convert processed Mat back to byte array
    //   val processedBytes = new Array[Byte](edges.total().toInt * edges.elemSize().toInt)
    //   edges.data().get(processedBytes)

    //   processedBytes
    // }

    // val kafkaProducer = new FlinkKafkaProducer[Array[Byte]](
    //   "output-topic",
    //   new ByteArraySchema(),
    //   properties,
    //   FlinkKafkaProducer.Semantic.EXACTLY_ONCE
    // )

    // // Add the Kafka producer as a sink to the Flink job
    // processedStream.addSink(kafkaProducer)

    // ----------------------------------------------------------------

    // Execute the Flink job
    env.execute("FlinkJob Kafka Consumer")
  }
}
