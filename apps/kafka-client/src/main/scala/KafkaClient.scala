import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import java.net.URI
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.bytedeco.javacv.{FFmpegFrameGrabber, Java2DFrameConverter}
import io.prometheus.client.exporter.HTTPServer
import io.prometheus.client.hotspot.DefaultExports
import io.prometheus.client.{CollectorRegistry, Counter, Gauge, Histogram}
import com.typesafe.config.ConfigFactory
import java.util.Properties


object KafkaClient {

  // Configuration case classes
  case class HdfsConfig(uri: String, videoPath: String)
  case class KafkaConfig(bootstrapServers: String, topic: String)
  case class VideoConfig(frameWidth: Int, frameHeight: Int, frameRate: Int)
  case class AppConfig(hdfs: HdfsConfig, kafka: KafkaConfig, video: VideoConfig)

  // Prometheus metrics
  val framesProduced: Counter = Counter.build()
    .name("frames_produced_total")
    .help("Total number of frames produced")
    .register()

  val frameProductionTime: Histogram = Histogram.build()
    .name("frame_production_time_seconds")
    .help("Time taken to produce each frame")
    .register()

  val frameProductionErrors: Counter = Counter.build()
    .name("frame_production_errors_total")
    .help("Total number of frame production errors")
    .register()

  val frameSize: Gauge = Gauge.build()
    .name("frame_size_bytes")
    .help("Size of each frame in bytes")
    .register()

  def main(args: Array[String]): Unit = {
    // Load configuration from application.conf
    val config = ConfigFactory.load()
    val appConfig = AppConfig(
      HdfsConfig(
        uri = config.getString("hdfs.uri"),
        videoPath = config.getString("hdfs.video-path")
      ),
      KafkaConfig(
        bootstrapServers = config.getString("kafka.bootstrap-servers"),
        topic = config.getString("kafka.topic")
      ),
      VideoConfig(
        frameWidth = config.getInt("video.frame-width"),
        frameHeight = config.getInt("video.frame-height"),
        frameRate = config.getInt("video.frame-rate")
      )
    )

    // HDFS configuration
    val conf = new Configuration()
    conf.set("fs.defaultFS", appConfig.hdfs.uri)
    val fs = FileSystem.get(new URI(appConfig.hdfs.uri), conf)

    // Kafka configuration
    val kafkaProps = new Properties()
    kafkaProps.put("bootstrap.servers", appConfig.kafka.bootstrapServers)
    kafkaProps.put("key.serializer", classOf[ByteArraySerializer].getName)
    kafkaProps.put("value.serializer", classOf[ByteArraySerializer].getName)
    val kafkaProducer = new KafkaProducer[Array[Byte], Array[Byte]](kafkaProps)

    // Initialize Prometheus default metrics and start HTTP server
    DefaultExports.initialize()
    val prometheusServer = new HTTPServer(9080)
    println("Prometheus HTTP server started on port 9080")

    try {
      // Open an InputStream to read the video file directly from HDFS
      val hdfsInputStream = fs.open(new Path(appConfig.hdfs.videoPath))
      println(s"Video file opened from HDFS: ${appConfig.hdfs.videoPath}")

      // Initialize frame grabber with the HDFS InputStream
      val grabber = new FFmpegFrameGrabber(hdfsInputStream)
      grabber.setImageWidth(appConfig.video.frameWidth)
      grabber.setImageHeight(appConfig.video.frameHeight)
      grabber.setFrameRate(appConfig.video.frameRate)
      grabber.start()

      val converter = new Java2DFrameConverter()

      // Process each frame and send it to Kafka
      var frame = grabber.grab()
      while (frame != null) {
        val startTime = System.nanoTime()
        val bufferedImage = converter.convert(frame)
        val byteArray = new Array[Byte](bufferedImage.getWidth * bufferedImage.getHeight * 3)
        val raster = bufferedImage.getRaster
        raster.getDataElements(0, 0, bufferedImage.getWidth, bufferedImage.getHeight, byteArray)

        // Update Prometheus metrics
        framesProduced.inc()
        frameSize.set(byteArray.length)
        frameProductionTime.observe((System.nanoTime() - startTime) / 1e9)

        // Send the frame to Kafka
        val record = new ProducerRecord[Array[Byte], Array[Byte]](appConfig.kafka.topic, byteArray)
        kafkaProducer.send(record)
        println("Frame sent to Kafka")

        frame = grabber.grab()
      }

      println("End of video file reached")
    } catch {
      case ex: Exception =>
        frameProductionErrors.inc()
        println(s"Error processing video: ${ex.getMessage}")
    } finally {
      fs.close()
      kafkaProducer.close()
      prometheusServer.stop()
    }
  }
}
