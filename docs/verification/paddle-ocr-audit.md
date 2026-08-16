# PaddleOCR / Paddle Lite offline runtime audit

**Current state:** 2026-08-16
**Boundary:** current API-24 source/package evidence plus historical device evidence for older
stacks; no device inference, accuracy, performance, or HOLDOUT claim for English PP-OCRv5.

## Current packaged stack

LiveShield packages the PP-OCRv3 English detector on Paddle Lite 2.11 and the targeted English
PP-OCRv5 recognizer on stock ONNX Runtime Android 1.28. Both inference lanes use BGR. The detector
retains polygon-masked DB scoring and bounded unclip ratio 1.6. Recognition preserves the official
aspect resize into a zero-padded `[1,3,48,320]` tensor, `x/127.5-1` normalization, the pinned English
dictionary, blank-first CTC collapse, and no text persistence or logging.

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| Paddle Lite 2.11 ARM64 archive | 9,791,354 | `b8165795964594adf7ded116f3955dfd4dfe5964c4559fd22e8d2aa6f73a674e` |
| `PaddlePredictor.jar` | 9,173 | `9b43bf6d07d4cccff14932fe4aee3d3cc2fb3ccae2edbbc28bb6326e4809223b` |
| `libpaddle_lite_jni.so` | 3,176,664 | `43ad4f58221570575e58d6af77653f476f7af485ee970ea924f20c0579cc2e01` |
| PP-OCRv3 English slim detector | 925,070 | `9d3c629313d47d203385216a756610eb00ee2496a06ff724cc34904deda70f22` |
| English PP-OCRv5 ONNX recognizer | 7,843,511 | `70b2450eed39599af6b996c27a2f1a0ef30eeb49f9f66dd3e74f28f652befc89` |
| English PP-OCRv5 dictionary | 1,416 | `e025a66d31f327ba0c232e03f407ae8d105e1e709e7ccb3f408aa778c24e70d6` |
| ONNX Runtime Android 1.28 AAR | 45,634,470 | `f351a0638696f54b35184290dbc001d66daae17281ad0b548d2c70347d53b8a9` |
| ONNX Runtime ARM64 JNI | 28,637,280 | `f826d8efb03adf0a84f10e7ba408f9d4cd11b0a2ccd8d08aeb0f7451fb50cacc` |

The detector metadata identifies optimizer v2.10 and the Paddle runtime identifies v2.11. The
recognizer has a fixed `[N,3,48,W] -> [N,T,438]` contract. Build and APK gates pin artifact bytes,
dictionary shape, API 24, ARM64 packaging, and absence of the older failed candidates.

## Preserved privacy and lifecycle controls

- Models and native code are bundled. Hash-verified copies are written only to app-private
  no-backup storage; there is no downloader or OCR logging API, and ORT telemetry is disabled
  before session creation.
- Recognized text remains method-local and is immediately reduced to category, bounded polygon,
  confidence class, and timestamp metadata.
- Analysis remains one-in-flight. Capacity rejection and cancellation release input exactly once.
  Completion is published only after input release, and engine close cannot race native ownership.
- The approved BGR fix, faithful DB polygon scoring/unclip, Noto corpus v2, payload-free diagnostic
  counts, Java/APK runner preflights, QR geometry, configured-zone policy, and production Priority 2
  scheduler/policy wiring remain in place.

## Failed candidate record

Neither experiment supplies accuracy evidence:

1. General PP-OCRv5 plus Paddle Lite 2.14-rc failed operationally on the first DEVELOPMENT text
   fixture before a complete findings file or accuracy evaluation. Its model, labels, JAR, and JNI
   are absent from the source and packages.
2. OpenCV Zoo CRNN-CH FP16 passed source/package verification but the genuine API 36 ARM64 run
   crashed on its first `Net.forward` with native `SIGILL / ILL_ILLOPC` in
   `cv::hal::exp32f` -> `cv::exp` -> OpenCV DNN. It produced 0/104 complete observations. Its ONNX,
   license, provenance helper, and code paths are absent from the source and packages.
3. The targeted official `en_PP-OCRv5_mobile_rec` converted to a checker-valid
   opset-17 ONNX graph, and ONNX Runtime 1.26 CPU matched Paddle logits and CTC indices on zero
   inputs and eight bounded DEVELOPMENT-only crops. The frozen ONNX SHA-256 was
   `70b2450eed39599af6b996c27a2f1a0ef30eeb49f9f66dd3e74f28f652befc89`; the fixed reduced ORT
   model SHA-256 was `c36ffbd17ed13a0e4245f91146782be07ddf1e2965c0f07e987fb850b30ce079`.
   The custom API-23 runtime build failed. A later explicit product decision raised `minSdk` to 24,
   removed size limits, and packaged the stock ORT 1.28 AAR. Source and packaging gates now pass,
   but Android inference and accuracy remain unverified.

The older v2.10 Paddle runtime failure at `fc_compute.cc` and subsequent v2.11 native smoke remain
historical compatibility evidence only. They do not establish automatic/watchlist accuracy.

## Acceptance boundary

Automatic text patterns remain **unsupported**. Session-only private words are implemented but
unverified. SC-002 and SC-009 remain **UNMET**. QR and creator-configured full-area zones retain
scoped DEVELOPMENT evidence.
The current Noto v2 HOLDOUT split remains sealed and must not be staged, inspected, or evaluated.
No threshold change, fixture tuning, or HOLDOUT access occurred.

For the terminal host/build evidence and cleanup boundary, see
[`t119-ocr-development.md`](t119-ocr-development.md). The current stack is PP-OCRv3 detection plus
English PP-OCRv5 recognition; it has package evidence only.
