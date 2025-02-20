import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions.{col, udf}
import org.apache.spark.ml.linalg.Vectors
import org.bytedeco.opencv.global.opencv_imgcodecs._
import org.bytedeco.opencv.global.opencv_imgproc._
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_core.Size
import org.apache.spark.ml.feature.{StringIndexer, VectorAssembler}
import org.apache.spark.ml.classification.{
  RandomForestClassifier,
  LogisticRegression,
  DecisionTreeClassifier
}
import org.apache.spark.ml.{Pipeline, PipelineModel}
import org.apache.spark.ml.tuning.{CrossValidator, ParamGridBuilder}
import org.apache.spark.ml.evaluation.MulticlassClassificationEvaluator
import com.typesafe.config.ConfigFactory
import java.io.File


object MarsImageClassification {
  def main(args: Array[String]): Unit = {
    // Load configuration
    val config = ConfigFactory.load("application.conf")
    val appName = config.getString("spark.appName")
    val master = config.getString("spark.master")
    val csvPath = config.getString("spark.csvPath")
    val modelSavePath = config.getString("spark.modelSavePath")

    // Initialize SparkSession
    val spark = SparkSession.builder
      .appName(appName)
      .master(master)
      .config("spark.driver.memory", "8g")  // Increase driver memory
      .config("spark.executor.memory", "8g")  // Increase executor memory
      .config("spark.memory.fraction", "0.8")  // Increase memory fraction
      .config("spark.memory.storageFraction", "0.5")  // Adjust storage fraction
      .config("spark.network.timeout", "600s")  // Increase network timeout
      .config("spark.executor.heartbeatInterval", "60s")  // Increase heartbeat interval
      .config("spark.executor.extraJavaOptions", "-XX:+UseG1GC")  // Use G1 garbage collector
      .config("spark.driver.extraJavaOptions", "-XX:+UseG1GC")  // Use G1 garbage collector
      // .config("spark.driver.maxResultSize", "2g")
      .getOrCreate()

    import spark.implicits._

    // Load the dataset
    val data = spark.read.format("csv").option("header", "true").load(csvPath)

    // Define the UDF to process images
    val processImage = udf((path: String) => {
      try {
        val img: Mat = imread(path)
        if (img.empty()) {
          null
        } else {
          val resized = new Mat()
          resize(img, resized, new Size(64, 64)) // Resize to smaller dimensions
          val size = resized.total().toInt * resized.channels()
          val byteArray = new Array[Byte](size)
          resized.data().get(byteArray)
          Vectors.dense(byteArray.map(_.toDouble))
        }
      } catch {
        case e: Exception =>
          println(s"Error processing image at path: $path")
          e.printStackTrace()
          null
      }
        })

    // Create the imageDF with error handling
    val imageDF = data.withColumn("features", processImage(col("image_path")))
      .filter(col("features").isNotNull)  // Remove rows where image processing failed

    // Print some statistics about the DataFrame
    println(s"Total rows: ${imageDF.count()}")
    println(s"Rows with features: ${imageDF.filter(col("features").isNotNull).count()}")

    // Check if we have enough data to proceed
    if (imageDF.count() == 0) {
      println("No valid images found. Exiting.")
      spark.stop()
      System.exit(1)
    }

    // Index the labels once and reuse the indexed DataFrame
    val indexer = new StringIndexer().setInputCol("label").setOutputCol("indexedLabel")
    val indexedDF = indexer.fit(imageDF).transform(imageDF)

    // Repartition the dataset
    // val repartitionedDF = indexedDF.repartition(200)  // Adjust the number of partitions
    // val Array(trainingData, testData) = repartitionedDF.randomSplit(Array(0.8, 0.2))

    // Split the data into training and test sets
    // val Array(trainingData, testData) = indexedDF.randomSplit(Array(0.8, 0.2))
    val smallerDataset = indexedDF.sample(0.01)  // Use 1% of the data
    val Array(trainingData, testData) = smallerDataset.randomSplit(Array(0.8, 0.2))

    // Define the classifiers
    val rf = new RandomForestClassifier()
      .setLabelCol("indexedLabel")
      .setFeaturesCol("features")
      .setMaxDepth(5)  // Limit tree depth
      .setNumTrees(10)  // Reduce number of trees
      .setMaxBins(16)   // Reduce number of bins

    val lr = new LogisticRegression()
      .setLabelCol("indexedLabel")
      .setFeaturesCol("features")
      .setFamily("multinomial")

    val dt = new DecisionTreeClassifier()
      .setLabelCol("indexedLabel")
      .setFeaturesCol("features")

    // Create pipelines for each classifier (without the indexer)
    val rfPipeline = new Pipeline().setStages(Array(rf))
    val lrPipeline = new Pipeline().setStages(Array(lr))
    val dtPipeline = new Pipeline().setStages(Array(dt))

    // Define a parameter grid for cross-validation
    val paramGrid = new ParamGridBuilder()
      .addGrid(rf.numTrees, Array(50, 100))
      .addGrid(lr.regParam, Array(0.1, 0.01))
      .addGrid(dt.maxDepth, Array(5, 10))
      .build()

    // Define the evaluator
    val evaluator = new MulticlassClassificationEvaluator()
      .setLabelCol("indexedLabel")
      .setPredictionCol("prediction")
      .setMetricName("f1")

    // Define the cross-validator for each pipeline
    val rfCv = new CrossValidator()
      .setEstimator(rfPipeline)
      .setEvaluator(evaluator)
      .setEstimatorParamMaps(paramGrid)
      .setNumFolds(3)

    val lrCv = new CrossValidator()
      .setEstimator(lrPipeline)
      .setEvaluator(evaluator)
      .setEstimatorParamMaps(paramGrid)
      .setNumFolds(3)

    val dtCv = new CrossValidator()
      .setEstimator(dtPipeline)
      .setEvaluator(evaluator)
      .setEstimatorParamMaps(paramGrid)
      .setNumFolds(3)

    // Train the models and select the best one
    println("Training Random Forest model...")
    val rfModel = try {
      rfCv.fit(trainingData)
    } catch {
      case e: Exception =>
        println(s"Error during Random Forest training: ${e.getMessage}")
        e.printStackTrace()
        throw e
    }

    println("Training Logistic Regression model...")
    val lrModel = try {
      lrCv.fit(trainingData)
    } catch {
      case e: Exception =>
        println(s"Error during Logistic Regression training: ${e.getMessage}")
        e.printStackTrace()
        throw e
    }

    println("Training Decision Tree model...")
    val dtModel = try {
      dtCv.fit(trainingData)
    } catch {
      case e: Exception =>
        println(s"Error during Decision Tree training: ${e.getMessage}")
        e.printStackTrace()
        throw e
    }

    // Evaluate the models on the test data
    println("Evaluating models...")    
    val rfPredictions = rfModel.transform(testData)
    val lrPredictions = lrModel.transform(testData)
    val dtPredictions = dtModel.transform(testData)

    val rfF1Score = evaluator.evaluate(rfPredictions)
    val lrF1Score = evaluator.evaluate(lrPredictions)
    val dtF1Score = evaluator.evaluate(dtPredictions)

    println(s"Random Forest F1 score = $rfF1Score")
    println(s"Logistic Regression F1 score = $lrF1Score")
    println(s"Decision Tree F1 score = $dtF1Score")

    // Select the best model based on F1 score
    val bestModel = if (rfF1Score >= lrF1Score && rfF1Score >= dtF1Score) {
      println("Best model: Random Forest")
      rfModel.bestModel
    } else if (lrF1Score >= rfF1Score && lrF1Score >= dtF1Score) {
      println("Best model: Logistic Regression")
      lrModel.bestModel
    } else {
      println("Best model: Decision Tree")
      dtModel.bestModel
    }

    // Save the best model
    println(s"Saving best model to $modelSavePath")
    bestModel.asInstanceOf[PipelineModel].write.overwrite().save(modelSavePath)

    spark.stop()
  }
}

// sbt marsImageClassification/assembly

// spark-submit \
//   --class com.sparkml.MarsImageClassification \
//   --master local[*] \
//   apps/marsImageClassification/assembly/target/scala-2.12/mars-image-classification-assembly.jar
