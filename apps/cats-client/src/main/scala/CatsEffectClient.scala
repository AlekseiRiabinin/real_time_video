import cats.effect._
import fs2.Stream
import fs2.kafka._


object KafkaServiceCatsEffect extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    val consumerStream = KafkaConsumer.stream(consumerSettings[IO, Array[Byte], Array[Byte]])
      .subscribeTo(topic)
      .records
      .map(_.record.value)
      .evalMap { byteArray =>
        // Process the data and forward it to Flink/Spark
        IO(println(s"Processed message of size: ${byteArray.length} bytes"))
      }

    consumerStream.compile.drain.as(ExitCode.Success)
  }
}
