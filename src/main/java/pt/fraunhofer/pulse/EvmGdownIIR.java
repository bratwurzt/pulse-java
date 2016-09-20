package pt.fraunhofer.pulse;

import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;

public class EvmGdownIIR extends Evm
{

  public EvmGdownIIR()
  {
    super();
  }

  public EvmGdownIIR(String filename)
  {
    super(filename);
  }

  private static final double MIN_FACE_SIZE = 0.3;
  private static final int BLUR_LEVEL = 4;
  private static final double F_LOW = 50 / 60. / 10;
  private static final double F_HIGH = 60 / 60. / 10;
  private static final Scalar ALPHA = Scalar.all(200);

  private int t;
  private Point point;
  private Size minFaceSize;
  private Size maxFaceSize;
  private MatOfRect faces;
  private Mat gray;
  private Mat blurred;
  private Mat output;
  private Mat outputFloat;
  private Mat frameFloat;
  private Mat lowpassHigh;
  private Mat lowpassLow;
  private Mat blurredHigh;
  private Mat blurredLow;

  @Override
  public void start(int width, int height)
  {
    t = 0;
    point = new Point();
    minFaceSize = new Size(MIN_FACE_SIZE * width, MIN_FACE_SIZE * height);
    maxFaceSize = new Size();
    faces = new MatOfRect();
    gray = new Mat();
    blurred = new Mat();
    output = new Mat();
    outputFloat = new Mat();
    frameFloat = new Mat();
    lowpassHigh = new Mat();
    lowpassLow = new Mat();
    blurredHigh = new Mat();
    blurredLow = new Mat();
  }

  @Override
  public void stop()
  {
    t = 0;
    faces.release();
    gray.release();
    blurred.release();
    output.release();
    outputFloat.release();
    frameFloat.release();
    lowpassHigh.release();
    lowpassLow.release();
    blurredHigh.release();
    blurredLow.release();
  }

  @Override
  public Mat onFrame(Mat frame)
  {
    // face detection
    Imgproc.cvtColor(frame, gray, Imgproc.COLOR_RGB2GRAY);
    faceDetector.detectMultiScale(gray, faces, 1.1, 2, 0, minFaceSize, maxFaceSize);

    // convert to float and remove alpha channel
    frame.convertTo(frameFloat, CvType.CV_32F);
    Imgproc.cvtColor(frameFloat, frameFloat, Imgproc.COLOR_RGBA2RGB);

    // apply spatial filter: blur and downsample
    frameFloat.copyTo(blurred);
    for (int i = 0; i < BLUR_LEVEL; i++)
    {
      Imgproc.pyrDown(blurred, blurred);
    }

    // apply temporal filter: subtraction of two IIR lowpass filters
    if (0 == t)
    {
      blurred.copyTo(lowpassHigh);
      blurred.copyTo(lowpassLow);

      frame.copyTo(output);
    }
    else
    {
      Core.multiply(lowpassHigh, Scalar.all(1 - F_HIGH), lowpassHigh);
      Core.multiply(blurred, Scalar.all(F_HIGH), blurredHigh);
      Core.add(lowpassHigh, blurredHigh, lowpassHigh);

      Core.multiply(lowpassLow, Scalar.all(1 - F_LOW), lowpassLow);
      Core.multiply(blurred, Scalar.all(F_LOW), blurredLow);
      Core.add(lowpassLow, blurredLow, lowpassLow);

      Core.subtract(lowpassHigh, lowpassLow, blurred);

      // amplify
      Core.multiply(blurred, ALPHA, blurred);

      // resize back to original size
      for (int i = 0; i < BLUR_LEVEL; i++)
      {
        Imgproc.pyrUp(blurred, blurred);
      }
      Imgproc.resize(blurred, outputFloat, frame.size());

      // add back to original frame
      Core.add(frameFloat, outputFloat, outputFloat);

      // add back alpha channel and convert to 8 bit
      Imgproc.cvtColor(outputFloat, outputFloat, Imgproc.COLOR_RGB2RGBA);
      outputFloat.convertTo(output, CvType.CV_8U);
    }

    // draw some rectangles and points
    for (Rect face : faces.toArray())
    {
      Core.rectangle(output, face.tl(), face.br(), BLUE, 4);

      // forehead point
      point.x = face.tl().x + face.size().width * 0.5;
      point.y = face.tl().y + face.size().width * 0.2;
      point(output, point);

      // left cheek point
      point.x = face.tl().x + face.size().width * 0.7;
      point.y = face.tl().y + face.size().width * 0.6;
      point(output, point);

      // right cheek point
      point.x = face.tl().x + face.size().width * 0.3;
      point(output, point);
    }

    t++;

    return output;
  }

}
