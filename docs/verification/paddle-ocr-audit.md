# PaddleOCR / Paddle Lite offline runtime audit

**Frozen state:** 2026-08-13  
**Boundary:** source/package evidence plus historical API 36 ARM64 emulator runs; no physical-device
performance or current HOLDOUT claim.

## Restored operational baseline

LiveShield packages the last operationally audited PP-OCRv3 English slim detector and recognizer on
the paired stable Paddle Lite 2.11 Java/ARM64 runtime. Both inference lanes use BGR. The detector
retains polygon-masked DB scoring and bounded unclip ratio 1.6. Recognition preserves the official
aspect resize into a zero-padded `[1,3,48,320]` tensor, `x/127.5-1` normalization, the pinned English
dictionary, blank-first CTC collapse, and no text persistence or logging.

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| Paddle Lite 2.11 ARM64 archive | 9,791,354 | `b8165795964594adf7ded116f3955dfd4dfe5964c4559fd22e8d2aa6f73a674e` |
| `PaddlePredictor.jar` | 9,173 | `9b43bf6d07d4cccff14932fe4aee3d3cc2fb3ccae2edbbc28bb6326e4809223b` |
| `libpaddle_lite_jni.so` | 3,176,664 | `43ad4f58221570575e58d6af77653f476f7af485ee970ea924f20c0579cc2e01` |
| PP-OCRv3 English slim detector | 925,070 | `9d3c629313d47d203385216a756610eb00ee2496a06ff724cc34904deda70f22` |
| PP-OCRv3 English slim recognizer | 3,313,574 | `053b3a99fc88233c5ea5fda10141cf2f9c81e93ca2b74ce3dcf8208d3e80185d` |
| `en_dict.txt` | 190 | `5662df9d2d03f0e8ca0d3b0649d6acbab904b6a14b3d3521463c71c37c668ce3` |

The detector metadata identifies optimizer v2.10; the recognizer identifies v2.11-rc. The audited
stable runtime identifies v2.11 and accepts only those two frozen optimizer/runtime combinations.
Build and APK gates independently pin sizes, hashes, metadata versions, dictionary shape, runtime
identity, ARM64 packaging, and absence of both failed recognition candidates.

## Preserved privacy and lifecycle controls

- Models and native code are bundled. Hash-verified copies are written only to app-private
  no-backup storage; there is no downloader, network permission, telemetry, or logging API.
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
3. The targeted official `en_PP-OCRv5_mobile_rec` converted deterministically to a checker-valid
   opset-17 ONNX graph, and ONNX Runtime 1.26 CPU matched Paddle logits and CTC indices on zero
   inputs and eight bounded DEVELOPMENT-only crops. The frozen ONNX SHA-256 was
   `70b2450eed39599af6b996c27a2f1a0ef30eeb49f9f66dd3e74f28f652befc89`; the fixed reduced ORT
   model SHA-256 was `c36ffbd17ed13a0e4245f91146782be07ddf1e2965c0f07e987fb850b30ce079`.
   This was host numerical-parity evidence only. The pinned ARM64/API23 custom ORT build stopped
   before AAR/JNI compilation because vcpkg could not find host `pkg-config`. No ORT/model artifact
   is packaged, and Android size, manifest, ELF, Java-load, offline dependency, or accuracy gates
   passed. The preregistered stop rule prohibits another retry in this workstream.

The older v2.10 Paddle runtime failure at `fc_compute.cc` and subsequent v2.11 native smoke remain
historical compatibility evidence only. They do not establish automatic/watchlist accuracy.

## Acceptance boundary

Automatic text patterns and configured watchlists remain **unsupported**, SC-002 is **UNMET**, and
SC-009 is **UNMET**. QR and creator-configured full-area zones retain scoped DEVELOPMENT evidence.
The current Noto v2 HOLDOUT split remains sealed and must not be staged, inspected, or evaluated.
This workstream permits no further candidate, retry, threshold change, fixture tuning, or device run;
reopening it would require new explicit scope and authority.

For the terminal host/build evidence and cleanup boundary, see
[`t119-ocr-development.md`](t119-ocr-development.md). PP-OCRv3/Paddle Lite 2.11 remains the only
packaged OCR stack and remains unsupported for automatic text and configured-watchlist acceptance.
