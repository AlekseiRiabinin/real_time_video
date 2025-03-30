import sbtassembly.AssemblyPlugin.autoImport._
import sbtassembly.MergeStrategy

lazy val root = (project in file("."))
  .settings(
    name := "FS2Client",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := "3.3.4",
    
    // Assembly plugin settings
    assembly / mainClass := Some("FS2Client"),
    assembly / assemblyJarName := "fs2-client-assembly-0.1.0-SNAPSHOT.jar",
    assembly / assemblyOption := (assembly / assemblyOption).value.copy(includeScala = true),
    
    // Dependencies
    libraryDependencies ++= Seq(
      "com.github.fd4s" %% "fs2-kafka" % "3.6.0",
      "org.typelevel" %% "cats-effect" % "3.5.4",
      "org.apache.kafka" % "kafka-clients" % "3.7.0",
      "org.apache.hadoop" % "hadoop-client" % "3.3.6" excludeAll(
        ExclusionRule("javax.activation", "activation"),
        ExclusionRule("javax.xml.bind", "jaxb-api")
      ),
      "org.apache.hadoop" % "hadoop-common" % "3.3.6" excludeAll(
        ExclusionRule("javax.activation", "activation"),
        ExclusionRule("javax.xml.bind", "jaxb-api")
      ),
      "org.apache.hadoop" % "hadoop-hdfs" % "3.3.6" excludeAll(
        ExclusionRule("javax.activation", "activation"),
        ExclusionRule("javax.xml.bind", "jaxb-api")
      ),
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
      "Bytedeco" at "https://repo.bytedeco.org/releases/",
      "Typelevel Repository" at "https://repo.typelevel.org/stable/"
    ),
    
    // Enhanced merge strategy
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
      case PathList("META-INF", "services", _*) => MergeStrategy.filterDistinctLines
      case PathList("META-INF", "versions", _*) => MergeStrategy.first
      case PathList("META-INF", _*) => MergeStrategy.discard
      case "module-info.class" => MergeStrategy.discard
      case "reference.conf" => MergeStrategy.concat
      case "version.conf" => MergeStrategy.concat
      case PathList("org", "opencv", _*) => MergeStrategy.first
      case PathList("org", "bytedeco", _*) => MergeStrategy.first
      case PathList("org", "bytedeco", "javacpp", "linux-x86_64", _*) => MergeStrategy.first
      case PathList("javax", "inject", _*) => MergeStrategy.first
      case PathList("javax", "activation", _*) => MergeStrategy.discard
      case PathList("javax", "xml", "bind", _*) => MergeStrategy.discard
      case PathList("org", "apache", "hadoop", _*) => MergeStrategy.first
      case PathList("org", "slf4j", _*) => MergeStrategy.first
      // FS2/Cats specific cases
      case PathList("cats", _*) => MergeStrategy.first
      case PathList("fs2", _*) => MergeStrategy.first
      case PathList("co", "fs2", _*) => MergeStrategy.first
      case x => MergeStrategy.first
    },
    
    // Runtime settings
    Compile / run / mainClass := Some("FS2Client"),
    run / fork := true,
    run / javaOptions ++= Seq(
      "-Djava.library.path=/usr/local/lib"
    )
  )
