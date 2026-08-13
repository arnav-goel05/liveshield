# Quickstart Validation Guide

This is the release-candidate validation sequence for LiveShield. Record the exact committed
revision and retain command output in `docs/verification/quickstart.md` when using this guide for a
release decision. A separate clean-checkout execution is recommended, but is not a project task or
acceptance gate.

The corpus contract is defined in [test-data.md](test-data.md), and dependency versions and privacy
boundaries are recorded in [DEPENDENCIES.md](../../docs/DEPENDENCIES.md).

Public stills are offline detector evidence. Synthetic fixtures are deterministic pipeline
evidence. Emulator runs are Android integration evidence. Only the planned physical-device and
consented-corpus work can support physical camera, performance, or temporal face-tracking claims.

## Prerequisites

- A committed Git revision that contains the repository.
- Android Studio JBR/JDK 17, Android SDK platform 37 for compilation, build-tools/platform-tools,
  and an API 36 ARM64 emulator. The app targets API 36 and supports API 23+.
- A physical ARM64 Android 6.0+ phone with a front camera and USB debugging for the device-only
  sections. Emulator results do not replace those sections.
- Python 3 using only the standard library for manifest tooling; `ffmpeg` and `ffprobe` for
  deterministic silent H.264 fixture generation.
- `curl`, `shasum` or `sha256sum`, ZIP support, and sufficient disk for the approximately 382 MiB
  pinned public archives plus selected local media.
- Docker Compose for the manual trusted-LAN relay, or the pinned local MediaMTX runner and its
  documented host tools for the API 36 transport integration.
- Encrypted access-controlled storage outside Git and the app, reviewed consent materials, and
  consenting adults for the later capture steps. Do not capture incidental bystanders, minors, or
  vulnerable people.
- Optional: an eligible TikTok test account that displays an RTMP server and stream key.

## 0. Record repository provenance

Record the revision and whether the working tree contains local changes:

```bash
git rev-parse HEAD
git status --short --branch
```

Record the operating system, Java, Android SDK, Python, FFmpeg, Docker/MediaMTX, device, and
emulator versions before testing. If the tree is not clean, identify the local changes so the
evidence is not misattributed to the committed revision.

## 1. Prepare and validate the safe fixture packs

Run the host tooling tests first:

```bash
python3 -m unittest discover -s tools/testdata/tests -p 'test_*.py'
```

Generate the 12 renderer, 20 fault-injection, and 26 fictional Priority 2 clips. These commands
require `ffmpeg` and `ffprobe` and write only silent H.264 fixtures:

```bash
python3 tools/testdata/generate_system_fixtures.py --repo .
python3 tools/testdata/generate_priority2_fixtures.py --repo .
python3 tools/testdata/validate_manifest.py \
  test-fixtures/manifests/system-v1.jsonl \
  --media-root . --truth-root . --profile system-v1 --expected-count 32
python3 tools/testdata/validate_manifest.py \
  test-fixtures/manifests/pii-v1.jsonl \
  --media-root . --truth-root . --profile pii-v1 --expected-count 26
```

Fetch all four pinned public inputs explicitly. Downloads are never Gradle build hooks:

```bash
tools/testdata/fetch-public-data.sh \
  --asset wider-val --asset wider-annotations \
  --asset biv-support-images --asset biv-support-json
python3 -m zipfile -e \
  evaluation-data/public/archives/wider_face_split.zip \
  evaluation-data/public/wider-annotations-archive
python3 tools/testdata/prepare_public_manifest.py \
  --wider-images-archive evaluation-data/public/archives/WIDER_val.zip \
  --wider-annotations \
    evaluation-data/public/wider-annotations-archive/wider_face_split/wider_face_val_bbx_gt.txt \
  --biv-images-archive evaluation-data/public/archives/support_images.zip \
  --biv-annotations evaluation-data/public/archives/support_set.json \
  --media-root evaluation-data/public/media \
  --truth-root test-fixtures/annotations/public-v1 \
  --manifest-output test-fixtures/manifests/public-v1.jsonl
python3 tools/testdata/validate_manifest.py \
  test-fixtures/manifests/public-v1.jsonl \
  --media-root . --truth-root . --profile public-v1 --expected-count 216
```

WIDER FACE remains local and unmodified under its non-commercial/no-derivatives boundary.
BIV-Priv-Seg attribution remains in `test-fixtures/manifests/BIV_PRIV_SEG_ATTRIBUTION.md`. Neither
public dataset is packaged in the app or evidence of live tracking, CameraX timing, or fail-private
encoded output.

The complete initial profile also requires the 12 authorized face clips from T106 and their T107
truth. Only after those exist, build a local aggregate and validate all 286 records:

```bash
cat test-fixtures/manifests/public-v1.jsonl \
    test-fixtures/manifests/system-v1.jsonl \
    test-fixtures/manifests/pii-v1.jsonl \
    test-fixtures/manifests/face-v1.jsonl \
  > evaluation-data/full-v1.jsonl
python3 tools/testdata/validate_manifest.py evaluation-data/full-v1.jsonl \
  --media-root . --truth-root . --profile full-v1 --expected-count 286
```

Do not create a placeholder `face-v1.jsonl`; an absent authorized corpus is a blocked gate.

## 2. Run host build and privacy gates

```bash
./gradlew test checkstyleAll checkPrivacyBoundaries \
  :app:lintDebug :app:lintRelease \
  :vision:lintDebug :vision:lintRelease \
  :video-pipeline:lintDebug :video-pipeline:lintRelease \
  :transport:lintDebug :transport:lintRelease \
  :app:assembleDebug :app:assembleRelease
```

Retain the complete terminal output, JUnit XML, lint reports, privacy-boundary output, generated
debug/release manifests, and APK hashes. A source-only pass does not prove camera, GPU, codec,
detector-native, or RTMP runtime behavior.

## 3. Run Android integration gates

Use an API 36 ARM64 emulator for the current connected suites:

```bash
./gradlew \
  :app:connectedDebugAndroidTest \
  :vision:connectedDebugAndroidTest \
  :video-pipeline:connectedDebugAndroidTest \
  :transport:connectedDebugAndroidTest
```

Public WIDER data is staged by its bounded runner after the vision test APK is built:

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew :vision:assembleDebugAndroidTest
ANDROID_SERIAL=emulator-5554 tools/testdata/run-wider-regression.sh
```

The BIV and Priority 2 device tests require their manifest, truth, and local media paths to be
staged and passed as instrumentation arguments. No single repository runner currently performs
that staging, so a complete clean-checkout execution must either record the exact manual `adb`
commands or add a reviewed bounded runner before claiming these gates.

Run the pinned MediaMTX API 36 integration separately:

```bash
ANDROID_SERIAL=emulator-5554 \
LIVESHIELD_RTMP_API36_INTEGRATION=true \
ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" \
tools/mediamtx/run-api36-rtmp-integration.sh
```

This proves the controlled synthetic H.264 path through MediaMTX. It does not prove TikTok ingest,
a physical camera, internet behavior, or glass-to-glass latency.

## 4. Run the controlled LAN demonstration

On a trusted LAN only:

```bash
LIVESHIELD_LAN_IP=192.0.2.10 \
  docker compose -f dev/mediamtx/compose.yml up
./gradlew :app:installDebug
```

Replace the documentation-only address with the laptop's trusted-LAN address. Configure
`rtmp://<LAPTOP_LAN_IP>:1935/liveshield` and view
`http://<LAPTOP_LAN_IP>:8889/liveshield`. The pinned configuration is anonymous development-only,
limits access to `liveshield`, and disables recording by default. Do not expose it to an untrusted
network.

In an owned indoor room, acknowledge scope, configure only fictional watchlist values and owned
privacy zones, select a fresh host face, and start only when the production readiness callbacks
enable Start. Exercise consenting-person entry/exit, host loss/reselection, synthetic Priority 2
cards, lifecycle changes, and debug-only fault controls. Retain decoded viewer evidence and confirm
that every unsafe case yields sanitized video, full shield, a drop, or a stopped stream—never an
untreated frame. Keep creator controls outside the encoded surface.

## 5. Run physical-device and consented-corpus gates

Run macrobenchmarks only on identified physical ARM64 devices:

```bash
./gradlew :benchmark:connectedCheck
```

Long-duration physical-device performance, thermal, battery, and microphone-indicator measurements
have not been collected; do not infer them from emulator results.

T106/T107 then require the reviewed adults-only consent protocol, encrypted external storage,
access/deletion ledger, 12 authorized silent face clips, per-frame annotations, and decoded-output
temporal metrics. Raw participant media must never enter Git or the app. T108 is a later growth
gate and is not part of the 286-record initial-corpus pass.

## 6. Optional TikTok gate

Run this only if a test account visibly exposes an RTMP server and stream key. Record only
`available` or `unavailable` for T085; never retain credentials. If available, use the masked
session-only destination fields, publish the already-sanitized silent video, observe it from a
separate viewer account, and audit logs, screenshots, saved state, and settings after shutdown.

If credentials are unavailable, keep T086 explicitly unverified and use MediaMTX. Do not start a
second camera LIVE in TikTok, claim that LiveShield intercepts the TikTok camera, or infer TikTok
acceptance from a MediaMTX pass.

## 7. Completion record

For every section, record the exact revision, commands, exit codes, test counts, artifact hashes,
device/tool versions, and evidence boundary in `docs/verification/quickstart.md`. Link the separate
corpus, [privacy](../../docs/verification/privacy-audit.md),
[MediaMTX](../../docs/verification/us6-mediamtx.md), usability, consented-corpus, and
optional TikTok reports.
List external or human gates as blocked rather than silently skipping them.
