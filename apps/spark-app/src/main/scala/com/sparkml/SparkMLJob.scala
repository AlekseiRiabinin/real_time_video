package com.sparkml

import com.typesafe.config.ConfigFactory
import org.apache.hadoop.security.UserGroupInformation
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import org.apache.spark.ml.PipelineModel
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.hadoop.fs.permission.FsAction
import org.apache.log4j.{Logger, PropertyConfigurator}
import scala.collection.JavaConverters._
import com.transformers.CustomImageTransformer


object SparkMLJob {
  private val logger = Logger.getLogger(getClass.getName)

  def main(args: Array[String]): Unit = {
    // Load Log4j configuration
    PropertyConfigurator.configure(getClass.getResource("/log4j.properties"))
    logger.info("Log4j configuration loaded successfully")

    // Log HADOOP_USER_NAME
    logger.info(s"HADOOP_USER_NAME: ${sys.env.getOrElse("HADOOP_USER_NAME", "Not set")}")

    // Load configuration from application.conf
    val config = ConfigFactory.load()
    logger.info("Configuration loaded successfully")

    // Initialize Spark session
    val spark = SparkSession.builder
      .appName(config.getString("spark.appName"))
      .master(sys.env.getOrElse("SPARK_MASTER_URL", config.getString("spark.master")))
      .getOrCreate()
    logger.info("Spark session initialized successfully")

    // Verify and set user information
    val hadoopUser = config.getString("spark.hdfs.hadoopUser")
    logger.info(s"Setting Hadoop user to: $hadoopUser")
    UserGroupInformation.setLoginUser(UserGroupInformation.createRemoteUser(hadoopUser))
    val currentUser = UserGroupInformation.getCurrentUser
    logger.info(s"Current Hadoop user: ${currentUser.getUserName}")
    logger.info(s"User's groups: ${currentUser.getGroups.asScala.mkString(", ")}")
    logger.info(s"User's authentication method: ${currentUser.getAuthenticationMethod}")

    // Explicitly set Hadoop configuration
    val hadoopConf = spark.sparkContext.hadoopConfiguration
    hadoopConf.set("dfs.client.use.datanode.hostname", "true")
    hadoopConf.set("dfs.namenode.rpc-address", "namenode:8020")
    hadoopConf.set("dfs.namenode.http-address", "namenode:9870")

    logger.info("Hadoop configuration set explicitly")
    logger.info(s"Hadoop configuration: fs.defaultFS=${hadoopConf.get("fs.defaultFS")}")
    logger.info(s"Hadoop configuration: hadoop.security.authentication=${hadoopConf.get("hadoop.security.authentication")}")
    logger.info(s"Hadoop configuration: hadoop.security.authorization=${hadoopConf.get("hadoop.security.authorization")}")

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
      var retries = 0
      val maxRetries = 10
      while (!fs.exists(new Path("/")) && retries < maxRetries) {
        logger.info(s"Waiting for HDFS to be ready (attempt ${retries + 1}/${maxRetries})...")
        Thread.sleep(5000)
        retries += 1
      }
      if (retries >= maxRetries) {
        throw new Exception("HDFS not ready after maximum retries")
      }
      logger.info("HDFS FileSystem initialized successfully")

      // Check permissions for input, processed, and model paths
      checkAndLogPermissions(fs, new Path(inputPath), "Input")
      checkAndLogPermissions(fs, new Path(processedPath), "Processed")
      checkAndLogPermissions(fs, new Path(hdfsModelPath), "Model")

      // Copy model from HDFS to local filesystem
      fs.copyToLocalFile(new Path(hdfsModelPath), new Path(localModelPath))
      logger.info(s"Model copied from HDFS ($hdfsModelPath) to local filesystem ($localModelPath)")
    } catch {
      case e: Exception =>
        logger.error("Failed to initialize HDFS FileSystem or copy model", e)
        throw e // Re-throw the exception to stop the job
    }

    // Read video files from HDFS
    logger.info(s"Starting to read video files from HDFS path: $inputPath")
    val videoDF = spark.read.format("binaryFile")
      .option("pathGlobFilter", "*.mp4")
      .load(inputPath)
    logger.info(s"Video files loaded successfully. Count: ${videoDF.count()}")

    // Use the custom image transformer
    val imageTransformer = new CustomImageTransformer()
      .setInputCol("value")
      .setOutputCol("processed_image")
    logger.info("CustomImageTransformer initialized successfully")

    // Transform the video frames
    val processedDF = imageTransformer.transform(videoDF)
    logger.info(s"DataFrame transformed successfully. Count: ${processedDF.count()}")

    // Use a pre-trained model for inference
    val model = PipelineModel.load(localModelPath)
    logger.info("Model loaded successfully")
    val predictions = model.transform(processedDF)
    logger.info(s"Predictions generated successfully. Count: ${predictions.count()}")

    // Write results back to HDFS
    logger.info(s"Starting to write results to HDFS path: $processedPath")
    predictions.write
      .format("parquet")
      .mode("overwrite")
      .option("path", processedPath)
      .save()
    logger.info("Results written to HDFS successfully")

    spark.stop()
  }

  private def checkAndLogPermissions(fs: FileSystem, path: Path, pathType: String): Unit = {
    if (fs.exists(path)) {
      val status = fs.getFileStatus(path)
      val perm = status.getPermission
      logger.info(s"$pathType path ($path) permissions: ${perm.toString}")

      // Check if the current user has read and write permissions
      val currentUser = UserGroupInformation.getCurrentUser.getUserName
      if (!perm.getUserAction.implies(FsAction.READ_WRITE)) {
        logger.warn(s"$pathType path ($path) does not have read-write permissions for user $currentUser")
      }
    } else {
      logger.warn(s"$pathType path ($path) does not exist")
    }
  }
}
