import sbtassembly.AssemblyPlugin.autoImport._


lazy val root = (project in file("."))
  .settings(
    name := "FlinkJob",
    scalaVersion := "2.12.20",
    libraryDependencies ++= Seq(
      "org.apache.flink" %% "flink-scala" % "1.17.1",
      "org.apache.flink" %% "flink-streaming-scala" % "1.17.1" % "provided",
      "org.apache.flink" % "flink-clients" % "1.17.1",
      "org.apache.flink" % "flink-connector-kafka" % "3.0.0-1.17",
      "org.apache.kafka" % "kafka-clients" % "3.7.0",
      "org.bytedeco" % "opencv" % "4.10.0-1.5.11" exclude("org.bytedeco", "javacpp-presets"),
      "org.bytedeco" % "ffmpeg" % "7.1-1.5.11" exclude("org.bytedeco", "javacpp-presets"),
      "org.bytedeco" % "javacpp" % "1.5.11",
      "org.bytedeco" % "javacv-platform" % "1.5.11",
      "org.bytedeco" % "openblas" % "0.3.23-1.5.9",
      "org.slf4j" % "slf4j-simple" % "1.7.36"
    ),
    resolvers ++= Seq(
      "Apache Repository" at "https://repository.apache.org/content/repositories/releases/",
      "Maven Central" at "https://repo1.maven.org/maven2/",
      "Bytedeco" at "https://repo.bytedeco.org/releases/"
    ),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", xs @ _*) => MergeStrategy.discard
      case PathList("version.conf") => MergeStrategy.concat
      case PathList("org", "opencv", xs @ _*) => MergeStrategy.first
      case PathList("org", "bytedeco", "javacpp", "linux-x86_64", xs @ _*) => MergeStrategy.first
      case x => MergeStrategy.defaultMergeStrategy(x)
    },
    Compile / run / mainClass := Some("FlinkJob"),
    Compile / run / fork := true,
    Compile / run / javaOptions ++= Seq(
      "--add-opens=java.base/java.util=ALL-UNNAMED",
      "--add-opens=java.base/java.lang=ALL-UNNAMED"
    )
  )
