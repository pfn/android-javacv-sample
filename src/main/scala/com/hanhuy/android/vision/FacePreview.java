package com.hanhuy.android.vision;

/*
 * Copyright (C) 2010,2011,2012 Samuel Audet
 *
 * FacePreview - A fusion of OpenCV's facedetect and Android's CameraPreview samples,
 *               with JavaCV + JavaCPP as the glue in between.
 *
 * This file was based on CameraPreview.java that came with the Samples for
 * Android SDK API 8, revision 1 and contained the following copyright notice:
 *
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *
 * IMPORTANT - Make sure the AndroidManifest.xml file looks like this:
 *
 * <?xml version="1.0" encoding="utf-8"?>
 * <manifest xmlns:android="http://schemas.android.com/apk/res/android"
 *     package="org.bytedeco.javacv.facepreview"
 *     android:versionCode="1"
 *     android:versionName="1.0" >
 *     <uses-sdk android:minSdkVersion="4" />
 *     <uses-permission android:name="android.permission.CAMERA" />
 *     <uses-feature android:name="android.hardware.camera" />
 *     <application android:label="@string/app_name">
 *         <activity
 *             android:name="FacePreview"
 *             android:label="@string/app_name"
 *             android:screenOrientation="landscape">
 *             <intent-filter>
 *                 <action android:name="android.intent.action.MAIN" />
 *                 <category android:name="android.intent.category.LAUNCHER" />
 *             </intent-filter>
 *         </activity>
 *     </application>
 * </manifest>
 */


import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.*;
import android.hardware.Camera;
import android.hardware.Camera.Size;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.List;

import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.ContiguousSet;
import com.google.common.collect.DiscreteDomain;
import com.google.common.collect.Iterables;
import org.bytedeco.javacv.AndroidFrameConverter;
import org.bytedeco.javacv.OpenCVFrameConverter.ToIplImage;

import static org.bytedeco.javacpp.Loader.sizeof;
import static org.bytedeco.javacpp.opencv_core.*;
import static org.bytedeco.javacpp.opencv_imgproc.*;

// ----------------------------------------------------------------------

public class FacePreview extends Activity {
    private FrameLayout layout;
    private FaceView faceView;
    private Preview mPreview;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Hide the window title.
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Create our Preview view and set it as the content of our activity.
        try {
            layout = new FrameLayout(this);
            layout.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_IMMERSIVE |
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            faceView = new FaceView(this);
            mPreview = new Preview(this, faceView);
            layout.addView(mPreview);
            layout.addView(faceView);
            setContentView(layout);
        } catch (IOException e) {
            e.printStackTrace();
            new AlertDialog.Builder(this).setMessage(e.getMessage()).create().show();
        }
    }
}

// ----------------------------------------------------------------------

class FrameConverter {
    private final AndroidFrameConverter androidConverter = new AndroidFrameConverter();
    private final ToIplImage iplConverter = new ToIplImage();
    public IplImage convert(byte[] data, int width, int height) {
        return iplConverter.convert(androidConverter.convert(data, width, height));
    }
    public Bitmap convert(IplImage ipl) {
        return androidConverter.convert(iplConverter.convert(ipl));
    }
}
class FaceView extends View implements Camera.PreviewCallback {
    public static final int SUBSAMPLING_FACTOR = 4;

    private IplImage mask;
    private IplImage frame;
    private IplImage frame2;
    private CvMemStorage storage;
    private final FrameConverter previewConverter = new FrameConverter();
    private final FrameConverter hsvConverter = new FrameConverter();
    private final FrameConverter maskConverter = new FrameConverter();

    public FaceView(FacePreview context) throws IOException {
        super(context);

        // Preload the opencv_objdetect module to work around a known bug.
//        Loader.load(opencv_objdetect.class);
        storage = CvMemStorage.create();
    }

    public void onPreviewFrame(final byte[] data, final Camera camera) {
        try {
            Camera.Size size = camera.getParameters().getPreviewSize();
            processImage(data, size.width, size.height);
            camera.addCallbackBuffer(data);
        } catch (RuntimeException e) {
            // The camera has probably just been released, ignore.
        }
    }

    protected void processImage(byte[] data, int width, int height) {
        // First, downsample our image and convert it into a grayscale IplImage
        int f = SUBSAMPLING_FACTOR;
        if (mask == null || mask.width() != width/f || mask.height() != height/f) {
            mask = IplImage.create(width/f, height/f, IPL_DEPTH_8U, 1);
        }
        if (frame == null || frame.width() != width/f || frame.height() != height/f) {
            frame = IplImage.create(width/f, height/f, IPL_DEPTH_8U, 3);
            frame2 = IplImage.create(width/f, height/f, IPL_DEPTH_8U, 3);
        }
        int imageWidth  = frame.width();
        int imageHeight = frame.height();
        int dataStride = f*width;
        int imageStride = frame.widthStep();

        int[] ints = new int[width * height];
        decodeYUV420SP(ints, data, width, height);
        ByteBuffer maskBuffer = mask.createBuffer();
        ByteBuffer frameBuffer = frame.createBuffer();
        int rgba;
        for (int y = 0; y < imageHeight; y++) {
            for (int x = 0; x < imageWidth; x++) {
                rgba = ints[y * dataStride + x * f];
                frameBuffer.put(y * imageStride + x * 3 + 2, (byte)( rgba  >> 24));
                frameBuffer.put(y * imageStride + x * 3 + 1, (byte)((rgba >> 16) & 0xff));
                frameBuffer.put(y * imageStride + x * 3,     (byte)((rgba >> 8)  & 0xff));
            }
        }
        cvCvtColor(frame, frame, CV_BGR2HSV);
        ByteBuffer hsvBuffer = frame.createBuffer();
        int hsvStep = frame.widthStep();
        boolean hsv1, hsv2;
        for (int y = 0; y < imageHeight; y++) {
            for (int x = 0; x < imageWidth; x++) {
                hsv1 = (hsvBuffer.get(y*hsvStep+x*3) & 0xff) > 170;
                hsv2 = (hsvBuffer.get(y*hsvStep+x*3+2) & 0xff) < 90;
                maskBuffer.put(y*imageWidth + x, (byte)(hsv1 || hsv2 ? 0xff : 0));
            }
        }
        cvCvtColor(frame, frame, CV_HSV2BGR);
        cvErode(mask, mask, null, 1);
        cvDilate(mask, mask, null, 1);
//        CvSeq contours = new CvSeq();
//        cvFindContours(mask, storage, contours, sizeof(CvContour.class), CV_RETR_EXTERNAL, CV_CHAIN_APPROX_NONE, cvPoint(0,0));
//        while (contours != null && contours.total() <= 650) {
//            contours = contours.h_next();
//        }
//        if (contours != null && contours.total() > 650)
//            cvDrawContours(frame, contours, CV_RGB(100,100,100), CV_RGB(0,255,0), 1, 2, CV_AA, cvPoint(0,0));

        cvClearMemStorage(storage);
        postInvalidate();
    }

    private final Paint paint;
    private final String s = "FacePreview - This side up.";
    private final float textWidth;
    {
        paint = new Paint();
        paint.setColor(Color.RED);
        paint.setTextSize(20);
        textWidth = paint.measureText(s);
    }
    @Override
    protected void onDraw(Canvas canvas) {

        canvas.drawText(s, (getWidth()-textWidth)/2, 20, paint);
        Bitmap b = previewConverter.convert(frame);

//        if (faces != null) {
//            paint.setStrokeWidth(2);
//            paint.setStyle(Paint.Style.STROKE);
//            float scaleX = (float)getWidth()/grayImage.width();
//            float scaleY = (float)getHeight()/grayImage.height();
//            int total = faces.total();
//            for (int i = 0; i < total; i++) {
//                CvRect r = new CvRect(cvGetSeqElem(faces, i));
//                int x = r.x(), y = r.y(), w = r.width(), h = r.height();
//                canvas.drawRect(x*scaleX, y*scaleY, (x+w)*scaleX, (y+h)*scaleY, paint);
//            }
//        }
        canvas.save();
        canvas.scale(-1, 1);
        if (b != null) {
            canvas.drawBitmap(b, -canvas.getWidth() + 100, 100, null);
        }
        b = maskConverter.convert(mask);
        if (b != null) {
            canvas.drawBitmap(b, -canvas.getWidth() + 100, 500, null);
        }
        canvas.restore();
    }
    static void decodeYUV420SP(int[] rgba, byte[] yuv420sp, int width,
                                      int height) {


        final int frameSize = width * height;
// define variables before loops (+ 20-30% faster algorithm o0`)
        int r, g, b, y1192, y, i, uvp, u, v;
        for (int j = 0, yp = 0; j < height; j++) {
            uvp = frameSize + (j >> 1) * width;
            u = 0;
            v = 0;
            for (i = 0; i < width; i++, yp++) {
                y = (0xff & ((int) yuv420sp[yp])) - 16;
                if (y < 0)
                    y = 0;
                if ((i & 1) == 0) {
                    v = (0xff & yuv420sp[uvp++]) - 128;
                    u = (0xff & yuv420sp[uvp++]) - 128;
                }

                y1192 = 1192 * y;
                r = (y1192 + 1634 * v);
                g = (y1192 - 833 * v - 400 * u);
                b = (y1192 + 2066 * u);

// Java's functions are faster then 'IFs'
                r = Math.max(0, Math.min(r, 262143));
                g = Math.max(0, Math.min(g, 262143));
                b = Math.max(0, Math.min(b, 262143));

                // rgb[yp] =  ((r << 6) & 0xff0000) | ((g >> 2) &
                // 0xff00) | ((b >> 10) & 0xff);
                // rgba, divide 2^10 ( >> 10)
                rgba[yp] = ((r << 14) & 0xff000000) | ((g << 6) & 0xff0000)
                        | ((b >> 2) & 0xff00) | 0xff;
//                rgba[yp] = 0xff000000 | ((b << 16) & 0xff0000) | ((g << 8) & 0xff00) | (r & 0xff);
            }
        }
    }

}

// ----------------------------------------------------------------------

class Preview extends SurfaceView implements SurfaceHolder.Callback {
    SurfaceHolder mHolder;
    Camera mCamera;
    Camera.PreviewCallback previewCallback;

    Preview(Context context, Camera.PreviewCallback previewCallback) {
        super(context);
        this.previewCallback = previewCallback;

        // Install a SurfaceHolder.Callback so we get notified when the
        // underlying surface is created and destroyed.
        mHolder = getHolder();
        mHolder.addCallback(this);
        mHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
    }

    public void surfaceCreated(SurfaceHolder holder) {
        // The Surface has been created, acquire the camera and tell it where
        // to draw.
        int count = Camera.getNumberOfCameras();
        final Camera.CameraInfo info = new Camera.CameraInfo();
        Optional<Integer> found = Iterables.tryFind(ContiguousSet.create(com.google.common.collect.Range.closedOpen(0, count), DiscreteDomain.integers()),
                new Predicate<Integer>() {
                    @Override
                    public boolean apply(Integer input) {
                        Camera.getCameraInfo(input, info);
                        return info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT;
                    }
                }
        );
        mCamera = found.isPresent() ? Camera.open(found.get()) : Camera.open();
        mCamera.setDisplayOrientation((info.orientation + 90) % 360);
        try {
            mCamera.setPreviewDisplay(holder);
        } catch (IOException exception) {
            mCamera.release();
            mCamera = null;
            // TODO: add more exception handling logic here
        }
    }

    public void surfaceDestroyed(SurfaceHolder holder) {
        // Surface will be destroyed when we return, so stop the preview.
        // Because the CameraDevice object is not a shared resource, it's very
        // important to release it when the activity is paused.
        mCamera.stopPreview();
        mCamera.release();
        mCamera = null;
    }


    private Size getOptimalPreviewSize(List<Size> sizes, int w, int h) {
        final double ASPECT_TOLERANCE = 0.05;
        double targetRatio = (double) w / h;
        if (sizes == null) return null;

        Size optimalSize = null;
        double minDiff = Double.MAX_VALUE;

        int targetHeight = h;

        // Try to find an size match aspect ratio and size
        for (Size size : sizes) {
            double ratio = (double) size.width / size.height;
            if (Math.abs(ratio - targetRatio) > ASPECT_TOLERANCE) continue;
            if (Math.abs(size.height - targetHeight) < minDiff) {
                optimalSize = size;
                minDiff = Math.abs(size.height - targetHeight);
            }
        }

        // Cannot find the one match the aspect ratio, ignore the requirement
        if (optimalSize == null) {
            minDiff = Double.MAX_VALUE;
            for (Size size : sizes) {
                if (Math.abs(size.height - targetHeight) < minDiff) {
                    optimalSize = size;
                    minDiff = Math.abs(size.height - targetHeight);
                }
            }
        }
        return optimalSize;
    }

    boolean parametersSet = false;
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        if (parametersSet) return;
        parametersSet = true;
        // Now that the size is known, set up the camera parameters and begin
        // the preview.
        Camera.Parameters parameters = mCamera.getParameters();

        List<Size> sizes = parameters.getSupportedPreviewSizes();
        Size optimalSize = getOptimalPreviewSize(sizes, w, h);
        parameters.setPreviewSize(optimalSize.width, optimalSize.height);
//        parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);

        mCamera.setParameters(parameters);
        if (previewCallback != null) {
            mCamera.setPreviewCallbackWithBuffer(previewCallback);
            Camera.Size size = parameters.getPreviewSize();
            byte[] data = new byte[size.width*size.height*
                    ImageFormat.getBitsPerPixel(parameters.getPreviewFormat())/8];
            mCamera.addCallbackBuffer(data);
        }
        mCamera.startPreview();
    }

}
