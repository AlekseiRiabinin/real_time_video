import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import akka.actor.ActorSystem
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.kafka.scaladsl.Consumer
import akka.kafka.ConsumerMessage.{CommittableMessage, CommittableOffsetBatch}
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl.Sink
import com.typesafe.config.ConfigFactory
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.{ByteArrayDeserializer, StringDeserializer}
import org.slf4j.LoggerFactory
import io.prometheus.client.exporter.HTTPServer
import io.prometheus.client.hotspot.DefaultExports
import io.prometheus.client.{CollectorRegistry, Counter, Gauge, Histogram}


object KafkaService extends App {
  implicit val system: ActorSystem = ActorSystem("KafkaServiceSystem")
  implicit val materializer: Materializer = Materializer(system)

  val log = LoggerFactory.getLogger(getClass)
  
  // Load configuration
  val config = ConfigFactory.load()
  val bootstrapServers = config.getString("kafka.bootstrap-servers")
  val topic = config.getString("kafka.topic")
  val consumerGroup = config.getString("kafka.consumer-group")
  val commitBatchSize = config.getInt("kafka.commit-batch-size")
 
  // Consumer settings
  val consumerSettings = ConsumerSettings(system, new ByteArrayDeserializer, new StringDeserializer)
    .withBootstrapServers(bootstrapServers)
    .withGroupId(consumerGroup)
    .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
    .withProperty(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, "100000")
    .withProperty(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, "500")
    .withProperty(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "10000")
    .withProperty(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, "3000")
    .withProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
    .withProperty(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, "30000")
    .withProperty(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, "1048576")

  // Prometheus metrics for consumer
  val messagesConsumed = Counter.build()
    .name("messages_consumed_total")
    .help("Total number of messages consumed")
    .register()

  val messageConsumptionTime = Histogram.build()
    .name("message_consumption_time_seconds")
    .help("Time taken to consume each message")
    .register()

  val messageConsumptionErrors = Counter.build()
    .name("message_consumption_errors_total")
    .help("Total number of message consumption errors")
    .register()

  val consumerLag = Gauge.build()
    .name("consumer_lag_seconds")
    .help("Time lag between message production and consumption")
    .register()

  // Start Prometheus HTTP server
  DefaultExports.initialize()
  val server = new HTTPServer(9091)
  log.info("Prometheus HTTP server started on port 9091")

  // Consumer
  val numConsumers = 2 // Number of consumer instances

  for (i <- 1 to numConsumers) {
    val consumer = Consumer
      .committableSource(consumerSettings, Subscriptions.topics(topic))
      .mapAsync(1) { (msg: CommittableMessage[Array[Byte], String]) =>
        val startTime = System.nanoTime()
        messagesConsumed.inc() // Increment messages consumed counter

        val byteArray = msg.record.value()
        val produceTime = msg.record.timestamp() // Timestamp when the message was produced
        val lagTime = if (produceTime >= 0) (System.currentTimeMillis() - produceTime) / 1000.0 else 0
        consumerLag.set(lagTime) // Update consumer lag gauge

        Future {
          val byteArray = msg.record.value()
          // Process the message (e.g., forward to Flink/Spark)

          log.info(s"Consumed message of size: ${byteArray.length} bytes")
          log.debug(s"Consumer lag: $lagTime seconds")

          val endTime = System.nanoTime()
          messageConsumptionTime.observe((endTime - startTime) / 1e9) // Update consumption time histogram

          msg.committableOffset
        }.recover {
          case ex: Exception =>
            messageConsumptionErrors.inc() // Increment error counter
            log.error("Error processing message", ex)
            msg.committableOffset
        }
      }
      .batch(max = commitBatchSize, first => CommittableOffsetBatch.empty.updated(first)) { (batch, elem) =>
        batch.updated(elem)
      }
      .mapAsync(1)(_.commitScaladsl())
      .runWith(Sink.ignore)(materializer)
  }
  
  // Log application start
  log.info(s"Starting Kafka consumer for topic: $topic, group: $consumerGroup")

  // Graceful shutdown
  sys.addShutdownHook {
    log.info("Shutting down KafkaService...")
    system.terminate()
    server.stop()
    log.info("KafkaService stopped")
  }
}

// METRICS

// messages_consumed_total: Total number of messages consumed.

// message_consumption_time_seconds: Time taken to consume each message.

// message_consumption_errors_total: Total number of message consumption errors.

// consumer_lag_seconds: Time lag between message production and consumption.

// resource_usage: CPU, memory, and network usage (use default Prometheus JVM metrics).