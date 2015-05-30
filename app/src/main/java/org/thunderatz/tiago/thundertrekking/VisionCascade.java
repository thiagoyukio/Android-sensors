package org.thunderatz.tiago.thundertrekking;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Mat;
import org.opencv.core.MatOfRect;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.CascadeClassifier;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class VisionCascade extends Activity implements CameraBridgeViewBase.CvCameraViewListener2 {
    private Mat frameGray, frameRGBA;
    private CascadeClassifier cascadeDetector;
    private static final Scalar HIT_COLOR = new Scalar(0, 255, 0, 255);
    private boolean testing = true;
    private CameraBridgeViewBase mOpenCvCameraView;

    private BaseLoaderCallback loaderCallback = new BaseLoaderCallback(this) {
        @Override
        public void onManagerConnected(int status) {
            switch (status) {
                case LoaderCallbackInterface.SUCCESS:
                {
                    try {
                        File mCascadeFile;
                        // load cascade file from application resources
                        InputStream is = getResources().openRawResource(R.raw.lbpcascade_frontalface);
                        File cascadeDir = getDir("cascade", Context.MODE_PRIVATE);
                        mCascadeFile = new File(cascadeDir, "lbpcascade_frontalface.xml");
                        FileOutputStream os = new FileOutputStream(mCascadeFile);

                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        while ((bytesRead = is.read(buffer)) != -1) {
                            os.write(buffer, 0, bytesRead);
                        }
                        is.close();
                        os.close();

                        cascadeDetector = new CascadeClassifier(mCascadeFile.getAbsolutePath());
                        if (cascadeDetector.empty())
                            cascadeDetector = null;

                        cascadeDir.delete();

                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                    mOpenCvCameraView.enableView();
                } break;
                default:
                {
                    super.onManagerConnected(status);
                } break;
            }
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.opencv_test_window);

        mOpenCvCameraView = (CameraBridgeViewBase) findViewById(R.id.fd_activity_surface_view);
        mOpenCvCameraView.setCvCameraViewListener(this);
    }

    @Override
    public void onPause()
    {
        super.onPause();
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.disableView();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!OpenCVLoader.initDebug())
            // local library
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_3_0_0, this, loaderCallback);
        else
            // system library
            loaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mOpenCvCameraView.disableView();
    }

    @Override
    public void onCameraViewStarted(int width, int height) {
        frameGray = new Mat();
        frameRGBA = new Mat();
    }

    @Override
    public Mat onCameraFrame(CameraBridgeViewBase.CvCameraViewFrame inputFrame) {
        frameGray = inputFrame.gray();

        MatOfRect hits = new MatOfRect();
        if (cascadeDetector != null)
            cascadeDetector.detectMultiScale(frameGray, hits, 1.1, 2, 2,
                new Size(100, 150), new Size());

        if (testing) {
            frameRGBA = inputFrame.rgba();
            Rect[] hitsArray = hits.toArray();
            for (int i = 0; i < hitsArray.length; i++)
                Imgproc.rectangle(frameRGBA, hitsArray[i].tl(), hitsArray[i].br(), HIT_COLOR, 3);
            return frameRGBA;
        }
        return null;
    }

    @Override
    public void onCameraViewStopped() {
        frameGray.release();
        frameRGBA.release();
    }
}
