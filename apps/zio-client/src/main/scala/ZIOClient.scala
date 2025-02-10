import zio._
import zio.kafka.consumer._


object KafkaServiceZIO extends ZIOAppDefault {
  override def run: ZIO[Any, Throwable, Unit] = {
    val consumerStream = Consumer
      .subscribeAnd(Subscription.topics(topic))
      .plainStream
      .map(_.record.value)
      .tap { byteArray =>
        // Process the data and forward it to Flink/Spark
        ZIO(println(s"Processed message of size: ${byteArray.length} bytes"))
      }

    consumerStream.runDrain
  }
}
