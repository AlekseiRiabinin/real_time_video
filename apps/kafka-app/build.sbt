import sbtassembly.MergeStrategy

lazy val root = (project in file("."))
  .settings(
    name := "KafkaService",
    scalaVersion := "3.3.4",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor" % "2.8.8",
      "com.typesafe.akka" %% "akka-stream" % "2.8.8",
      "com.typesafe.akka" %% "akka-stream-kafka" % "4.0.2",
      "org.apache.kafka" % "kafka-clients" % "3.7.0",
      "ch.qos.logback" % "logback-classic" % "1.2.11"
    ),
    resolvers ++= Seq(
        "Apache Repository" at "https://repository.apache.org/content/repositories/releases/",
        "Maven Central" at "https://repo1.maven.org/maven2/",
        "Akka Repository" at "https://repo.akka.io/releases/"
    ),
    assemblyMergeStrategy in assembly := {
        case "version.conf" => MergeStrategy.first
        case x => MergeStrategy.defaultMergeStrategy(x)
    }
  )