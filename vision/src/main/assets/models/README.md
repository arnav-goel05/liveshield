# Offline face model provenance

- Asset: `face_detection_yunet_2023mar.onnx`
- Upstream repository: `opencv/opencv_zoo`
- Upstream URL:
  `https://media.githubusercontent.com/media/opencv/opencv_zoo/main/models/face_detection_yunet/face_detection_yunet_2023mar.onnx`
- Size: `232589` bytes
- SHA-256: `8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4`
- License: MIT, copyright 2020 Shiqi Yu
- License source:
  `https://github.com/opencv/opencv_zoo/blob/main/models/face_detection_yunet/LICENSE`

The production loader and `verifyYuNetModel` Gradle gate both reject a size or digest mismatch.
The model is bundled in the APK and is never downloaded or updated at runtime.
