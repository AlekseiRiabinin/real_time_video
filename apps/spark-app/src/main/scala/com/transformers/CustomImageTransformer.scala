package com.transformers

import org.apache.spark.ml.Transformer
import org.apache.spark.ml.param.{Param, ParamMap}
import org.apache.spark.ml.util.Identifiable
import org.apache.spark.sql.{DataFrame, Dataset}
import org.apache.spark.sql.types.StructType
import org.opencv.core.{Mat, Size}
import org.opencv.imgproc.Imgproc
import org.apache.spark.sql.functions.udf
import org.apache.spark.sql.functions.col


class CustomImageTransformer(override val uid: String) extends Transformer {
  def this() = this(Identifiable.randomUID("customImageTransformer"))

  val inputCol = new Param[String](this, "inputCol", "Input column name")
  val outputCol = new Param[String](this, "outputCol", "Output column name")

  def setInputCol(value: String): this.type = set(inputCol, value)
  def setOutputCol(value: String): this.type = set(outputCol, value)

  override def transform(dataset: Dataset[_]): DataFrame = {
    // Define a UDF to process the video frames
    val processFrameUDF = udf { bytes: Array[Byte] =>
      try {
        // Decode the video frame from bytes
        val mat = new Mat()
        mat.put(0, 0, bytes)

        // Convert the frame to grayscale
        val grayMat = new Mat()
        Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_BGR2GRAY)

        // Resize the frame from 256x256 to 64x64
        val resizedMat = new Mat()
        Imgproc.resize(grayMat, resizedMat, new Size(64, 64))

        // Convert the resized frame back to bytes
        val size = resizedMat.total().toInt * resizedMat.channels()
        val byteArray = new Array[Byte](size)
        resizedMat.get(0, 0, byteArray)

        byteArray
      } catch {
        case e: Exception =>
          // Log the error and return null for failed frames
          println(s"Error processing frame: ${e.getMessage}")
          null
      }
    }

    // Apply the UDF to the input column and create a new column with processed frames
    dataset.withColumn($(outputCol), processFrameUDF(col($(inputCol))))
  }

  override def copy(extra: ParamMap): Transformer = defaultCopy(extra)

  override def transformSchema(schema: StructType): StructType = schema
}
