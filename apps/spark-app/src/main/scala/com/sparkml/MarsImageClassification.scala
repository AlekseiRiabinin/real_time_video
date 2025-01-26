package com.sparkml

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions.{col, udf}
import org.apache.spark.ml.linalg.Vectors
import org.bytedeco.opencv.global.opencv_imgcodecs._
import org.bytedeco.opencv.opencv_core.Mat
import org.apache.spark.ml.feature.{StringIndexer, VectorAssembler}
import org.apache.spark.ml.classification.{
  RandomForestClassifier,
  LogisticRegression,
  DecisionTreeClassifier
}
import org.apache.spark.ml.{Pipeline, PipelineModel}
import org.apache.spark.ml.tuning.{CrossValidator, ParamGridBuilder}
import org.apache.spark.ml.evaluation.MulticlassClassificationEvaluator


object MarsImageClassification {
  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder
      .appName("MarsImageClassification")
      .getOrCreate()

    import spark.implicits._

    // Load the dataset
    val data = spark.read.format("csv").option("header", "true").load("mars_images.csv")

    // Define the UDF to process images
    val processImage = udf((path: String) => {
      try {
        val img: Mat = imread(path)
        if (img.empty()) {
          null // Return null if the image couldn't be read
        } else {
          val size = img.total().toInt * img.channels()
          val byteArray = new Array[Byte](size)
          img.data().get(byteArray)
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

    // Index the labels
    val indexer = new StringIndexer().setInputCol("label").setOutputCol("indexedLabel")
    val indexedDF = indexer.fit(imageDF).transform(imageDF)

    // Split the data into training and test sets
    val Array(trainingData, testData) = indexedDF.randomSplit(Array(0.8, 0.2))

    // Define the classifiers
    val rf = new RandomForestClassifier()
      .setLabelCol("indexedLabel")
      .setFeaturesCol("features")

    val lr = new LogisticRegression()
      .setLabelCol("indexedLabel")
      .setFeaturesCol("features")
      .setFamily("multinomial")

    val dt = new DecisionTreeClassifier()
      .setLabelCol("indexedLabel")
      .setFeaturesCol("features")

    // Create pipelines for each classifier
    val rfPipeline = new Pipeline().setStages(Array(indexer, rf))
    val lrPipeline = new Pipeline().setStages(Array(indexer, lr))
    val dtPipeline = new Pipeline().setStages(Array(indexer, dt))
    
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
    val rfModel = rfCv.fit(trainingData)
    val lrModel = lrCv.fit(trainingData)
    val dtModel = dtCv.fit(trainingData)
    
    // Evaluate the models on the test data
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
      rfModel.bestModel
    } else if (lrF1Score >= rfF1Score && lrF1Score >= dtF1Score) {
      lrModel.bestModel
    } else {
      dtModel.bestModel
    }

    // Save the best model
    bestModel.asInstanceOf[PipelineModel].write.overwrite().save("models/bestModel")

    spark.stop()
  }
}
