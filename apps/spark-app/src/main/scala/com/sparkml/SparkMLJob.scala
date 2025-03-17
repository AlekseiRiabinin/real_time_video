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
      .master(sys.env.getOrElse("SPARK_MASTER_URL", config.getString("spark.master")))
      .getOrCreate()
    logger.info("Spark session initialized successfully")

    // Explicitly set Hadoop configuration
    val hadoopConf = spark.sparkContext.hadoopConfiguration
    val hdfsDefaultFS = sys.env.getOrElse(
      "SPARK_HADOOP_FS_DEFAULTFS",
      config.getString("spark.hdfs.defaultFS")
    )
    hadoopConf.set("fs.defaultFS", hdfsDefaultFS)
    hadoopConf.set("dfs.client.use.datanode.hostname", "true")
    hadoopConf.set("dfs.namenode.rpc-address", "namenode:8020")
    hadoopConf.set("dfs.namenode.http-address", "namenode:50070")
    
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

    // Read HDFS configurations
    val inputPath = config.getString("spark.hdfs.inputPath")
    val processedPath = config.getString("spark.hdfs.processedPath")
    val hdfsModelPath = config.getString("spark.hdfs.modelPath")
    val localModelPath = sys.env.getOrElse("MODEL_PATH", config.getString("spark.local.modelPath"))
    logger.info(
      s"""HDFS configurations:
         |inputPath=$inputPath,
         |processedPath=$processedPath,
         |hdfsModelPath=$hdfsModelPath,
         |localModelPath=$localModelPath""".stripMargin
    )

    // Initialize HDFS FileSystem
    logger.info("Initializing HDFS FileSystem...")
    try {
      val fs = FileSystem.get(hadoopConf)
      while (!fs.exists(new Path("/"))) {
        Thread.sleep(5000) // Wait for HDFS to be ready
      }
      logger.info("HDFS FileSystem initialized successfully")

      // Copy model from HDFS to local filesystem
      fs.copyToLocalFile(new Path(hdfsModelPath), new Path(localModelPath))
      logger.info(s"Model copied from HDFS ($hdfsModelPath) to local filesystem ($localModelPath)")
    } catch {
      case e: Exception => logger.error("Failed to initialize HDFS FileSystem or copy model", e)
    }

    // Read video files from HDFS
    logger.info(s"Reading video files from HDFS path: $inputPath")
    val videoDF = spark.read.format("binaryFile")
      .option("pathGlobFilter", "*.mp4")
      .load(inputPath)
    logger.info("Video files loaded successfully")

    // Use the custom image transformer
    val imageTransformer = new CustomImageTransformer()
      .setInputCol("value")
      .setOutputCol("processed_image")
    logger.info("CustomImageTransformer initialized successfully")

    // Transform the video frames
    val processedDF = imageTransformer.transform(videoDF)
    logger.info("DataFrame transformed successfully")

    // Use a pre-trained model for inference
    val model = PipelineModel.load(localModelPath)
    logger.info("Model loaded successfully")
    val predictions = model.transform(processedDF)
    logger.info("Predictions generated successfully")

    // Write results back to HDFS
    logger.info(s"Writing results to HDFS path: $processedPath")
    predictions.write
      .format("parquet")
      .mode("overwrite")
      .option("path", processedPath)
      .save()
    logger.info("Results written to HDFS successfully")

    spark.stop()
  }
}
