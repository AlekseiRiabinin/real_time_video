import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import java.net.URI


object RoverClient {
  def main(args: Array[String]): Unit = {
    // HDFS configuration
    val hdfsUri = "hdfs://namenode:8020" // HDFS URI
    val conf = new Configuration()
    conf.set("fs.defaultFS", hdfsUri)
    val fs = FileSystem.get(new URI(hdfsUri), conf)

    // Path to the local video file
    val localVideoPath = "./video.mp4"
    val hdfsVideoPath = "/videos/video.mp4" // Destination path in HDFS

    try {
      // Upload the video file to HDFS
      fs.copyFromLocalFile(new Path(localVideoPath), new Path(hdfsVideoPath))
      println(s"Video file uploaded to HDFS at $hdfsVideoPath")
    } catch {
      case ex: Exception =>
        println(s"Error uploading video to HDFS: ${ex.getMessage}")
    } finally {
      fs.close()
    }
  }
}
