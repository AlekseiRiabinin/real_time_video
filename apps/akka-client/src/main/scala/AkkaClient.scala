import akka.actor.ActorSystem
import akka.kafka.ProducerSettings
import akka.kafka.scaladsl.Producer
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, Materializer}
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.bytedeco.javacv.{FFmpegFrameGrabber, Java2DFrameConverter}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import java.net.URI


object AkkaClient extends App {
  implicit val system: ActorSystem = ActorSystem("AkkaClientSystem")
  implicit val materializer: ActorMaterializer = ActorMaterializer(
    ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 16, maxSize = 256) // Adjusted buffer sizes
  )

  // HDFS configuration
  val hdfsURI = "hdfs://namenode:8020"
  val conf = new Configuration()
  conf.set("fs.defaultFS", hdfsURI)
  val fs = FileSystem.get(new URI(hdfsURI), conf)

  // Kafka configuration
  val bootstrapServers = "kafka-1:9092,kafka-2:9095"
  val topic = "video-stream"
  val producerSettings = ProducerSettings(system, new ByteArraySerializer, new ByteArraySerializer)
    .withBootstrapServers(bootstrapServers)

  // Path to the video file in HDFS
  val hdfsVideoPath = "/videos/video.mp4"

  try {
    // Download the video file from HDFS to a local temporary file
    val localVideoPath = "/tmp/video.mp4"
    fs.copyToLocalFile(new Path(hdfsVideoPath), new Path(localVideoPath))
    println(s"Video file downloaded from HDFS to $localVideoPath")

    // Initialize frame grabber for the local video file
    val grabber = new FFmpegFrameGrabber(localVideoPath)
    grabber.setImageWidth(256) // Set the width of the captured image
    grabber.setImageHeight(256) // Set the height of the captured image
    grabber.setFrameRate(1) // Set the frame rate
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

    // Run the stream
    frameSource
      .map { byteArray =>
        new ProducerRecord[Array[Byte], Array[Byte]](topic, byteArray)
      }
      .runWith(kafkaSink)

    println("Stream started. Sending frames to Kafka...")
  } catch {
    case ex: Exception =>
      println(s"Error processing video: ${ex.getMessage}")
  } finally {
    fs.close()
    system.terminate()
  }
}
