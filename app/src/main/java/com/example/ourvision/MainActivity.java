package com.example.ourvision;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.RectF;
import android.graphics.Bitmap;
import android.speech.tts.TextToSpeech;
import android.os.Bundle;
import android.widget.Toast;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@androidx.camera.core.ExperimentalGetImage
public class MainActivity extends AppCompatActivity {

    private PreviewView previewView;
    private OverlayView overlayView;
    private ExecutorService cameraExecutor;
    private Yolov8TFLiteDetector yolov8;
    private TextToSpeech tts;
    private static final int CAMERA_PERMISSION_CODE = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        overlayView = findViewById(R.id.overlay);
        cameraExecutor = Executors.newSingleThreadExecutor();

        // initialize YOLOv8 detector (model and labels must be placed into assets)
        try {
            String[] labels = loadLabelsFromAssets("labels.txt");
            yolov8 = new Yolov8TFLiteDetector(getAssets(), "model.tflite", labels, 640);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // initialize TTS
        tts = new TextToSpeech(this, status -> {
            // no-op
        });

        // check camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
        }
    }

    private String[] loadLabelsFromAssets(String path) {
        try (java.io.InputStream is = getAssets().open(path);
             java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(is))) {
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) lines.add(line);
            }
            return lines.toArray(new String[0]);
        } catch (Exception e) {
            e.printStackTrace();
            return new String[]{"object"};
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, imageProxy -> {
                    runOnUiThread(() -> {
                        Bitmap bmp = previewView.getBitmap();
                        if (bmp != null && yolov8 != null) {
                            cameraExecutor.execute(() -> {
                                List<Yolov8TFLiteDetector.Detection> dets = yolov8.detect(bmp);
                                List<RectF> rects = new ArrayList<>();
                                float scaleX = overlayView.getWidth() / (float) bmp.getWidth();
                                float scaleY = overlayView.getHeight() / (float) bmp.getHeight();
                                for (Yolov8TFLiteDetector.Detection d : dets) {
                                    RectF r = d.rect;
                                    rects.add(new RectF(r.left * scaleX, r.top * scaleY, r.right * scaleX, r.bottom * scaleY));
                                }
                                runOnUiThread(() -> {
                                    overlayView.setBoxes(rects);
                                    if (!dets.isEmpty()) {
                                        Yolov8TFLiteDetector.Detection best = dets.get(0);
                                        String speak = best.label + " " + Math.round(best.confidence * 100) + " percent";
                                        tts.speak(speak, TextToSpeech.QUEUE_FLUSH, null, "det");
                                    }
                                });
                                imageProxy.close();
                            });
                        } else {
                            imageProxy.close();
                        }
                    });
                });

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

            } catch (Exception e) {
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_CODE &&
                grantResults.length > 0 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show();
        }
    }
}
