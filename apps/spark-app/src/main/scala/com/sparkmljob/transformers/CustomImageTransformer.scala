package com.sparkmljob.transformers

import org.apache.spark.ml.Transformer
import org.apache.spark.ml.param.{Param, ParamMap}
import org.apache.spark.ml.util.Identifiable
import org.apache.spark.sql.{DataFrame, Dataset}
import org.apache.spark.sql.types.StructType
import org.opencv.core.Mat
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
    val toGrayUDF = udf { bytes: Array[Byte] =>
      val mat = new Mat()
      mat.put(0, 0, bytes)
      val grayMat = new Mat()
      Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_BGR2GRAY)
      grayMat
    }

    dataset.withColumn($(outputCol), toGrayUDF(col($(inputCol))))
  }

  override def copy(extra: ParamMap): Transformer = defaultCopy(extra)

  override def transformSchema(schema: StructType): StructType = schema
}
