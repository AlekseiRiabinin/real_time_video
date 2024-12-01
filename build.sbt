lazy val commonSettings = Seq(
    scalaVersion := "3.5.2",
    version := "0.1.0"    
)

lazy val root = (project in file("."))
    .aggregate(kafkaService, flinkJob, sparkMLJob, hdfsClient)
    .settings(commonSettings)

lazy val kafkaService = (project in file("kafka-service"))
    .settings(commonSettings, libraryDependencies += "org.apache.kafka" %% "kafka" % "2.8.0")

lazy val flinkJob = (project in file("flink-job"))
    .settings(commonSettings, libraryDependencies += "org.apache.flink" %% "flink-streaming-scala" % "1.14.0")

lazy val sparkMLJob = (project in file("spark-ml-job"))
    .settings(commonSettings, libraryDependencies += "org.apache.spark" %% "spark-mllib" % "3.1.2")

lazy val hdfsClient = (project in file("hdfs-client"))
    .settings(commonSettings, libraryDependencies += "org.apache.hadoop" %% "hadoop-client" % "3.3.1")