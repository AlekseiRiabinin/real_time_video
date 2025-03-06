import sbtassembly.AssemblyPlugin.autoImport._


lazy val root = (project in file("."))
  .settings(
    name := "MarsImageClassification",
    scalaVersion := "2.12.20",
    libraryDependencies ++= Seq(
      "org.apache.spark" %% "spark-sql" % "3.5.4" % "provided",
      "org.apache.spark" %% "spark-streaming" % "3.5.4" % "provided",
      "org.apache.spark" %% "spark-mllib" % "3.5.4" % "provided",
      "ch.qos.logback" % "logback-classic" % "1.2.11",
      "com.typesafe" % "config" % "1.4.2",
      "org.bytedeco" % "opencv" % "4.10.0-1.5.11" exclude("org.bytedeco", "javacpp-presets"),
      "org.bytedeco" % "ffmpeg" % "7.1-1.5.11" exclude("org.bytedeco", "javacpp-presets"),
      "org.bytedeco" % "javacpp" % "1.5.11",
      "org.bytedeco" % "javacv-platform" % "1.5.11",
      "org.bytedeco" % "openblas" % "0.3.23-1.5.9"
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
    Compile / run / mainClass := Some("MarsImageClassification")
  )

// Increasing the memory  
// export SBT_OPTS="-Xmx8G -XX:+UseG1GC"

// OPTIONAL create the .jvmopts file with the following:
// -Xmx4G
// -XX:+UseG1GC

// spark-submit \
//   --class MarsImageClassification \
//   --master local[*] \
//   apps/marsImageClassification/target/scala-2.12/MarsImageClassification-assembly-0.1.0-SNAPSHOT.jar