import zio._
import zio.kafka.producer._
import zio.kafka.serde._
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import java.net.URI
import org.bytedeco.javacv.{FFmpegFrameGrabber, Java2DFrameConverter}

object ZIOClient extends ZIOAppDefault {

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

  // Kafka Producer Settings
  val producerSettings: ProducerSettings = ProducerSettings(
    bootstrapServers = List(kafkaBootstrapServers)
  )

  // ZIO Layer for Kafka Producer
  val producerLayer: ZLayer[Any, Throwable, Producer] =
    ZLayer.scoped(Producer.make(producerSettings))

  // Function to process video frames and send them to Kafka
  def processVideoFrames(producer: Producer): ZIO[Any, Throwable, Unit] = {
    val localVideoPath = "/tmp/video.mp4"

    for {
      _ <- ZIO.attempt(fs.copyToLocalFile(new Path(hdfsVideoPath), new Path(localVideoPath)))
      _ <- ZIO.attempt(println(s"Video file downloaded from HDFS to $localVideoPath"))
      _ <- ZIO.attemptBlocking {
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
          producer.produce(record, Serde.byteArray, Serde.byteArray).catchAll { ex =>
            ZIO.attempt(println(s"Error sending frame to Kafka: ${ex.getMessage}"))
          }.forkDaemon // Run in the background
          println("Frame sent to Kafka")

          frame = grabber.grab()
        }

        println("End of video file reached")
      }.catchAll { ex =>
        ZIO.attempt(println(s"Error processing video: ${ex.getMessage}"))
      }
    } yield ()
  }

  // Main entry point
  override def run: ZIO[Any, Throwable, Unit] = {
    ZIO.serviceWithZIO[Producer](processVideoFrames).provideLayer(producerLayer)
  }
}
