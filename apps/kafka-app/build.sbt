import sbtassembly.AssemblyPlugin.autoImport._


lazy val root = (project in file("."))
  .settings(
    name := "KafkaService",
    scalaVersion := "3.3.4",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor" % "2.8.8",
      "com.typesafe.akka" %% "akka-stream" % "2.8.8",
      "com.typesafe.akka" %% "akka-stream-kafka" % "4.0.2",
      "org.apache.kafka" % "kafka-clients" % "3.7.0",
      "org.bytedeco" % "opencv" % "4.10.0-1.5.11" exclude("org.bytedeco", "javacpp-presets"),
      "org.bytedeco" % "ffmpeg" % "7.1-1.5.11" exclude("org.bytedeco", "javacpp-presets"),
      "ch.qos.logback" % "logback-classic" % "1.2.11"
    ),
    resolvers ++= Seq(
      "Apache Repository" at "https://repository.apache.org/content/repositories/releases/",
      "Maven Central" at "https://repo1.maven.org/maven2/",
      "Bytedeco" at "https://repo.bytedeco.org/releases/",
      "Akka Repository" at "https://repo.akka.io/releases/"
    ),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", xs @ _*) => MergeStrategy.discard
      case PathList("version.conf") => MergeStrategy.concat
      case PathList("org", "opencv", xs @ _*) => MergeStrategy.first
      case x => MergeStrategy.defaultMergeStrategy(x)
    },
    Compile / run / mainClass := Some("KafkaService")
  )
