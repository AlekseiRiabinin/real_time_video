import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import java.net.URI
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.bytedeco.javacv.{FFmpegFrameGrabber, Java2DFrameConverter}
import java.util.Properties


object KafkaClient {
  def main(args: Array[String]): Unit = {
    // HDFS configuration
    val hdfsUri = "hdfs://namenode:8020"
    val conf = new Configuration()
    conf.set("fs.defaultFS", hdfsUri)
    val fs = FileSystem.get(new URI(hdfsUri), conf)

    // Kafka configuration
    val kafkaBootstrapServers = "kafka-1:9092,kafka-2:9095"
    val kafkaTopic = "video-stream"
    val kafkaProps = new Properties()
    kafkaProps.put("bootstrap.servers", kafkaBootstrapServers)
    kafkaProps.put("key.serializer", classOf[ByteArraySerializer].getName)
    kafkaProps.put("value.serializer", classOf[ByteArraySerializer].getName)
    val kafkaProducer = new KafkaProducer[Array[Byte], Array[Byte]](kafkaProps)

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

      // Process each frame and send it to Kafka
      var frame = grabber.grab()
      while (frame != null) {
        val bufferedImage = converter.convert(frame)
        val byteArray = new Array[Byte](bufferedImage.getWidth * bufferedImage.getHeight * 3)
        val raster = bufferedImage.getRaster
        raster.getDataElements(0, 0, bufferedImage.getWidth, bufferedImage.getHeight, byteArray)

        // Send the frame to Kafka
        val record = new ProducerRecord[Array[Byte], Array[Byte]](kafkaTopic, byteArray)
        kafkaProducer.send(record)
        println("Frame sent to Kafka")

        frame = grabber.grab()
      }

      println("End of video file reached")
    } catch {
      case ex: Exception =>
        println(s"Error processing video: ${ex.getMessage}")
    } finally {
      fs.close()
      kafkaProducer.close()
    }
  }
}
