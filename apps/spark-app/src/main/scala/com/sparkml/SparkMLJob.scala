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
      // Use environment variable if set, otherwise use application.conf
      .master(sys.env.getOrElse("SPARK_MASTER_URL", config.getString("spark.master")))
      // Other configurations will be read from spark-defaults.conf
      .getOrCreate()
    logger.info("Spark session initialized successfully")

    // Explicitly set Hadoop configuration
    val hadoopConf = spark.sparkContext.hadoopConfiguration
    // Use environment variable if set, otherwise use application.conf
    val hdfsDefaultFS = sys.env.getOrElse("SPARK_HADOOP_FS_DEFAULTFS", config.getString("spark.hdfs.defaultFS"))
    hadoopConf.set("fs.defaultFS", hdfsDefaultFS)
    
    // Set Hadoop security authentication with a fallback value
    val hdfsAuthentication = if (config.hasPath("spark.hdfs.authentication")) {
      config.getString("spark.hdfs.authentication")
    } else {
      "simple" // Fallback value
    }
    hadoopConf.set("hadoop.security.authentication", hdfsAuthentication)

    // Set Hadoop security authorization with a fallback value
    val hdfsAuthorization = if (config.hasPath("spark.hdfs.authorization")) {
      config.getString("spark.hdfs.authorization")
    } else {
      "false" // Fallback value
    }
    hadoopConf.set("hadoop.security.authorization", hdfsAuthorization)

    logger.info("Hadoop configuration set explicitly")

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
    // Use environment variable if set, otherwise use application.conf
    val localModelPath = sys.env.getOrElse("MODEL_PATH", config.getString("spark.local.modelPath"))
    logger.info(
      s"""HDFS configurations:
         |processedPath=$processedPath,
         |hdfsModelPath=$hdfsModelPath,
         |localModelPath=$localModelPath""".stripMargin
    )

    // Initialize HDFS FileSystem
    logger.info("Initializing HDFS FileSystem...")
    try {
      val fs = FileSystem.get(hadoopConf)
      logger.info("HDFS FileSystem initialized successfully")

      // Copy model from HDFS to local filesystem
      fs.copyToLocalFile(new Path(hdfsModelPath), new Path(localModelPath))
      logger.info(s"Model copied from HDFS ($hdfsModelPath) to local filesystem ($localModelPath)")
    } catch {
      case e: Exception => logger.error("Failed to initialize HDFS FileSystem or copy model", e)
    }

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
