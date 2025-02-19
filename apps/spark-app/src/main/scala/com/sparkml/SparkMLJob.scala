package com.sparkml

import com.typesafe.config.ConfigFactory
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import org.apache.spark.ml.PipelineModel
import org.apache.hadoop.fs.{FileSystem, Path}
import com.transformers.CustomImageTransformer


object SparkMLJob {
  def main(args: Array[String]): Unit = {
    // Load configuration from application.conf
    val config = ConfigFactory.load()

    // Initialize Spark session
    val spark = SparkSession.builder
      .appName(config.getString("spark.appName"))
      .master(config.getString("spark.master"))
      .config("spark.hadoop.fs.defaultFS", config.getString("spark.hdfs.defaultFS"))
      .config("spark.sql.streaming.checkpointLocation", config.getString("spark.hdfs.checkpointLocation"))
      .getOrCreate()

    // Read Kafka configurations
    val kafkaBootstrapServers = config.getString("spark.kafka.bootstrapServers")
    val inputTopic = config.getString("spark.kafka.inputTopic")
    val outputTopic = config.getString("spark.kafka.outputTopic")

    // Read HDFS configurations
    val processedPath = config.getString("spark.hdfs.processedPath")
    val hdfsModelPath = config.getString("spark.hdfs.modelPath")
    val localModelPath = config.getString("spark.local.modelPath")

    // Copy model from HDFS to local filesystem
    val fs = FileSystem.get(spark.sparkContext.hadoopConfiguration)
    fs.copyToLocalFile(new Path(hdfsModelPath), new Path(localModelPath))

    // Read video frames from Kafka
    val kafkaDF = spark.readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", kafkaBootstrapServers)
      .option("subscribe", inputTopic)
      .load()

    // Process video frames and write to HDFS
    kafkaDF.writeStream
      .format("parquet")
      .option("path", processedPath)
      .option("checkpointLocation", config.getString("spark.hdfs.checkpointLocation"))
      .start()

    // Use the custom image transformer
    val imageTransformer = new CustomImageTransformer()
      .setInputCol("value")
      .setOutputCol("processed_image")

    val processedDF = imageTransformer.transform(kafkaDF)

    // Use a pre-trained model for inference
    val model = PipelineModel.load(localModelPath)
    val predictions = model.transform(processedDF)

    // Write results back to Kafka
    val query = predictions.selectExpr("CAST(key AS STRING)", "CAST(prediction AS STRING)")
      .writeStream
      .format("kafka")
      .option("kafka.bootstrap.servers", kafkaBootstrapServers)
      .option("topic", outputTopic)
      .start()

    query.awaitTermination()
  }
}
