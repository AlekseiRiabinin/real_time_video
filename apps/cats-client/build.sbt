import sbtassembly.AssemblyPlugin.autoImport._

lazy val root = (project in file("."))
  .settings(
    name := "CatsClient",
    scalaVersion := "3.3.4",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % "3.5.4",
      "org.apache.kafka" % "kafka-clients" % "3.7.0",
      "org.apache.hadoop" % "hadoop-client" % "3.3.6" exclude("javax.activation", "activation") exclude("javax.xml.bind", "jaxb-api"),
      "org.apache.hadoop" % "hadoop-common" % "3.3.6" exclude("javax.activation", "activation") exclude("javax.xml.bind", "jaxb-api"),
      "org.apache.hadoop" % "hadoop-hdfs" % "3.3.6" exclude("javax.activation", "activation") exclude("javax.xml.bind", "jaxb-api"),
      "org.bytedeco" % "opencv" % "4.10.0-1.5.11" exclude("org.bytedeco", "javacpp-presets"),
      "org.bytedeco" % "ffmpeg" % "7.1-1.5.11" exclude("org.bytedeco", "javacpp-presets"),
      "org.bytedeco" % "javacpp" % "1.5.11",
      "org.bytedeco" % "javacv-platform" % "1.5.11",
      "org.bytedeco" % "openblas" % "0.3.23-1.5.9",
      "ch.qos.logback" % "logback-classic" % "1.2.11",
      "io.prometheus" % "simpleclient" % "0.16.0",
      "io.prometheus" % "simpleclient_hotspot" % "0.16.0",
      "io.prometheus" % "simpleclient_httpserver" % "0.16.0",
      "com.typesafe" % "config" % "1.4.3"
    ),
    resolvers ++= Seq(
      "Apache Repository" at "https://repository.apache.org/content/repositories/releases/",
      "Maven Central" at "https://repo1.maven.org/maven2/",
      "Bytedeco" at "https://repo.bytedeco.org/releases/"
    ),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", xs @ _*) => MergeStrategy.discard
      case "module-info.class" => MergeStrategy.discard
      case PathList("version.conf") => MergeStrategy.concat
      case PathList("org", "opencv", xs @ _*) => MergeStrategy.first
      case PathList("org", "bytedeco", "javacpp", "linux-x86_64", xs @ _*) => MergeStrategy.first
      case PathList("javax", "activation", xs @ _*) => MergeStrategy.discard
      case PathList("javax", "xml", "bind", xs @ _*) => MergeStrategy.discard
      case PathList("org", "apache", "hadoop", xs @ _*) => MergeStrategy.first
      case PathList("org", "slf4j", "impl", xs @ _*) => MergeStrategy.first
      case x =>
        val oldStrategy = (assembly / assemblyMergeStrategy).value
        oldStrategy(x)
    },
    Compile / run / mainClass := Some("CatsClient")
  )
