# Implementation Plan: Live Privacy Protection

**Branch**: `001-live-privacy-protection` | **Date**: 2026-08-13 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/001-live-privacy-protection/spec.md`

## Summary

Build a Java-first Android app and reusable privacy pipeline for a solo indoor creator. CameraX
provides camera lifecycle, raw analysis frames, and downstream preview/video surfaces. A single
custom OpenGL `SurfaceProcessor` holds a short, bounded texture queue, joins each timestamp to an
explicit privacy decision, applies regional blur or a full-frame shield, and is the only route to
both preview and encoding. A custom CameraX `VideoOutput` feeds sanitized frames into a hardware
H.264 encoder. Sanitized H.264 video units receive the intentional two-second LIVE delay and publish
through a destination-neutral RTMP adapter. A pinned local MediaMTX instance and browser viewer
provide the always-available verification target. When an eligible test account supplies a TikTok
RTMP server and stream key, the same sanitized publisher targets TikTok directly; LiveShield
remains the broadcaster and never attempts to inject a virtual camera into TikTok's Android app.

Bundled on-device detectors cover faces, OCR, and barcodes. Structured validators cover emails,
phones, payment-card-like numbers, and contextual verification codes. Ambiguous names, schools,
employers, and addresses use an exact session-local watchlist. Documents, badges, parcels, and
screens use creator-defined privacy zones whose complete active area is protected. V1 does not
claim universal semantic understanding of those objects.

## Technical Context

**Language/Version**: Java 17 for application, domain, pipeline, and tests; Groovy Gradle scripts;
Kotlin only inside third-party dependencies

**Primary Dependencies**: Android Jetpack/Views; CameraX 1.6.1; audited fully offline OpenCV/YuNet
face detection, PaddleOCR/Paddle-Lite text recognition, and ZXing barcode scanning; Google
libphonenumber; Android OpenGL ES 3.0, MediaCodec,
MediaMuxer/MediaExtractor; RootEncoder RTMP 2.8.0 low-level client; local pinned MediaMTX

**Storage**: No runtime raw video persistence; in-memory bounded raw texture and sanitized packet queues;
session-local watchlist/zones in memory; optional sanitized diagnostic recording in app cache for
explicit test builds only; non-sensitive JSON session metrics; stream secrets session-only and
excluded from logs/metrics by default; later, separately authorized consented evaluation captures
remain encrypted/access-controlled outside Git, carry deletion deadlines and audits, and are never
packaged in the app

**Testing**: JUnit 4 JVM tests, AndroidX Test/JUnit/Espresso, CameraX testing fakes, deterministic
OpenGL instrumentation tests, encoded-output decode inspection, Macrobenchmark and Perfetto on
physical devices; initial 286-item corpus comprising 200 WIDER FACE images, 16 BIV-Priv-Seg support
images, 12 deterministic renderer clips, 12 consented face-tracking clips, 26 synthetic
Priority 2 appearances, and 20 fault-injection clips; public subsets are detector-only evidence

**Target Platform**: Android 6.0+ (`minSdk 23`), compile SDK 37 and target SDK 36 initially; compile
SDK 37 is required by the pinned RootEncoder RTMP 2.8.0 artifact and does not change V1 runtime
behavior or the target-SDK contract; portrait-first
720p/30 fps controlled indoor sessions on physical Android phones

**Project Type**: Multi-module Android application plus reusable Android libraries and local test
stream infrastructure

**Performance Goals**: 30 fps sanitized output where supported; face analysis target 10–15
completed results/s; OCR 1–2/s plus scene-change bursts; barcode 3–5/s; no raw frame in encoded
output; configured two-second sanitized packet delay plus measured transport overhead

**Constraints**: All privacy analysis on-device; exactly one creator-selected host; no biometric
identity; raw gate is memory-bounded; every output timestamp receives an explicit safe decision;
missing/stale/error decisions render a shield; app UI warnings never enter viewer output; V1 is
video-only and does not request microphone permission or capture, encode, retain, or transmit audio

**Scale/Scope**: One Android device, one host, controlled indoor scene, initial cap of four
simultaneous face tracks, Latin OCR, one RTMP publisher, one local browser viewer, optional one
eligible TikTok test destination, and 30-minute sustained validation runs

## Constitution Check

*GATE: Passed before research and re-checked after Phase 1 design.*

| Constitutional gate | Plan evidence | Result |
|---|---|---|
| Privacy outranks continuity | Missing/stale/error decisions select regional or full-frame protection; no raw bypass surface exists | PASS |
| On-device processing and controlled evaluation data | ML inference and policy run locally; runtime raw frames exist only in a bounded GL texture pool; later consented evaluation captures follow the encrypted, access-controlled, deletion-bound exception | PASS |
| Manual, ephemeral host selection | Host permission binds to one session track; no embeddings or automatic identity transfer | PASS |
| Automatic and configured visual protection | Structured PII is validated automatically; ambiguous categories use session-scoped watchlists and creator-defined zones | PASS |
| Reusable Java-first architecture | Privacy domain, vision, video, and transport are separate Java modules behind contracts | PASS |
| Protective treatment gate | Strong mosaic/opaque is the initial certified treatment; blur remains disabled until a decoded-output strength gate passes and otherwise escalates | PASS |
| Evidence-based completion | JVM, GPU, encoded-output, failure-injection, physical-device, latency, memory, thermal, and battery gates are planned | PASS |
| Official/auditable dependencies | Android APIs plus source-auditable, locally packaged vision runtimes/models are primary; RootEncoder and MediaMTX are pinned, isolated, and licence-reviewed; TikTok uses only creator-issued external-stream credentials | PASS after offline vision replacement and release-graph/egress verification |

### Data flow and trust boundaries

```text
UNTRUSTED RAW ZONE (device memory only)
Camera sensor
  ├── ImageAnalysis -> detector adapters -> timestamped findings
  └── SurfaceProcessor input -> bounded raw GL texture pool
                                  + PrivacyPolicyEngine
                                  -> RedactionRenderer

SANITIZED ZONE
Processed Preview + custom VideoOutput
  -> MediaCodec H.264
  -> bounded sanitized video-unit delay
  -> destination-neutral RTMP publisher
       ├── local MediaMTX -> browser WebRTC viewer
       └── eligible TikTok RTMP ingest -> TikTok LIVE viewers
```

The custom renderer is the sole bridge from the raw zone to the sanitized zone. No raw camera
surface may connect directly to the encoder, transport, recorder, analytics, logs, or screenshots.

### Post-design re-check

Phase 1 interfaces make the privacy boundary explicit: `FrameReleaseDecision` defaults to
`FULL_SHIELD`, `SanitizedVideoOutput` accepts only renderer-owned surfaces, and `StreamPublisher`
accepts only encoded video units marked sanitized. The controlled evaluation-recording exception is
isolated outside the application, begins only during device validation, and cannot affect runtime
data flow.

## Project Structure

### Documentation (this feature)

```text
specs/001-live-privacy-protection/
├── plan.md
├── research.md
├── data-model.md
├── test-data.md
├── quickstart.md
├── contracts/
│   ├── privacy-pipeline.md
│   └── stream-transport.md
└── tasks.md                  # Created later by $speckit-tasks
```

### Source Code (repository root)

```text
app/
├── src/main/java/.../app/           # Activities, Views, permissions, lifecycle
├── src/main/res/                    # XML layouts, strings, drawable assets
└── src/androidTest/                 # End-to-end UI/session tests

privacy-domain/
├── src/main/java/.../privacy/       # Pure Java policy, tracks, decisions, health state
└── src/test/                        # Deterministic JVM state-machine tests

vision/
├── src/main/java/.../vision/        # Face/OCR/barcode adapters, validators, watchlist
├── src/test/                        # Text validators and association tests
└── src/androidTest/                 # Detector integration tests

video-pipeline/
├── src/main/java/.../video/         # CameraX binding, GL queue/renderer, video codec
├── src/test/                        # Timestamp joins and bounded queue tests
└── src/androidTest/                 # GPU and encoded-output tests

transport/
├── src/main/java/.../transport/     # Access units, delay queue, RTMP adapter, secret handling
└── src/test/                        # Ordering, delay, reconnect and congestion tests

benchmark/
└── src/main/java/.../benchmark/     # Macrobenchmark and sustained-session scenarios

test-fixtures/
├── src/main/                        # Deterministic generators and committed non-human fixtures
├── manifests/                       # Versioned fixture/provenance/split manifests
├── annotations/                     # Per-frame expected regions and privacy states
└── README.md                        # Corpus creation, consent, licences, and evaluation boundaries

tools/testdata/
├── fetch-public-data.sh             # Explicit licence-aware archive fetch; never runs in normal build
├── select_wider_subset.py           # Fixed-seed five-slice WIDER manifest selection
├── prepare_biv_support.py           # Verify 16 support images and attribution
└── validate_manifest.py             # Hash, provenance, count, and split-leakage checks

evaluation-data/                     # Gitignored media; never packaged in the APK
├── consented/                       # Opaque auth refs and derived results; raw captures live in approved encrypted external storage
├── public/                          # Licensed detector-regression subsets
└── outputs/                         # Decoded sanitized evidence and metric reports

dev/mediamtx/
├── mediamtx.yml                     # LAN-only local demo configuration
└── README.md                        # Pinned server setup and browser viewer instructions
```

**Structure Decision**: Five production modules preserve the reusable pipeline boundary without
splitting every class into a separate library. `privacy-domain` has no Android dependency;
`vision`, `video-pipeline`, and `transport` depend inward on contracts rather than on the app.

## Phase 0 Research Decisions

See [research.md](research.md). All architecture unknowns are resolved. Exact detector thresholds,
raw queue depth, and bitrate are benchmark-derived configuration values, not unresolved design
questions.

## Phase 1 Design Outputs

- [data-model.md](data-model.md): session, track, finding, decision, health, queue, and metric models
- [privacy-pipeline.md](contracts/privacy-pipeline.md): timestamped fail-private module contracts
- [stream-transport.md](contracts/stream-transport.md): sanitized encoder/packet/publisher contract
- [quickstart.md](quickstart.md): runnable end-to-end validation guide
- [test-data.md](test-data.md): initial corpus inventory, splits, annotation rules, and growth gates

## Complexity Tracking

| Complexity | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| Custom OpenGL `SurfaceProcessor` | Timestamped true regional blur, bounded raw-frame ownership, shared sanitized preview/encode path | Canvas overlay cannot sample underlying pixels for blur; immediate Media3 effects do not provide the required analysis rendezvous queue |
| Custom CameraX `VideoOutput` | Obtain sanitized H.264 access units for a network publisher | CameraX `Recorder` writes files and does not expose a live transport contract |
| Separate RTMP dependency and local media server | Verify the complete viewer path without depending on TikTok account eligibility, while keeping the same publisher usable with creator-issued TikTok RTMP details | Native WebRTC ingest would dominate V1 and obscure the privacy-pipeline work |
