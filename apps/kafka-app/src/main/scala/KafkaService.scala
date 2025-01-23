import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import akka.actor.{ActorSystem, CoordinatedShutdown}
import akka.kafka.{ConsumerSettings, ProducerSettings, Subscriptions}
import akka.kafka.scaladsl.{Consumer, Producer}
import akka.kafka.ConsumerMessage.{CommittableMessage, CommittableOffsetBatch}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import akka.stream.scaladsl.{Sink, Source}
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.{
  ByteArrayDeserializer,
  ByteArraySerializer,
  StringDeserializer,
  StringSerializer
}
import org.bytedeco.javacv.{
  FFmpegFrameGrabber,
  Java2DFrameConverter,
  FFmpegLogCallback
}
import org.slf4j.LoggerFactory
import sun.misc.{Signal, SignalHandler}
import io.prometheus.client.exporter.HTTPServer
import io.prometheus.client.hotspot.DefaultExports
import io.prometheus.client.{CollectorRegistry, Counter, Gauge, Histogram}

object KafkaService extends App {
  implicit val system: ActorSystem = ActorSystem("VideoProcessingSystem")
  implicit val materializer: ActorMaterializer = ActorMaterializer(
    ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 16, maxSize = 256) // Adjusted buffer sizes
  )

  val log = LoggerFactory.getLogger(getClass)
  
  // Set FFmpeg log callback for detailed logging
  FFmpegLogCallback.set()

  // Ensure all necessary libraries are loaded
  FFmpegFrameGrabber.tryLoad()

  // Signal handler for SIGTERM
  Signal.handle(new Signal("TERM"), new SignalHandler {
    def handle(sig: Signal): Unit = {
      log.info(s"Received signal: ${sig.getName}")
      releaseResources()
    }
  })

  // val bootstrapServers = "kafka-broker:9092" -> for cloud deployment (CHECK HOSTNAME!!!!)
  val bootstrapServers = "kafka-1:9092,kafka-2:9095"
  // val bootstrapServers = "172.18.0.2:9092,172.18.0.3:9095"
  // val bootstrapServers = "localhost:9092,localhost:9095" // when this app is running on host
  val topic = "video-stream"

  // Prometheus metrics
  // val registry = new CollectorRegistry()
  val frameProcessingTime = Histogram.build()
    .name("frame_processing_time_seconds")
    .help("Time taken to process each frame")
    .register()
  val framesProcessed = Counter.build()
    .name("frames_processed_total")
    .help("Total number of frames processed")
    .register()
  val frameSize = Gauge.build()
    .name("frame_size_bytes")
    .help("Size of each frame in bytes")
    .register()

  // Start Prometheus HTTP server
  DefaultExports.initialize()
  val server = new HTTPServer(9091)
  log.info("Prometheus HTTP server started on port 9091")
  
  // Producer settings
  val producerSettings = ProducerSettings(system, new ByteArraySerializer, new ByteArraySerializer)
    .withBootstrapServers(bootstrapServers)
    .withProperty("acks", "all") // Ensure all replicas acknowledge
    .withProperty("batch.size", "200000") // For lower latency
    .withProperty("linger.ms", "5") // For faster message delivery
    .withProperty("retries", "5") // Avoid excessive retry attempts
    .withProperty("retry.backoff.ms", "500") // For quicker retries
    .withProperty("enable.idempotence", "true") // Ensure idempotent producer
    .withProperty("connections.max.idle.ms", "10000")
    .withProperty("request.timeout.ms", "30000")
    .withProperty("compression.type", "snappy") // Enable compression
    .withProperty("socket.connection.setup.timeout.ms", "30000")
    .withProperty("socket.connection.setup.timeout.max.ms", "60000")
    .withProperty("socket.request.max.bytes", "10485760")
    .withProperty("max.request.size", "2097152") // Set to 2 MB
    .withProperty("fetch.max.bytes", "2097152")

  // Consumer settings
  val consumerSettings = ConsumerSettings(system, new ByteArrayDeserializer, new StringDeserializer)
    .withBootstrapServers(bootstrapServers)
    .withGroupId("video-processing-group")
    .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest") // Start consuming new messages
    .withProperty(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, "100000")
    .withProperty(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, "500") // For quicker fetches
    .withProperty(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "10000") // For quicker detection of failures    
    .withProperty(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, "3000") // For more frequent heartbeats
    .withProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false") // Disable auto commit for better control
    .withProperty(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, "30000")
    .withProperty(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, "1048576")

  // Initialize frame grabber
  // val grabber = new OpenCVFrameGrabber(0) // 0 for default camera
  // val grabber = new FFmpegFrameGrabber("/dev/video0")
  // grabber.setFormat("video4linux2") // Set the appropriate format
  val videoFilePath = "./video.mp4"
  val grabber = new FFmpegFrameGrabber(videoFilePath)

  try {
    // Set frame grabber options
    grabber.setImageWidth(256) // Set the width of the captured image
    grabber.setImageHeight(256) // Set the height of the captured image
    grabber.setFrameRate(1) // Set the frame rate

    // Start the frame grabber
    grabber.start()
    log.info("Frame grabber started with options: width=640, height=480, frameRate=30")
  } catch {
    case ex: Exception =>
      log.error("Error starting frame grabber", ex)
      System.exit(1) // Exit if the grabber fails to start
  }  

  val converter = new Java2DFrameConverter()
  if (grabber.grab() != null) {
    log.info("Test frame grabbed successfully")
  } else {
    log.error("Failed to grab a test frame. Camera might not be working.")
    System.exit(1)
  }

  // Add shutdown hook to release resources
  def releaseResources(): Unit = {
    log.info("Shutting down...")
    try {
      grabber.stop()
      log.info("FrameGrabber stopped")
    } catch {
      case ex: Exception => log.error("Error stopping FrameGrabber", ex)
    }
    system.terminate()
  }

  // Shutdown hook using sys.addShutdownHook
  sys.addShutdownHook {
    releaseResources()
    server.stop()
    log.info("Prometheus HTTP server stopped")
  }

  // Producer
  val producer = Source
    .tick(0.seconds, 300.milliseconds, ())
    .mapAsync(1) { _ =>
      Future {
        val startTime = System.currentTimeMillis()
        val frame = grabber.synchronized {
          log.info("Attempting to grab frame")
          grabber.grab()
        }
        val endTime = System.currentTimeMillis()
        val processingTime = endTime - startTime
        frameProcessingTime.observe(processingTime / 1000.0) // Update histogram
        log.info(s"Frame processing time: $processingTime ms")
        if (frame != null) {
          log.info("Frame grabbed")
          val bufferedImage = converter.convert(frame)
          log.info("Frame converted to BufferedImage")
          val byteArray = new Array[Byte](bufferedImage.getWidth * bufferedImage.getHeight * 3)
          val raster = bufferedImage.getRaster
          raster.getDataElements(0, 0, bufferedImage.getWidth, bufferedImage.getHeight, byteArray)
          frameSize.set(byteArray.length) // Update gauge
          framesProcessed.inc() // Increment counter
          log.info("Copy pixel data into byte array")
          new ProducerRecord[Array[Byte], Array[Byte]](topic, byteArray)
        } else {  
          log.info("End of video file reached")
          releaseResources()
          null
        }
      }.recover {
        case ex: Exception =>
        log.error("Error grabbing frame", ex)
        null
      }        
    }
    .filter(_ != null)
    .runWith(Producer.plainSink(producerSettings))(materializer) // Use materializer

  // Consumer
  val numConsumers = 2 // Number of consumer instances

  for (i <- 1 to numConsumers) {
    val consumer = Consumer
      .committableSource(consumerSettings, Subscriptions.topics(topic))
      .mapAsync(1) { (msg: CommittableMessage[Array[Byte], String]) =>
        Future {
          val byteArray = msg.record.value()
          log.info(s"Consumer $i: Consumed message of size: ${byteArray.length} bytes")
          log.debug(s"Hex dump: ${byteArray.map(b => "%02X".format(b & 0xFF)).mkString(" ")}")
          msg.committableOffset
        }.recover {
          case ex: Exception =>
          log.error(s"Consumer $i: Error processing message", ex)
          msg.committableOffset
        }
      }
      .batch(max = 20, first => CommittableOffsetBatch.empty.updated(first)) { (batch, elem) =>
        batch.updated(elem)
      }
      .mapAsync(1)(_.commitScaladsl())
      .runWith(Sink.ignore)(materializer)
  }
  
  // Log application start
  log.info("Application started")

  // Keep the application running
  CoordinatedShutdown(system).addJvmShutdownHook {
    releaseResources()
  }
  system.whenTerminated.onComplete(_ => releaseResources())
}
