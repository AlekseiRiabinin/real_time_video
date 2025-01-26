package com.sparkml

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions.{col, udf}
import org.apache.spark.ml.linalg.Vectors
import org.bytedeco.opencv.global.opencv_imgcodecs._
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.javacpp.BytePointer
import org.apache.spark.ml.feature.StringIndexer
import org.apache.spark.ml.classification.RandomForestClassifier


object MarsImageClassification {
  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder
      .appName("MarsImageClassification")
      .getOrCreate()

    import spark.implicits._

    // Load the dataset
    val data = spark.read.format("csv").option("header", "true").load("path/to/mars_images.csv")

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

    // Define the model
    val rf = new RandomForestClassifier()
      .setLabelCol("indexedLabel")
      .setFeaturesCol("features")
      .setNumTrees(100)

    // Train the model
    val model = rf.fit(trainingData)

    // Save the model
    model.save("path/to/save/model")

    spark.stop()
  }
}
