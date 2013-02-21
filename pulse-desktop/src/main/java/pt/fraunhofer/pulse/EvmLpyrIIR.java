package pt.fraunhofer.pulse;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfRect;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

public class EvmLpyrIIR extends Evm {

    public EvmLpyrIIR() {
        super();
    }

    public EvmLpyrIIR(String filename) {
        super(filename);
    }

    private static final float MIN_FACE_SIZE = 0.3f;
    private static final float F_LOW = 0.05f;
    private static final float F_HIGH = 0.4f;
    private static final Scalar ALPHA = Scalar.all(50);

    private int t;
    private int pyrLevel;
    private Point point;
    private Size minFaceSize;
    private Size maxFaceSize;
    private MatOfRect faces;
    private Mat gray;
    private Mat blurred;
    private Mat output;
    private Mat outputFloat;
    private Mat frameFloat;
    private Mat buildLpyrAux;
    private Mat[] lowpassHigh;
    private Mat[] lowpassLow;
    private Mat[] lowpassHighAux;
    private Mat[] lowpassLowAux;
    private Mat[] pyr;

    @Override
    public void start(int width, int height) {
        t = 0;
        pyrLevel = (int) (Math.log(Math.min(width, height) / 5.0) / Math.log(2));
        point = new Point();
        minFaceSize = new Size(MIN_FACE_SIZE * width, MIN_FACE_SIZE * height);
        maxFaceSize = new Size();
        faces = new MatOfRect();
        gray = new Mat();
        blurred = new Mat();
        output = new Mat();
        outputFloat = new Mat();
        frameFloat = new Mat();
        buildLpyrAux = new Mat();
        lowpassHigh = new Mat[pyrLevel + 1];
        lowpassLow = new Mat[pyrLevel + 1];
        lowpassHighAux = new Mat[pyrLevel + 1];
        lowpassLowAux = new Mat[pyrLevel + 1];
        pyr = new Mat[pyrLevel + 1];
        for (int i = 0; i < pyr.length; i++) {
            lowpassHigh[i] = new Mat();
            lowpassLow[i] = new Mat();
            lowpassHighAux[i] = new Mat();
            lowpassLowAux[i] = new Mat();
            pyr[i] = new Mat();
        }
    }

    @Override
    public void stop() {
        t = 0;
        faces.release();
        gray.release();
        blurred.release();
        output.release();
        outputFloat.release();
        frameFloat.release();
        buildLpyrAux.release();
        for (int i = 0; i < pyr.length; i++) {
            lowpassHigh[i].release();
            lowpassLow[i].release();
            lowpassHighAux[i].release();
            lowpassLowAux[i].release();
            pyr[i].release();
        }
        lowpassHigh = null;
        lowpassLow = null;
        lowpassHighAux = null;
        lowpassLowAux = null;
        pyr = null;
    }

    @Override
    public Mat onFrame(Mat frame) {
        // face detection
        Imgproc.cvtColor(frame, gray, Imgproc.COLOR_RGB2GRAY);
        faceDetector.detectMultiScale(gray, faces, 1.1, 2, 0, minFaceSize, maxFaceSize);

        // convert to YUV color space
        frame.convertTo(frameFloat, CvType.CV_32F);
        Imgproc.cvtColor(frameFloat, frameFloat, Imgproc.COLOR_RGB2YUV);

        // apply spatial filter: Laplacian pyramid
        buildLpyr(frameFloat, pyr, pyrLevel);

        // apply temporal filter: substraction of two IIR lowpass filters
        if (0 == t) {
            for (int i = 0; i < pyr.length; i++) {
                pyr[i].copyTo(lowpassHigh[i]);
                pyr[i].copyTo(lowpassLow[i]);
            }

            frame.copyTo(output);
        } else {
            for (int i = 0; i < pyr.length; i++) {
                Core.multiply(lowpassHigh[i], Scalar.all(1-F_HIGH), lowpassHigh[i]);
                Core.multiply(pyr[i], Scalar.all(F_HIGH), lowpassHighAux[i]);
                Core.add(lowpassHigh[i], lowpassHighAux[i], lowpassHigh[i]);

                Core.multiply(lowpassLow[i], Scalar.all(1-F_LOW), lowpassLow[i]);
                Core.multiply(pyr[i], Scalar.all(F_LOW), lowpassLowAux[i]);
                Core.add(lowpassLow[i], lowpassLowAux[i], lowpassLow[i]);

                Core.subtract(lowpassHigh[i], lowpassLow[i], pyr[i]);

                // amplify
                Core.multiply(pyr[i], ALPHA, pyr[i]);
            }

            // rebuild frame from Laplacian pyramid
            reconLpyr(pyr, outputFloat);

            // add back to original frame
            Core.add(frameFloat, outputFloat, outputFloat);

            // convert back to RGBA
            Imgproc.cvtColor(outputFloat, outputFloat, Imgproc.COLOR_YUV2RGB);
            Imgproc.cvtColor(outputFloat, outputFloat, Imgproc.COLOR_RGB2RGBA);
            outputFloat.convertTo(output, CvType.CV_8U);
        }

        // draw some rectangles and points
        for (Rect face : faces.toArray()) {
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

    private void buildLpyr(Mat a, Mat[] pyr, int level) {
        a.copyTo(pyr[0]);
        for (int i = 0; i < level; i++) {
            Imgproc.pyrDown(pyr[i], pyr[i+1]);
            Imgproc.pyrUp(pyr[i+1], buildLpyrAux);
            Imgproc.resize(buildLpyrAux, buildLpyrAux, pyr[i].size());
            Core.subtract(pyr[i], buildLpyrAux, pyr[i]);
        }
    }

    private void reconLpyr(Mat[] pyr, Mat o) {
        pyr[pyr.length - 1].copyTo(o);
        for (int i = pyr.length; i > 0; i--) {
            Imgproc.pyrUp(o, o);
            Imgproc.resize(o, o, pyr[i-1].size());
            Core.add(pyr[i-1], o, o);
        }
    }

}