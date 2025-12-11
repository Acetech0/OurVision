Place your converted YOLOv8 TFLite model and labels in this folder.

- model.tflite  -> The tflite model file exported from YOLOv8 (use a model converted to TFLite).
- labels.txt    -> Plain text file with one label per line, e.g.:
  person
  bicycle
  car

Notes:
- The Java detector expects an output shape similar to [1, N, 5+num_classes]. If your TFLite export has a different output format, update `Yolov8TFLiteDetector.detect()` to match.
- For best performance on Android, consider converting to a quantized model or using GPU/NNAPI delegates.
- After placing the files, rebuild the app.
