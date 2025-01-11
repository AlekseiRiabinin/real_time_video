import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import org.apache.spark.ml.Pipeline
import org.apache.spark.ml.classification.LogisticRegression
import org.apache.spark.ml.feature.{HashingTF, Tokenizer}
import org.apache.spark.sql.streaming.Trigger


object SparkMLJob {
  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder
      .appName("SparkMLJob")
      .getOrCreate()

    // Read data from Kafka
    val kafkaDF = spark.readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", "kafka-1:9092,kafka-2:9095")
      .option("subscribe", "video-stream")
      .load()

    // Process data (example: text classification)
    val tokenizer = new Tokenizer().setInputCol("value").setOutputCol("words")
    val hashingTF = new HashingTF().setInputCol("words").setOutputCol("features")
    val lr = new LogisticRegression().setMaxIter(10).setRegParam(0.001)
    val pipeline = new Pipeline().setStages(Array(tokenizer, hashingTF, lr))

    val model = pipeline.fit(kafkaDF)

    val predictions = model.transform(kafkaDF)

    // Write results back to Kafka
    val query = predictions.selectExpr("CAST(key AS STRING)", "CAST(prediction AS STRING)")
      .writeStream
      .format("kafka")
      .option("kafka.bootstrap.servers", "kafka-1:9092,kafka-2:9095")
      .option("topic", "anomaly-results")
      .trigger(Trigger.ProcessingTime("10 seconds"))
      .start()

    query.awaitTermination()
  }
}
