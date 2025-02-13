import cats.effect.{IO, IOApp, Resource}
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerConfig, ProducerRecord}
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import java.net.URI
import org.bytedeco.javacv.{FFmpegFrameGrabber, Java2DFrameConverter}
import scala.concurrent.duration._
import java.util.Properties


object CatsClient extends IOApp.Simple {

  // HDFS configuration
  val hdfsURI = "hdfs://namenode:8020"
  val conf = new Configuration()
  conf.set("fs.defaultFS", hdfsURI)
  val fs = FileSystem.get(new URI(hdfsURI), conf)

  // Kafka configuration
  val kafkaBootstrapServers = "kafka-1:9092,kafka-2:9095"
  val kafkaTopic = "video-stream"

  // Path to the video file in HDFS
  val hdfsVideoPath = "/videos/video.mp4"

  // Kafka Producer Properties
  val kafkaProps: Properties = new Properties()
  kafkaProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers)
  kafkaProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, classOf[ByteArraySerializer].getName)
  kafkaProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, classOf[ByteArraySerializer].getName)

  // Resource for Kafka Producer
  def kafkaProducerResource: Resource[IO, KafkaProducer[Array[Byte], Array[Byte]]] =
    Resource.make(IO(new KafkaProducer[Array[Byte], Array[Byte]](kafkaProps)))(producer => IO(producer.close()))

  // Function to process video frames and send them to Kafka
  def processVideoFrames(producer: KafkaProducer[Array[Byte], Array[Byte]]): IO[Unit] = {
    val localVideoPath = "/tmp/video.mp4"

    // Download the video file from HDFS to a local temporary file
    IO(fs.copyToLocalFile(new Path(hdfsVideoPath), new Path(localVideoPath))) *>
      IO(println(s"Video file downloaded from HDFS to $localVideoPath")) *>
      IO.blocking {
        val grabber = new FFmpegFrameGrabber(localVideoPath)
        grabber.setImageWidth(256) // Set the width of the captured image
        grabber.setImageHeight(256) // Set the height of the captured image
        grabber.setFrameRate(1) // Set the frame rate
        grabber.start()

        val converter = new Java2DFrameConverter()

        var frame = grabber.grab()
        while (frame != null) {
          val bufferedImage = converter.convert(frame)
          val byteArray = new Array[Byte](bufferedImage.getWidth * bufferedImage.getHeight * 3)
          val raster = bufferedImage.getRaster
          raster.getDataElements(0, 0, bufferedImage.getWidth, bufferedImage.getHeight, byteArray)

          // Send the frame to Kafka
          val record = new ProducerRecord[Array[Byte], Array[Byte]](kafkaTopic, Array.empty[Byte], byteArray)
          producer.send(record).get() // Blocking call, but wrapped in IO.blocking
          println("Frame sent to Kafka")

          frame = grabber.grab()
        }

        println("End of video file reached")
      }.handleErrorWith { ex =>
        IO(println(s"Error processing video: ${ex.getMessage}"))
      }
  }

  // Main entry point
  override def run: IO[Unit] = {
    kafkaProducerResource.use { producer =>
      processVideoFrames(producer)
    }
  }
}
