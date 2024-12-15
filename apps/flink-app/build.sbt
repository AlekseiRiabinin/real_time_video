import sbtassembly.AssemblyPlugin.autoImport._


lazy val root = (project in file("."))
  .settings(
    name := "FlinkJob",
    scalaVersion := "2.12.20",
    libraryDependencies ++= Seq(
      "org.apache.flink" %% "flink-streaming-scala" % "1.17.1",
      "org.apache.flink" % "flink-connector-kafka" % "3.0.0-1.17",
      "org.apache.kafka" % "kafka-clients" % "3.7.0"
    ),
    resolvers ++= Seq(
      "Apache Repository" at "https://repository.apache.org/content/repositories/releases/",
      "Maven Central" at "https://repo1.maven.org/maven2/"
    ),
    assembly / assemblyMergeStrategy := {
    case PathList("META-INF", xs @ _*) => MergeStrategy.discard
    case PathList("version.conf") => MergeStrategy.concat
    case x => MergeStrategy.defaultMergeStrategy(x)
    },
    Compile / run / mainClass := Some("FlinkJob")
  )