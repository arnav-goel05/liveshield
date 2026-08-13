# LiveShield convergence record

**Assessment date:** 2026-08-13  
**Status:** local convergence complete; project completion is blocked by the open items below  
**Intent sources:** [specification](spec.md), [plan](plan.md), [tasks](tasks.md), and
[constitution](../../.specify/memory/constitution.md)

## Result

The fresh code/evidence comparison found and resolved the local production-composition drift that
was absent from the original implementation phases. No constitutional requirement was weakened.
The repository is not complete: T119 reached a formally stopped unsupported boundary, T115
requires a physical benchmark run, and the user/external actions listed below remain open.

Evidence boundaries remain distinct. Source/JVM, emulator, synthetic decoded output, public still
regression, physical-device, human usability, consented-corpus, MediaMTX, and optional TikTok
evidence cannot substitute for one another. LiveShield remains a bounded prototype, not an
anonymity or universal-detection guarantee.

## Completed local remediation

| Item | Original convergence finding | Current implementation/evidence | Result |
|---|---|---|---|
| T118 | Creator-facing watchlist and fixed-zone controls were missing | `SetupActivity`, `PrivacyZoneEditorView`, and `IndoorPrivacySetupController` now provide disclosure-gated add/remove/draw/edit controls, bounds, transform-safety, accessibility, and lifecycle clearing | Resolved locally and API 36 verified |
| T120 | Production live-session state and Stop were not bound to the private live UI | `ProductionLiveSessionUi`, `LiveSessionUiRegistry`, and `LiveActivity` now carry payload-free state and an idempotent safe-stop request without media ownership | Resolved locally and API 36 verified |
| T121 | Production health used incomplete/hardcoded safety inputs | `ProductionSafetyHealth` composes renderer raw-queue/recovery, real thermal, and scene state into `VisionScheduler`, `LiveSessionCoordinator`, fail-private policy, and private status | Resolved locally and API 36 typed-composition verified; real induced thermal remains T105 |
| T122 | Publisher failures were not proactively reflected in coordinator/private status | Typed asynchronous RTMP/controller/port health now reports connection, authentication, network, congestion, queue, fresh epoch, and terminal failure without endpoint or secret payloads | Resolved locally and API 36 private-UI verified; real TikTok remains conditional |
| T115 benchmark build remediation | Root `connectedCheck` originally selected a debuggable, non-self-instrumenting benchmark and then exposed an unsigned custom test APK | The target is release-derived/nondebuggable, the test APK self-instruments and is signed, both APKs pass `apksigner`, and no benchmark error is suppressed | Build/package defects resolved; physical execution still blocked |
| Evidence-document drift | Checked reports said Priority 2/live composition or completed evidence did not exist | README, case study, privacy audit, acceptance matrix, and final-gate report now state the measured boundary and keep unmet claims unmet | Resolved locally |

The exact current benchmark status is recorded in the
[final-gate report](../../docs/verification/final-gate.md). The signed benchmark pair reaches both
methods on API 36 and fails solely with unsuppressed `EMULATOR`. This is the intended physical-device
gate, not performance evidence.

## T119 terminal investigation boundary

### T119 host path

The terminal host investigation converted the English v5 model to ONNX and passed host-side
conversion/parity checks. This is model-conversion feasibility evidence only. The attempted custom
API 23 ARM64 ONNX Runtime build stopped at the missing `pkg-config` prerequisite, before native
compilation or AAR packaging. Consequently there is no Android runtime artifact, runtime gate,
DEVELOPMENT evaluation, decoded-output evaluation, or HOLDOUT evaluation for that candidate.

The frozen production/evaluation boundary remains the last complete v3 result recorded by
[T119 OCR DEVELOPMENT](../../docs/verification/t119-ocr-development.md):

- Noto v2 DEVELOPMENT: QR 8/8 and configured zones 32/32;
- automatic text 0/32 and configured watchlists 0/32, therefore unsupported;
- failed PP-OCRv5 and CRNN candidates, and the host-only v5 ONNX candidate, have no Android
  accuracy result;
- HOLDOUT remains sealed; and
- SC-002 and SC-009 remain unmet.

No more OCR device work is authorized in the current scope. T119 therefore remains open because
its success criterion is unsupported; host parity cannot substitute for source/package/runtime,
104/104 DEVELOPMENT, decoded-output, or one-shot HOLDOUT evidence.

## Open blockers

| Tasks | Owner/boundary | Why still open |
|---|---|---|
| T044 | User + physical device | Independent protected-start evidence requires a real front-camera phone |
| T071–T072 | User + human participants | At least ten preregistered first-time-user sessions and an honest de-identified report do not exist |
| T085–T086 | User + optional TikTok account | Eligibility must be observed on an authorized test account; credentials may be unavailable |
| T105 | User + physical device tiers | Required 30-minute thermal, battery, memory, latency, microphone-indicator, and zero-bypass sessions do not exist |
| T106, T107, T108 | User + consented adults/external encrypted store | Capture is not authorized; the 12 clips, their annotations/evaluation, and later scaled face corpus do not exist |
| T110 | Downstream corpus/evidence | Full 286-record validation is blocked by the missing 12 authorized face records and pending final evidence |
| T115 | User + physical ARM64 device | All current non-benchmark connected modules are green/expected-skip, but macrobenchmark correctly rejects the emulator |
| T119 | Frozen unsupported success criterion | Host v5 conversion/parity passed, but the API 23 ARM64 runtime build stopped before compilation/AAR; there is no Android accuracy, decoded-output, or HOLDOUT result |

The repository-safe deletion audit has a zero consented-record denominator; it does not imply an
external encrypted store was inspected. See the
[deletion audit](../../docs/verification/evaluation-data-deletion.md).

## External action checklist draft

### Physical-device evidence — T044, T105, T115

- [ ] Provide identified compatible physical ARM64 phones with front cameras and USB debugging;
  record model/tier, API level, build identifier, lens, resolution, FPS, and test date without
  account or location identifiers.
- [ ] Run the current benchmark-inclusive gate without suppression:

  ```bash
  ./gradlew test lint connectedCheck --continue
  ```

  Require both macrobenchmark methods to pass on a physical device. Retain terminal XML, reports,
  command output, APK hashes, and device facts. Never add `androidx.benchmark.suppressErrors`.
- [ ] Run the staged physical protected-start test for T044 and decode the sanitized output. Record
  first protectable frame, treatment, video-only tracks, and zero raw bypass in
  `docs/verification/us1-protected-start.md`; do not retain raw camera media in Git.
- [ ] Run the preregistered 30-minute T105 sessions on the required tiers. Record latency
  percentiles, memory, battery, thermal transitions, drops, shields, queue bounds, and microphone
  indicator. Emulator results cannot substitute.

### Usability — T071–T072

- [ ] Recruit at least ten eligible first-time adult participants under the frozen
  [solo-indoor protocol](../../docs/testing/solo-indoor-usability.md); do not replace failures,
  timeouts, assisted sessions, withdrawals, or product-blocked steps.
- [ ] Use a controlled room, supplied fictional values, controlled destination, and consenting
  staff/generated fixtures only. Capture no participant video, screen recording, audio, facial
  imagery, real PII, account, stream key, or free-form biography.
- [ ] Store only random study IDs, fixed outcome/assistance codes, durations, comprehension choices,
  and missing/withdrawal status in the gitignored
  `evaluation-data/usability/solo-indoor-v1.csv`.
- [ ] Report exact numerators/denominators, timing distribution, destination completion, health-state
  comprehension, assistance, withdrawal, and missingness in `docs/verification/us5-usability.md`.

### Consented corpus — T106–T108, T110

- [ ] Obtain an explicit device-validation go decision and prepare the approved encrypted external
  store, separate authorization mapping, access ledger, absolute deletion deadlines, and withdrawal
  route under the [capture protocol](../../docs/testing/consented-capture-protocol.md).
- [ ] Capture only freely consenting adults in an owned controlled room. Exclude incidental people,
  minors, vulnerable/coerced participants, real private props, microphone audio, uploads, cloud
  sync, and raw media in Git/app/build artifacts.
- [ ] Verify every accepted clip has exactly one video stream and zero audio streams:

  ```bash
  ffprobe -v error -show_entries stream=index,codec_type -of csv=p=0 INPUT.mp4
  ```

- [ ] Add only opaque authorization/storage/access references to `face-v1.jsonl`; annotate
  per-frame polygons, session-local track IDs, roles, transforms, and protectable timestamps. Never
  create identity labels, face crops, embeddings, demographics, or recognized-text artifacts.
- [ ] After the 12 authorized records exist, validate the 286-record aggregate:

  ```bash
  cat test-fixtures/manifests/public-v1.jsonl \
      test-fixtures/manifests/system-v1.jsonl \
      test-fixtures/manifests/pii-v1.jsonl \
      test-fixtures/manifests/face-v1.jsonl \
    > evaluation-data/full-v1.jsonl
  python3 tools/testdata/validate_manifest.py evaluation-data/full-v1.jsonl \
    --media-root . --truth-root . --profile full-v1 --expected-count 286
  ```

- [ ] Expand to T108 scale only after the initial controlled corpus is valid; keep actors,
  room/motion combinations, adjacent frames, payloads, and generator seeds split-isolated. Re-audit
  access and deletion at every protocol trigger.

### Optional TikTok eligibility — T085–T086

- [ ] On an explicitly authorized test account, inspect whether TikTok exposes an RTMP server and
  stream key. Record only `available` or `unavailable` in
  `docs/verification/tiktok-access.md`; never copy credentials into Git, chat, logs, screenshots,
  saved state, or evidence.
- [ ] If unavailable, keep T086 unverified and use the controlled MediaMTX demo. Do not claim the app
  can intercept TikTok's camera or bypass eligibility.
- [ ] If available, enter credentials only through masked session-scoped fields, publish silent
  sanitized video, observe from a separate viewer, stop safely, and audit logs/private state. Record
  viewer evidence without endpoint or secret in `docs/verification/us6-tiktok.md`.

## Closeout rule

The non-mutating link, task, and claim consistency review found no additional local actionable gap.
Every remaining unmet item is either an explicit open task above or an honestly bounded success
criterion in the [acceptance matrix](../../docs/verification/acceptance-matrix.md). T117 convergence
can close while T115, T119, and external implementation/evidence tasks remain open; project
completion cannot.
