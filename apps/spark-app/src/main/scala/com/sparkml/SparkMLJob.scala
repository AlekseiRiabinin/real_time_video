package com.sparkml

import com.typesafe.config.ConfigFactory
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import org.apache.spark.ml.PipelineModel
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.log4j.{Logger, PropertyConfigurator}
import com.transformers.CustomImageTransformer


object SparkMLJob {
  private val logger = Logger.getLogger(getClass.getName)

  def main(args: Array[String]): Unit = {
    // Load Log4j configuration
    PropertyConfigurator.configure(getClass.getResource("/log4j.properties"))
    logger.info("Log4j configuration loaded successfully")

    // Load configuration from application.conf
    val config = ConfigFactory.load()
    logger.info("Configuration loaded successfully")

    // Initialize Spark session
    val spark = SparkSession.builder
      .appName(config.getString("spark.appName"))
      .master(config.getString("spark.master"))
      .config("spark.hadoop.fs.defaultFS", config.getString("spark.hdfs.defaultFS"))
      .config("spark.sql.streaming.checkpointLocation", config.getString("spark.hdfs.checkpointLocation"))
      .config("spark.jars.ivy", config.getString("spark.ivy"))
      .config("spark.logConf", config.getString("spark.logs.logConf"))
      .config("spark.logLevel", config.getString("spark.logs.logLevel"))
      .getOrCreate()
    logger.info("Spark session initialized successfully")

    // Read Kafka configurations
    val kafkaBootstrapServers = config.getString("spark.kafka.bootstrapServers")
    val inputTopic = config.getString("spark.kafka.inputTopic")
    val outputTopic = config.getString("spark.kafka.outputTopic")
    logger.info(
      s"""Kafka configurations:
        |bootstrapServers=$kafkaBootstrapServers,
        |inputTopic=$inputTopic,
        |outputTopic=$outputTopic""".stripMargin
    )

    // Read HDFS configurations
    val processedPath = config.getString("spark.hdfs.processedPath")
    val hdfsModelPath = config.getString("spark.hdfs.modelPath")
    val localModelPath = config.getString("spark.local.modelPath")
    logger.info(
      s"""HDFS configurations:
        |processedPath=$processedPath,
        |hdfsModelPath=$hdfsModelPath,
        |localModelPath=$localModelPath""".stripMargin
    )

    // Copy model from HDFS to local filesystem
    val fs = FileSystem.get(spark.sparkContext.hadoopConfiguration)
    logger.info("HDFS FileSystem initialized successfully")
    fs.copyToLocalFile(new Path(hdfsModelPath), new Path(localModelPath))
    logger.info(s"Model copied from HDFS ($hdfsModelPath) to local filesystem ($localModelPath)")

    // Read video frames from Kafka
    val kafkaDF = spark.readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", kafkaBootstrapServers)
      .option("subscribe", inputTopic)
      .load()
    logger.info("Kafka DataFrame created successfully")

    // Process video frames and write to HDFS
    kafkaDF.writeStream
      .format("parquet")
      .option("path", processedPath)
      .option("checkpointLocation", config.getString("spark.hdfs.checkpointLocation"))
      .start()
    logger.info("Streaming query started successfully")

    // Use the custom image transformer
    val imageTransformer = new CustomImageTransformer()
      .setInputCol("value")
      .setOutputCol("processed_image")
    logger.info("CustomImageTransformer initialized successfully")

    val processedDF = imageTransformer.transform(kafkaDF)
    logger.info("DataFrame transformed successfully")

    // Use a pre-trained model for inference
    val model = PipelineModel.load(localModelPath)
    logger.info("Model loaded successfully")
    val predictions = model.transform(processedDF)
    logger.info("Predictions generated successfully")

    // Write results back to Kafka
    val query = predictions.selectExpr("CAST(key AS STRING)", "CAST(prediction AS STRING)")
      .writeStream
      .format("kafka")
      .option("kafka.bootstrap.servers", kafkaBootstrapServers)
      .option("topic", outputTopic)
      .start()
    logger.info("Kafka streaming query started successfully")

    query.awaitTermination()
  }
}

// spark-submit --class com.sparkml.SparkMLJob \
// --master local[*] target/scala-2.12/SparkMLJob-assembly-0.1.0-SNAPSHOT.jar

// docker run -it --rm \
//   -v $(pwd)/target/scala-2.12/SparkMLJob-assembly-0.1.0-SNAPSHOT.jar:/opt/spark-app/spark-job.jar \
//   -v $(pwd)/models/saved_model:/opt/spark-apps/models/saved_model \
//   -e SPARK_MASTER_URL=local[*] \
//   -e SPARK_HADOOP_FS_DEFAULTFS=hdfs://172.18.0.2:8020 \  # Use the IP address of namenode
//   -e MODEL_PATH=/opt/spark-apps/models/saved_model \
//   -e IVY_HOME=/opt/bitnami/spark/.ivy2 \
//   bitnami/spark:3.5.4 \
//   /opt/bitnami/spark/bin/spark-submit --class com.sparkml.SparkMLJob /opt/spark-app/spark-job.jar
