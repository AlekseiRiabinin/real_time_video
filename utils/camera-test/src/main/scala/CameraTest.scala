import org.bytedeco.javacv.{CanvasFrame, OpenCVFrameGrabber}

object CameraTest extends App {
  val grabber = new OpenCVFrameGrabber(1) // 0 for default camera
  grabber.start()
  val canvas = new CanvasFrame("Camera Test")
  while (canvas.isVisible && (grabber.grab()) != null) {
    val frame = grabber.grab()
    canvas.showImage(frame)
  }
  grabber.stop()
  canvas.dispose()
}
