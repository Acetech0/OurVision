package com.example.ourvision;

import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.util.Log;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Minimal YOLOv8 TFLite detector.
 *
 * Notes:
 * - Assumes you converted a YOLOv8 model to a TFLite model whose output is [1, N, 85]
 *   where each prediction is [x, y, w, h, conf, cls0, cls1, ...]. Adjust thresholds
 *   and parsing if your exported model differs.
 * - Place `model.tflite` and `labels.txt` in `app/src/main/assets/`.
 */
public class Yolov8TFLiteDetector {

    private static final String TAG = "Yolov8TFLiteDetector";
    private final Interpreter interpreter;
    private final int inputSize;
    private final float confThreshold = 0.35f;
    private final float iouThreshold = 0.45f;
    private final String[] labels;

    public static class Detection {
        public final String label;
        public final float confidence;
        public final RectF rect;

        public Detection(String label, float confidence, RectF rect) {
            this.label = label;
            this.confidence = confidence;
            this.rect = rect;
        }
    }

    public Yolov8TFLiteDetector(AssetManager assetManager, String modelPath, String[] labels, int inputSize) throws IOException {
        this.interpreter = new Interpreter(loadModelFile(assetManager, modelPath));
        this.inputSize = inputSize;
        this.labels = labels;
    }

    private static MappedByteBuffer loadModelFile(AssetManager assetManager, String modelPath) throws IOException {
        AssetFileDescriptor fileDescriptor = assetManager.openFd(modelPath);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    public List<Detection> detect(Bitmap bitmap) {
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true);
        ByteBuffer input = convertBitmapToByteBuffer(resized);

        // Model output: assume 1 x numPred x (5 + numClasses)
        // We'll allocate a reasonably large output. If your model provides a fixed size output,
        // adjust the dimensions accordingly for efficiency.
        int numClasses = labels.length;
        int maxPredictions = 25200; // typical for 640 with many anchors
        float[][][] output = new float[1][maxPredictions][5 + numClasses];

        Object[] inputs = new Object[]{input};
        java.util.Map<Integer, Object> outputs = new java.util.HashMap<>();
        outputs.put(0, output);

        try {
            interpreter.runForMultipleInputsOutputs(inputs, outputs);
        } catch (Exception e) {
            Log.e(TAG, "TFLite run failed", e);
            return Collections.emptyList();
        }

        List<Detection> detections = new ArrayList<>();

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        for (int i = 0; i < maxPredictions; i++) {
            float[] pred = output[0][i];
            if (pred == null) continue;
            float x = pred[0];
            float y = pred[1];
            float w = pred[2];
            float h = pred[3];
            float objConf = pred[4];

            // find class with highest score
            float maxClass = 0;
            int clsId = -1;
            for (int c = 0; c < numClasses; c++) {
                float score = pred[5 + c];
                if (score > maxClass) {
                    maxClass = score;
                    clsId = c;
                }
            }

            float conf = objConf * maxClass;
            if (conf < confThreshold || clsId < 0) continue;

            // Convert center x,y,w,h (normalized wrt inputSize) to box in original image coords
            float cx = x;
            float cy = y;
            float bw = w;
            float bh = h;

            // if model returns normalized values (0..1) vs absolute - handle both heuristically
            if (cx <= 1.0f && cy <= 1.0f && bw <= 1.0f && bh <= 1.0f) {
                cx *= width;
                cy *= height;
                bw *= width;
                bh *= height;
            }

            float left = cx - bw / 2f;
            float top = cy - bh / 2f;
            float right = cx + bw / 2f;
            float bottom = cy + bh / 2f;

            RectF rect = new RectF(left, top, right, bottom);
            detections.add(new Detection(labels[clsId], conf, rect));
        }

        // apply NMS per-class
        return nonMaxSuppression(detections, iouThreshold);
    }

    private ByteBuffer convertBitmapToByteBuffer(Bitmap bitmap) {
        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3);
        byteBuffer.order(ByteOrder.nativeOrder());
        int[] intValues = new int[inputSize * inputSize];
        bitmap.getPixels(intValues, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        int pixel = 0;
        for (int i = 0; i < inputSize; ++i) {
            for (int j = 0; j < inputSize; ++j) {
                final int val = intValues[pixel++];
                // Normalize to [0,1]
                byteBuffer.putFloat(((val >> 16) & 0xFF) / 255.0f);
                byteBuffer.putFloat(((val >> 8) & 0xFF) / 255.0f);
                byteBuffer.putFloat((val & 0xFF) / 255.0f);
            }
        }
        return byteBuffer;
    }

    private List<Detection> nonMaxSuppression(List<Detection> detections, float iouThreshold) {
        // Group by label
        List<Detection> result = new ArrayList<>();
        java.util.Map<String, List<Detection>> byLabel = new java.util.HashMap<>();
        for (Detection d : detections) {
            byLabel.computeIfAbsent(d.label, k -> new ArrayList<>()).add(d);
        }
        for (String label : byLabel.keySet()) {
            List<Detection> list = byLabel.get(label);
            Collections.sort(list, Comparator.comparingDouble(d -> -d.confidence));
            boolean[] removed = new boolean[list.size()];
            for (int i = 0; i < list.size(); i++) {
                if (removed[i]) continue;
                Detection a = list.get(i);
                result.add(a);
                for (int j = i + 1; j < list.size(); j++) {
                    if (removed[j]) continue;
                    Detection b = list.get(j);
                    if (iou(a.rect, b.rect) > iouThreshold) removed[j] = true;
                }
            }
        }
        return result;
    }

    private float iou(RectF a, RectF b) {
        float areaA = Math.max(0, a.width()) * Math.max(0, a.height());
        float areaB = Math.max(0, b.width()) * Math.max(0, b.height());
        float left = Math.max(a.left, b.left);
        float top = Math.max(a.top, b.top);
        float right = Math.min(a.right, b.right);
        float bottom = Math.min(a.bottom, b.bottom);
        float interW = Math.max(0, right - left);
        float interH = Math.max(0, bottom - top);
        float inter = interW * interH;
        return inter / (areaA + areaB - inter + 1e-6f);
    }
}
