# LiveShield

LiveShield is a Java-first Android prototype for privacy-protected, video-only live streaming. It
analyzes camera frames on-device, protects non-host faces and configured visual risks, renders one
sanitized video path, delays encoded H.264 by two seconds, and publishes it to a controlled RTMP
destination. Missing, stale, malformed, or failed privacy decisions strengthen protection or stop
output; they do not authorize untreated camera pixels.

This is a portfolio and evaluation project, not a guarantee of anonymity or universal sensitive
content detection. Its supported setting is a controlled indoor, solo-creator session. Public,
moving outdoor, and dense-crowd use are explicitly unsupported.

## Trust boundary

```text
UNTRUSTED RAW, DEVICE MEMORY ONLY
CameraX ImageAnalysis ----------------> offline face/text/barcode analysis
CameraX SurfaceProcessor input -------> bounded renderer-owned GL textures
                                                |
                                    timestamped privacy decision
                                                |
                                                v
SANITIZED ONLY                    protected preview + H.264 encoder
                                                |
                                  bounded two-second packet queue
                                                |
                                  RTMP publisher -> MediaMTX -> WebRTC viewer
```

The renderer is the only bridge out of the raw zone. Preview and encoder surfaces are capability-
bound to it. Transport accepts only immutable H.264 access units attested as sanitized. The app has
no `RECORD_AUDIO` permission, microphone capture, audio encoder, audio packet type, or audio publish
API. Stream credentials are session-only mutable buffers, masked in the UI, excluded from saved
state, and zeroized on close.

Host selection is manual and ephemeral. LiveShield uses geometry and session-local tracking, not
face recognition or biometric embeddings. If host continuity becomes uncertain, the affected face
is protected and the user must explicitly select a fresh track; permission is never transferred
automatically.

The production app composes offline face/text/barcode lanes with session watchlists and fixed zones.
Renderer queue/recovery, thermal and scene state, plus typed publisher connection health drive a
payload-free private `LiveActivity`; unsafe states strengthen protection or stop publication. This
wiring is not evidence that the current OCR model detects supported text reliably.

## Modules

| Module | Responsibility |
|---|---|
| `privacy-domain` | Pure-Java policy, freshness, tracking continuity, configured zones, and fail-private state |
| `vision` | Offline YuNet/OpenCV face analysis, PaddleOCR/Paddle-Lite text analysis, ZXing barcode analysis, and structured-PII rules |
| `video-pipeline` | CameraX graph, bounded raw-frame rendezvous, OpenGL redaction, sanitized H.264 encoding, and decoded-output verification |
| `transport` | Sanitized access-unit boundary, exact delay queue, session-secret handling, and video-only RTMP publication |
| `app` | Scope disclosure, permission/setup flow, destination UI, lifecycle coordination, and honest health status |
| `benchmark` | Macrobenchmark entry points for later physical-device validation |
| `test-fixtures` | Deterministic generators, manifests, annotations, validators, and evaluation metrics |

## Build and local checks

Requirements are Android Studio/JDK 17, Android SDK 36 platform tools, and the checked-in Gradle
wrapper. The project compiles against SDK 37, targets SDK 36, and has minimum SDK 24. Current OCR
packaging deliberately supports `arm64-v8a` only.

```bash
./gradlew test lint
./gradlew checkPrivacyBoundaries checkstyleAll
python3 -m unittest discover tools/testdata/tests
```

Install the debug app on a compatible connected device with:

```bash
./gradlew :app:installDebug
```

The full validation workflow, including licensed public data and explicit external prerequisites,
is in [`specs/001-live-privacy-protection/quickstart.md`](specs/001-live-privacy-protection/quickstart.md).
Public evaluation media and any later consented raw recordings are intentionally not packaged in
the app or committed to Git.

## Controlled MediaMTX demo

Start the pinned local relay on a trusted development LAN:

```bash
docker compose -f dev/mediamtx/compose.yml up
```

In LiveShield, acknowledge the scope disclosure, grant camera permission, configure the controlled
MediaMTX destination, select the fresh host face, and wait for every readiness gate before starting.
The local demo endpoint is `rtmp://10.0.2.2:1935/liveshield` from the Android emulator; the WebRTC
viewer path is `http://localhost:8889/liveshield` on the development machine.

The production publication path has been exercised on an API 36 ARM64 emulator with the pinned
MediaMTX 1.15.5 relay: an independent probe found one H.264 video stream, 15 video packets, and zero
audio tracks or packets; a controlled WebRTC viewer reached playing state. See
[`docs/verification/us6-mediamtx.md`](docs/verification/us6-mediamtx.md). This is local controlled-
relay evidence, not a TikTok result or a glass-to-glass latency claim.

## Evidence and current limitations

Evidence is deliberately labeled by boundary: pure JVM contracts, synthetic renderer/codec tests,
API 36 emulator runs, public detector regression, and external user/device work are not treated as
interchangeable.

- Face redaction and fail-private fixture paths have real GPU, H.264 encode/decode, and forbidden-
  raw-pixel checks. The selected 200-image WIDER run is detector-regression evidence, not a general
  accuracy benchmark.
- Offline YuNet face inference and ZXing barcode decoding are device/JVM verified respectively.
- The app now packages the English PP-OCRv5 recognizer for session-only private words. Its
  API-24 source and release packaging gates pass, but it has not been run on a device or evaluated.
  Automatic email, phone, card, and OTP rules remain disabled. Earlier DEVELOPMENT evidence was
  QR 8/8, configured zones 32/32, and text/watchlists 0/32, so SC-002 and SC-009 remain unmet and
  HOLDOUT remains sealed. See
  [`docs/verification/t119-ocr-development.md`](docs/verification/t119-ocr-development.md).
- The current corpus contains 274 of the planned 286 records. The missing 12 are consented adult
  face clips that require the separately reviewed external capture protocol; no substitute data is
  fabricated.
- Physical-device endurance, thermal, battery, and microphone-indicator measurements were not
  collected and are no longer project task gates; emulator results do not provide that evidence.
- TikTok publication is unverified until a user-authorized test account exposes external RTMP
  credentials. LiveShield does not use a TikTok SDK and cannot bypass account eligibility.
- Accessibility labels, focus order, contrast, assertive live-region notification, and unchanged-
  state silence passed the requirement-aligned API 36 test.
- V1 protects visible video only. It does not capture audio and therefore cannot protect spoken
  information.
- The retained final-gate report records an older workspace snapshot and a benchmark that correctly
  rejected the emulator; physical benchmark completion was retired as a task, not counted as a pass.

The evidence index currently includes face results, fail-private decoded output, local RTMP/WebRTC,
dependency audits, and setup verification under [`docs/verification`](docs/verification). Unmet
criteria remain unmet rather than being inferred from adjacent tests.

## Data and licences

Runtime and test dependencies, licences, hashes, and privacy boundaries are recorded in
[`docs/DEPENDENCIES.md`](docs/DEPENDENCIES.md). Evaluation-data rules are in
[`specs/001-live-privacy-protection/test-data.md`](specs/001-live-privacy-protection/test-data.md).

- WIDER FACE: public regression subset retained locally under its non-commercial/no-derivatives
  terms; never packaged in the APK.
- BIV-Priv-Seg support images: retained locally with CC BY 4.0 attribution.
- YuNet: MIT-licensed model bundled for offline face detection.
- OpenCV, PaddleOCR/Paddle-Lite, ZXing, CameraX, and most AndroidX components: Apache 2.0.
- MediaMTX: MIT-licensed local relay.
- Synthetic renderer, fault, and Priority 2 fixtures contain no people, credentials, or real PII.

Consented evaluation recordings, if later authorized, remain in an encrypted external store with
access and deletion ledgers. Only opaque authorization references and safe derived annotations may
enter the repository.

## Design documentation

- [`specs/001-live-privacy-protection/plan.md`](specs/001-live-privacy-protection/plan.md)
- [`specs/001-live-privacy-protection/contracts/privacy-pipeline.md`](specs/001-live-privacy-protection/contracts/privacy-pipeline.md)
- [`specs/001-live-privacy-protection/contracts/stream-transport.md`](specs/001-live-privacy-protection/contracts/stream-transport.md)
- [`specs/001-live-privacy-protection/tasks.md`](specs/001-live-privacy-protection/tasks.md)
