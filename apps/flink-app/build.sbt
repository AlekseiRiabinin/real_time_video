import sbtassembly.AssemblyPlugin.autoImport._


lazy val root = (project in file("."))
  .settings(
    name := "FlinkJob",
    scalaVersion := "2.12.20",
    libraryDependencies ++= Seq(
      "org.apache.flink" %% "flink-scala" % "1.17.1",
      "org.apache.flink" %% "flink-streaming-scala" % "1.17.1" % "provided",
      "org.apache.flink" % "flink-clients" % "1.17.1",
      // "org.apache.flink" %% "flink-runtime-web" % "1.17.1",
      "org.apache.flink" % "flink-connector-kafka" % "3.0.0-1.17",
      "org.apache.kafka" % "kafka-clients" % "3.7.0",
      "org.slf4j" % "slf4j-simple" % "1.7.36"
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
    Compile / run / mainClass := Some("FlinkJob"),
    Compile / run / fork := true,
    Compile / run / javaOptions ++= Seq(
      "--add-opens=java.base/java.util=ALL-UNNAMED",
      "--add-opens=java.base/java.lang=ALL-UNNAMED"
    )
  )
