package com.sparkml

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import org.apache.spark.ml.PipelineModel
import com.transformers.CustomImageTransformer


object SparkMLJob {
  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder
      .appName("VideoProcessingJob")
      .getOrCreate()

    // Read video frames from Kafka
    val kafkaDF = spark.readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", "kafka-1:9092,kafka-2:9095")
      .option("subscribe", "video-stream")
      .load()

    // Use the custom image transformer
    val imageTransformer = new CustomImageTransformer()
      .setInputCol("value")
      .setOutputCol("processed_image")

    val processedDF = imageTransformer.transform(kafkaDF)

    // Use a pre-trained model for inference
    val model = PipelineModel.load("path/to/model")
    val predictions = model.transform(processedDF)

    // Write results back to Kafka
    val query = predictions.selectExpr("CAST(key AS STRING)", "CAST(prediction AS STRING)")
      .writeStream
      .format("kafka")
      .option("kafka.bootstrap.servers", "kafka-1:9092,kafka-2:9095")
      .option("topic", "anomaly-results")
      .start()

    query.awaitTermination()
  }
}
