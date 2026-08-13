---

description: "Dependency-ordered implementation tasks for LiveShield"
---

# Tasks: Live Privacy Protection

**Input**: Design documents from `specs/001-live-privacy-protection/`

**Prerequisites**: `plan.md`, `spec.md`, `research.md`, `data-model.md`, `test-data.md`,
`contracts/privacy-pipeline.md`, `contracts/stream-transport.md`, `quickstart.md`

**Tests**: Tests are mandatory because the constitution requires automated healthy, uncertain,
unsafe, encoded-output, failure-path, and physical-device evidence.

**Organization**: Tasks are grouped by user story. Within each story, test tasks precede the
implementation they verify. Public data is detector-only evidence and is never used to claim
end-to-end fail-private behavior.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel with adjacent tasks after its stated prerequisites are complete
- **[Story]**: User story from `spec.md`
- Tasks whose descriptions begin with **User action:** require a physical device, participant, or account action from the user

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Create the Java-first Android project, reproducible dependency baseline, and safe test-data workspace.

- [x] T001 Create the Gradle multi-module skeleton for `app/`, `privacy-domain/`, `vision/`, `video-pipeline/`, `transport/`, `benchmark/`, and `test-fixtures/` in `settings.gradle` and each module's `build.gradle`
- [x] T002 Configure Java 17, compile SDK 37, target SDK 36, minimum SDK 23, dependency repositories, and centralized version constants in `build.gradle` and `gradle.properties`
- [x] T003 [P] Add Android application manifest with camera/network permissions, no microphone permission, and debug-only test flags in `app/src/main/AndroidManifest.xml` and `app/src/debug/AndroidManifest.xml`
- [x] T004 [P] Add CameraX 1.6.1, audited offline vision runtimes/models, AndroidX Test, JUnit, libphonenumber, RootEncoder 2.8.0, and benchmark dependencies to the owning module `build.gradle` files
- [x] T005 [P] Configure Checkstyle, Android Lint, unit-test defaults, and connected-test reporting in `config/checkstyle/checkstyle.xml` and root `build.gradle`
- [x] T006 Create repository exclusions for `evaluation-data/`, downloaded archives, decoded outputs, secrets, local SDK files, and consent records in `.gitignore`
- [x] T007 [P] Document each third-party dependency and local model's version or digest, licence, provenance, purpose, and privacy boundary in `docs/DEPENDENCIES.md`
- [x] T008 [P] Create the local MediaMTX development configuration and pinned container definition in `dev/mediamtx/mediamtx.yml` and `dev/mediamtx/compose.yml`
- [x] T009 [P] Create safe placeholder resources and private-status strings without anonymity guarantees in `app/src/main/res/values/strings.xml` and `app/src/main/res/drawable/`
- [x] T010 Verify a clean desktop build with `./gradlew test lint` and record the initial result in `docs/verification/setup.md`

---

## Phase 2: Foundational Safety and Test Data (Blocking Prerequisites)

**Purpose**: Establish immutable privacy contracts, fixture provenance, and test utilities required by every story.

**Critical**: No user-story implementation starts until the default-shield domain model and test-data validation are passing.

- [x] T011 Define immutable geometry, timestamp, protected-region, detector-snapshot, and typed-failure value objects in `privacy-domain/src/main/java/com/liveshield/privacy/model/`
- [x] T012 [P] Define `LiveSession`, `SessionHealth`, and legal state transitions from `data-model.md` in `privacy-domain/src/main/java/com/liveshield/privacy/session/`
- [x] T013 [P] Define `FramePrivacyDecision` with an unconditional `FULL_SHIELD` default in `privacy-domain/src/main/java/com/liveshield/privacy/decision/FramePrivacyDecision.java`
- [x] T014 [P] Define Java interfaces for `VisionAnalyzer`, `HostSelectionController`, `PrivacyPolicyEngine`, `FrameDecisionStore`, `BufferedFrameProcessor`, `RedactionRenderer`, and `SafetyTelemetry` in their owning module `src/main/java/` contract packages
- [x] T015 Write failing JVM tests for missing, stale, future, expired, and evicted decision lookup in `privacy-domain/src/test/java/com/liveshield/privacy/decision/FrameDecisionStoreTest.java`
- [x] T016 Implement the bounded timestamp-ordered decision store with shield-on-miss semantics in `privacy-domain/src/main/java/com/liveshield/privacy/decision/BoundedFrameDecisionStore.java`
- [x] T017 [P] Define the versioned fixture-manifest and per-frame JSONL schemas in `test-fixtures/schema/fixture-manifest.schema.json` and `test-fixtures/schema/frame-truth.schema.json`
- [x] T018 [P] Implement archive retrieval with explicit URLs, byte-length/hash verification, and no automatic build hook in `tools/testdata/fetch-public-data.sh`
- [x] T019 Implement the fixed-hash five-slice 200-image WIDER FACE selector without modifying source media in `tools/testdata/select_wider_subset.py`
- [x] T020 [P] Implement BIV-Priv-Seg support-set hash verification, annotation conversion, and CC BY 4.0 attribution output in `tools/testdata/prepare_biv_support.py`
- [x] T021 Implement fixture count, digest, provenance, split-leakage, forbidden-PII-field, and expected-outcome validation in `tools/testdata/validate_manifest.py`
- [x] T022 [P] Add automated tests for WIDER selection determinism, BIV attribution, manifest rejection, and actor/payload/seed leakage in `tools/testdata/tests/`
- [x] T023 [P] Extend the fixture schema and validator so every `CONSENTED_CAPTURE` requires an opaque authorization reference, fictional-payload verification, encrypted-storage/access references, a deletion deadline, and deletion-audit status in `test-fixtures/schema/fixture-manifest.schema.json` and `tools/testdata/validate_manifest.py`
- [x] T024 Run the explicit public-data preparation workflow, generate the 216-item regression/smoke manifest in `test-fixtures/manifests/public-v1.jsonl`, and keep downloaded media under `evaluation-data/public/`
- [x] T025 Create a deterministic sentinel-frame and synthetic-region generator in `test-fixtures/src/main/java/com/liveshield/fixtures/SentinelFixtureGenerator.java`
- [x] T026 Validate all foundational JVM and test-data checks with `./gradlew :privacy-domain:test` and `python3 -m unittest discover tools/testdata/tests`, recording results in `docs/verification/foundation.md`

**Checkpoint**: The project builds; privacy decisions default to shield; public datasets are reproducibly selected and licence-bounded; fixture manifests reject unsafe or leaking data.

---

## Phase 3: User Story 1 - Begin a Protected LIVE (Priority: P1) — MVP

**Goal**: Preview the camera, manually select one fresh host track, and begin a sanitized local session in which only that track may remain visible.

**Independent Test**: On a staged scene with one host and one additional face, the session cannot start before selection/readiness; after selection, the host is visible, the other region is protected, and the decoded local output contains no raw pre-readiness frame.

### Tests for User Story 1

- [x] T027 [P] [US1] Write failing host-selection tests for no face, stale face, multiple candidates, explicit tap, session-only permission, and reset in `privacy-domain/src/test/java/com/liveshield/privacy/host/HostSelectionControllerTest.java`
- [x] T028 [P] [US1] Write failing session-readiness transition tests in `privacy-domain/src/test/java/com/liveshield/privacy/session/LiveSessionStateMachineTest.java`
- [x] T029 [P] [US1] Create CameraX binding instrumentation tests with fake surfaces and deterministic synchronization in `video-pipeline/src/androidTest/java/com/liveshield/video/CameraBindingTest.java`
- [x] T030 [P] [US1] Create GPU pixel tests for one visible region, one protected region, full shield, crop, rotation, and mirror in `video-pipeline/src/androidTest/java/com/liveshield/video/RedactionRendererTest.java`
- [x] T031 [P] [US1] Write failing offline face-analysis tests for one/multiple faces, timestamps, transform metadata, cancellation, guaranteed input closure, model-load failure, and session-only tracking hints in `vision/src/androidTest/java/com/liveshield/vision/face/OfflineFaceAnalyzerTest.java`

### Implementation for User Story 1

- [x] T032 [US1] Implement manual fresh-track selection and explicit permission revocation in `privacy-domain/src/main/java/com/liveshield/privacy/host/DefaultHostSelectionController.java`
- [x] T033 [US1] Implement setup/readiness/start/stop transitions that block output before host selection in `privacy-domain/src/main/java/com/liveshield/privacy/session/LiveSessionStateMachine.java`
- [x] T034 [P] [US1] Implement CameraX `Preview`, `ImageAnalysis`, and `VideoCapture` lifecycle binding in `video-pipeline/src/main/java/com/liveshield/video/camera/CameraSessionController.java`
- [x] T035 [P] [US1] Implement sensor/buffer/output coordinate transforms including rotation, crop, and mirroring in `video-pipeline/src/main/java/com/liveshield/video/geometry/FrameTransform.java`
- [x] T036 [US1] Implement audited offline YuNet/OpenCV face analysis with session-only tracking hints and guaranteed `ImageProxy` closure in `vision/src/main/java/com/liveshield/vision/face/OfflineFaceAnalyzer.java`
- [x] T037 [US1] Implement the minimum timestamped face-snapshot coordinator and ephemeral association needed for host selection and default protection of every non-host face in `video-pipeline/src/main/java/com/liveshield/video/analysis/FaceAnalysisCoordinator.java`
- [x] T038 [US1] Implement the single renderer-owned CameraX `CameraEffect` and bounded OpenGL texture path in `video-pipeline/src/main/java/com/liveshield/video/render/PrivacySurfaceProcessor.java`
- [x] T039 [US1] Implement strong regional mosaic/opaque protection and full-frame shield rendering, leaving blur disabled until certified, in `video-pipeline/src/main/java/com/liveshield/video/render/GlRedactionRenderer.java`
- [x] T040 [US1] Add decoded-output treatment-gate tests for padded coverage, edges, compression stability, and deterministic escalation to stronger masking or full shield in `video-pipeline/src/androidTest/java/com/liveshield/video/RedactionStrengthGateTest.java`
- [x] T041 [P] [US1] Build the setup screen with permission handling, camera preview, selectable face overlays, readiness state, and disabled start action in `app/src/main/java/com/liveshield/app/setup/SetupActivity.java` and `app/src/main/res/layout/activity_setup.xml`
- [x] T042 [US1] Wire selection, readiness, sanitized preview, and safe stop through `app/src/main/java/com/liveshield/app/session/LiveSessionCoordinator.java`
- [x] T043 [US1] Implement production renderer-downstream `SanitizedVideoOutput` backed by H.264 `MediaCodec` plus a debug-only MP4 mux sink that accepts only renderer-owned surfaces in `video-pipeline/src/main/java/com/liveshield/video/output/SanitizedVideoOutput.java` and `video-pipeline/src/debug/java/com/liveshield/video/output/DebugSanitizedRecorder.java`
- [ ] T044 [US1] User action: connect a physical Android phone, then run the staged independent test and record decoded-frame evidence in `docs/verification/us1-protected-start.md`

**Checkpoint**: A host can be selected and a local sanitized session can begin; no network publishing or automatic bystander tracking is required yet.

---

## Phase 4: User Story 4 - Fail Private During Degradation (Priority: P1)

**Goal**: Guarantee that missing, stale, overloaded, or failed processing produces carried protection, a full shield, or a stopped session—never raw video.

**Independent Test**: Inject every initial fault while sentinel pixels are protected, decode the actual output, and observe zero untreated frames before, during, and after recovery.

### Tests for User Story 4

- [x] T045 [P] [US4] Write failing policy tests for stale lanes, scene changes, queue pressure, renderer failure, thermal transitions/hysteresis, unsafe thermal recovery, and conservative mask expansion in `privacy-domain/src/test/java/com/liveshield/privacy/policy/FailPrivatePolicyTest.java`
- [x] T046 [P] [US4] Write failing bounded raw-frame ownership tests for timeout, capacity, exactly-once release, and unsafe recovery in `video-pipeline/src/test/java/com/liveshield/video/buffer/BufferedFrameProcessorTest.java`
- [x] T047 [P] [US4] Build the 12 deterministic renderer clips and 20 fault-injection fixtures with development/holdout truth in `test-fixtures/manifests/system-v1.jsonl` and `test-fixtures/annotations/`
- [x] T048 [P] [US4] Implement decoded-output sentinel and forbidden-pixel inspection tests in `video-pipeline/src/androidTest/java/com/liveshield/video/EncodedPrivacyVerifierTest.java`

### Implementation for User Story 4

- [x] T049 [US4] Implement fresh/carried/expanded/shield policy transitions in `privacy-domain/src/main/java/com/liveshield/privacy/policy/DefaultPrivacyPolicyEngine.java`
- [x] T050 [US4] Implement bounded raw texture ownership, exact timestamp join, deadline handling, and shield-on-capacity in `video-pipeline/src/main/java/com/liveshield/video/buffer/GlBufferedFrameProcessor.java`
- [x] T051 [P] [US4] Implement privacy-safe numeric/enum telemetry with payload rejection in `privacy-domain/src/main/java/com/liveshield/privacy/telemetry/PrivacySafeTelemetry.java`
- [x] T052 [P] [US4] Add deterministic debug fault controls for detector, queue, GL, surface, camera, lifecycle, encoder, and network paths in `app/src/debug/java/com/liveshield/app/debug/FaultInjectionController.java`
- [x] T053 [US4] Implement platform thermal callbacks and feed typed degraded/severe signals into fail-private policy in `video-pipeline/src/main/java/com/liveshield/video/thermal/ThermalSafetyController.java`
- [x] T054 [US4] Implement healthy/degraded/shielding private UI states outside the rendered output in `app/src/main/java/com/liveshield/app/session/LiveActivity.java` and `app/src/main/res/layout/activity_live.xml`
- [x] T055 [US4] Execute all 20 fault fixtures against decoded output and publish zero-bypass evidence in `docs/verification/us4-fail-private.md`

**Checkpoint**: All unsafe paths fail private and recovery cannot flush older untreated frames.

---

## Phase 5: User Story 2 - Protect Unexpected People (Priority: P1)

**Goal**: Detect and temporally protect every non-host face, including entry, motion, obstruction, crossings, and uncertain host continuity.

**Independent Test**: A consented unknown person enters and crosses the host under varied conditions; the decoded output protects every unknown/ambiguous face and never transfers host visibility automatically.

### Tests for User Story 2

- [x] T056 [P] [US2] Implement an offline WIDER FACE regression runner and padded-containment metrics in `vision/src/androidTest/java/com/liveshield/vision/face/WiderFaceRegressionTest.java`
- [x] T057 [P] [US2] Write failing association tests for entry, short gaps, crossing, split/merge, identity-switch rejection, and expiry in `vision/src/test/java/com/liveshield/vision/face/FaceTrackAssociatorTest.java`
- [x] T058 [P] [US2] Write failing host-loss tests ensuring an ambiguous or replaced track becomes protected until explicit reselection in `privacy-domain/src/test/java/com/liveshield/privacy/host/HostContinuityPolicyTest.java`
- [x] T059 [P] [US2] Create temporal coverage, longest-gap, fragmentation, and ID-switch evaluation utilities in `test-fixtures/src/main/java/com/liveshield/fixtures/FaceTrackingMetrics.java`

### Implementation for User Story 2

- [x] T060 [US2] Implement Java IoU/distance/scale/velocity track association and bounded prediction in `vision/src/main/java/com/liveshield/vision/face/FaceTrackAssociator.java`
- [x] T061 [US2] Map face findings into host-visible, unknown-protected, and ambiguous-protected decisions in `privacy-domain/src/main/java/com/liveshield/privacy/policy/FacePrivacyPolicy.java`
- [x] T062 [US2] Upgrade the baseline face coordinator for entry, bounded prediction, crossing, split/merge, occlusion, and host-loss behavior in `video-pipeline/src/main/java/com/liveshield/video/analysis/FaceAnalysisCoordinator.java`
- [x] T063 [US2] Add host-loss reselection UI without biometric identity or automatic permission transfer in `app/src/main/java/com/liveshield/app/session/HostReselectionController.java`
- [x] T064 [US2] Define the later consented-capture procedure, consent form, fictional-payload checklist, encrypted external storage/access ledger, deletion deadlines, and deletion audit in `docs/testing/consented-capture-protocol.md`
- [x] T065 [US2] Implement reusable per-frame face annotation and temporal-metric tooling for the later device-validation corpus in `test-fixtures/src/main/java/com/liveshield/fixtures/FaceAnnotationEvaluator.java`
- [x] T066 [US2] Run WIDER regression and deterministic temporal fixtures, then record preliminary per-slice containment, delay, longest-gap, and ID-switch evidence in `docs/verification/us2-faces.md`

**Checkpoint**: Unexpected and uncertain faces are automatically protected in decoded output; public and consented evidence remain separately reported.

---

## Phase 6: User Story 5 - Protect a Solo Indoor Creator (Priority: P1)

**Goal**: Make the bounded indoor workflow understandable and honest for a first-time creator.

**Independent Test**: A representative user can configure a controlled room, select a host, recognize protection health, start/end safely, and understand that outdoor/crowd use is not validated and V1 does not capture microphone audio.

### Tests for User Story 5

- [x] T067 [P] [US5] Add Espresso tests for permission denial, setup progression, health-state comprehension labels, absence of microphone permission/capture, and safe end-session behavior in `app/src/androidTest/java/com/liveshield/app/SoloIndoorFlowTest.java`
- [x] T068 [P] [US5] Preregister a 10-participant usability script with first-time-user criteria, assistance/exclusion rules, task timing, destination failure, comprehension questions, and exact numerator/denominator reporting in `docs/testing/solo-indoor-usability.md`

### Implementation for User Story 5

- [x] T069 [US5] Implement session-local watchlist and fixed privacy-zone setup controls in `app/src/main/java/com/liveshield/app/setup/IndoorPrivacySetupController.java`
- [x] T070 [US5] Implement concise scope, visual-only protection, and unsupported-context disclosures in `app/src/main/java/com/liveshield/app/setup/ScopeDisclosureFragment.java` and `app/src/main/res/layout/fragment_scope_disclosure.xml`
- [ ] T071 [US5] User action: recruit at least 10 representative first-time users without replacing observed failures and run the scripted indoor/destination flow, storing de-identified outcomes in `evaluation-data/usability/solo-indoor-v1.csv`
- [ ] T072 [US5] Summarize exact counts, rates, setup-time distribution, destination-flow completion, health-state comprehension, and withdrawn/missing cases without participant media in `docs/verification/us5-usability.md`

**Checkpoint**: The bounded solo-indoor experience is usable and its limitations are visible before streaming.

---

## Phase 7: User Story 6 - Publish a Protected Stream to TikTok LIVE (Priority: P1)

**Goal**: Publish only sanitized H.264 video through a destination-neutral RTMP path, always testable with MediaMTX and optionally with creator-issued TikTok credentials.

**Independent Test**: The app publishes sanitized video-only output to MediaMTX and survives network interruption without raw output; if eligible TikTok credentials exist, the identical publisher reaches a limited TikTok LIVE without using TikTok's mobile camera.

### Tests for User Story 6

- [x] T073 [P] [US6] Write failing sanitized-access-unit type-boundary tests in `transport/src/test/java/com/liveshield/transport/SanitizedBoundaryTest.java`
- [x] T074 [P] [US6] Write failing video delay-queue tests for ordering, bounds, stop clearing, reconnect, configuration units, and fresh keyframes in `transport/src/test/java/com/liveshield/transport/DelayedAccessUnitQueueTest.java`
- [x] T075 [P] [US6] Write failing secret-lifecycle tests for masking, non-persistence, log exclusion, and memory clearing in `transport/src/test/java/com/liveshield/transport/StreamDestinationSecretTest.java`
- [x] T076 [P] [US6] Create an RTMP integration test against pinned MediaMTX that asserts exactly one H.264 video track and zero audio tracks/packets in `transport/src/test/java/com/liveshield/transport/RtmpMediaMtxIntegrationTest.java`

### Implementation for User Story 6

- [x] T077 [US6] Expose copied sanitized H.264 access units from the US1 `SanitizedVideoOutput` through the transport type boundary in `video-pipeline/src/main/java/com/liveshield/video/output/SanitizedVideoOutput.java`
- [x] T078 [US6] Implement copied encoded video access units and the bounded two-second sanitized video queue in `transport/src/main/java/com/liveshield/transport/DelayedAccessUnitQueue.java`
- [x] T079 [US6] Implement the RootEncoder low-level video-only RTMP adapter without a second encoder or any audio API initialization in `transport/src/main/java/com/liveshield/transport/rtmp/RtmpStreamPublisher.java`
- [x] T080 [US6] Implement stop, congestion, IDR request, disconnect, and clean reconnect behavior in `transport/src/main/java/com/liveshield/transport/StreamSessionController.java`
- [x] T081 [P] [US6] Implement session-scoped endpoint/secret handling with redaction-safe errors in `transport/src/main/java/com/liveshield/transport/destination/StreamDestination.java`
- [x] T082 [US6] Add local-demo and TikTok-external destination setup with masked secret input and eligibility explanation in `app/src/main/java/com/liveshield/app/setup/StreamDestinationFragment.java` and `app/src/main/res/layout/fragment_stream_destination.xml`
- [x] T083 [US6] Integrate sanitized video-only publication and private publisher health into `app/src/main/java/com/liveshield/app/session/LiveSessionCoordinator.java`
- [x] T084 [US6] Run the full MediaMTX browser-viewer scenario, measure configured versus observed video delay, and verify the recorded output has zero audio tracks in `docs/verification/us6-mediamtx.md`
- [ ] T085 [US6] User action: check whether a TikTok test account exposes an RTMP server and stream key, recording only `available` or `unavailable` in `docs/verification/tiktok-access.md`
- [ ] T086 [US6] If T085 is available, test whether TikTok accepts the silent video-only publication and document viewer evidence without credentials in `docs/verification/us6-tiktok.md`; otherwise record the integration gate as unverified without blocking V1 or enabling audio

**Checkpoint**: MediaMTX publishing is mandatory and verified; TikTok publishing is conditional on external account eligibility.

---

## Phase 8: User Story 3 - Protect Visible Personal Information (Priority: P2)

**Goal**: Automatically protect supported codes and structured patterns, apply configured watchlists and complete privacy zones, and preserve unrelated content when text boundaries are reliable.

**Independent Test**: Synthetic cards, labels, documents, and screens pass through the live pipeline; decoded output protects supported sensitive regions, carries masks through short misses, expands uncertain regions, and reports false-positive controls separately.

### Tests for User Story 3

- [x] T087 [P] [US3] Write failing validators for email, phone, Luhn card, contextual OTP, exact normalized watchlist matches, absent-watchlist controls, and adversarial near-matches in `vision/src/test/java/com/liveshield/vision/pii/StructuredPiiValidatorTest.java`
- [x] T088 [P] [US3] Write failing session privacy-configuration tests for Unicode/case/word-boundary watchlists, clearing, full-zone persistence, and rotation/crop/mirror/camera-change transforms in `privacy-domain/src/test/java/com/liveshield/privacy/policy/SessionPrivacyConfigurationTest.java`
- [x] T089 [P] [US3] Write failing OCR range-to-polygon and conservative-expansion tests in `vision/src/test/java/com/liveshield/vision/pii/OcrRegionMapperTest.java`
- [x] T090 [P] [US3] Write failing barcode-format and bounding-region tests in `vision/src/test/java/com/liveshield/vision/pii/BarcodePrivacyAnalyzerTest.java`
- [x] T091 [P] [US3] Implement the BIV-Priv-Seg 16-image smoke runner and localization metrics in `vision/src/androidTest/java/com/liveshield/vision/pii/BivPrivSmokeTest.java`
- [x] T092 [P] [US3] Generate the 26 fictional Priority 2 appearances with explicit `AUTOMATIC_PATTERN`, `CONFIGURED_WATCHLIST`, or `CONFIGURED_ZONE` lanes plus harmless/absent-watchlist controls and disjoint payload IDs in `test-fixtures/manifests/pii-v1.jsonl` and `test-fixtures/annotations/pii-v1/`

### Implementation for User Story 3

- [x] T093 [US3] Implement audited fully offline PaddleOCR/Paddle-Lite text analysis with element-level polygons and guaranteed input release in `vision/src/main/java/com/liveshield/vision/pii/OfflineTextAnalyzer.java`
- [x] T094 [P] [US3] Implement audited fully offline ZXing privacy-relevant barcode scanning in `vision/src/main/java/com/liveshield/vision/pii/OfflineBarcodeAnalyzer.java`
- [x] T095 [P] [US3] Implement email, phone, payment-card-like, and contextual-OTP validators plus normalized exact watchlist matching using deterministic rules and libphonenumber in `vision/src/main/java/com/liveshield/vision/pii/StructuredPiiValidator.java`
- [x] T096 [US3] Implement OCR character-range localization, padded unions, contextual classification, and uncertain-region expansion in `vision/src/main/java/com/liveshield/vision/pii/OcrPrivacyClassifier.java`
- [x] T097 [US3] Implement independent face/OCR/barcode scheduling, one-in-flight limits, and scene-change freshness gates in `video-pipeline/src/main/java/com/liveshield/video/analysis/VisionScheduler.java`
- [x] T098 [US3] Implement timestamped carry-forward for sensitive visual findings in `privacy-domain/src/main/java/com/liveshield/privacy/policy/SensitiveFindingPolicy.java`
- [x] T099 [US3] Integrate session-scoped watchlists and full-area privacy zones with automatic pattern findings, ensuring OCR cannot weaken a zone, in `privacy-domain/src/main/java/com/liveshield/privacy/policy/PriorityTwoPolicy.java`
- [x] T100 [US3] Run BIV smoke and synthetic development/holdout suites, reporting automatic, watchlist, and zone lanes separately with per-category recall, localization coverage, excessive mask, and false positives in `docs/verification/us3-priority-two.md`
- [x] T101 [US3] Verify Priority 2 protection in actual decoded MediaMTX output and document any unsupported categories in `docs/verification/us3-encoded-output.md`

**Checkpoint**: Priority 2 risks are protected through the encoded live path with per-category limitations reported honestly.

---

## Phase 9: Polish, Performance, and Evidence Gates

**Purpose**: Validate the integrated product, harden privacy boundaries, and produce reviewable portfolio evidence.

- [x] T102 [P] Add cold-start, frame-timing, and custom trace benchmarks in `benchmark/src/main/java/com/liveshield/benchmark/LiveShieldBenchmark.java`
- [x] T103 [P] Add static checks that forbid raw image types and destination secrets in transport/telemetry APIs and reject `RECORD_AUDIO`, first-party microphone capture/audio encoders, and audio publish calls in `tools/privacy/check-boundaries.sh`
- [x] T104 [P] Add app accessibility labels, focus order, contrast checks, and nonvisual health announcements in `app/src/androidTest/java/com/liveshield/app/AccessibilityTest.java`
- [ ] T105 User action: connect the available physical-device tiers, then run 30-minute sessions, exercise degraded/severe thermal policy, verify the microphone indicator remains off, and record device, API level, resolution, FPS, detector configuration, video queue bounds, latency percentiles, memory, battery, thermal state, drops, shields, and zero raw bypass in `docs/verification/device-matrix.md`
- [ ] T106 User action: after the capture protocol passes review, record 12 consented adult face-tracking clips into the approved encrypted external store and add only opaque authorization references to `test-fixtures/manifests/face-v1.jsonl`
- [ ] T107 Annotate and evaluate the 12 controlled clips with per-frame privacy polygons, track IDs, roles, transforms, protectable timestamps, and decoded-output metrics in `test-fixtures/annotations/face-v1/` and `docs/verification/consented-device-corpus.md`
- [ ] T108 User action: before claiming SC-001, expand the controlled corpus to at least 180 independent face episodes, 100 unknown-face appearances, 10,000 annotated positive frames, and three physical device tiers according to `specs/001-live-privacy-protection/test-data.md`
- [ ] T110 Validate all 286 initial corpus records, publish separate public-regression versus created-system metrics, and freeze result hashes in `docs/verification/corpus-v1.md`
- [x] T111 Review merged manifests, media tracks, logs, crash output, screenshots, saved state, and test artifacts for microphone access, audio output, raw pixels, recognized PII, biometrics, and stream secrets in `docs/verification/privacy-audit.md`
- [x] T112 Compare every implemented requirement and success criterion with evidence, marking unmet claims explicitly in `docs/verification/acceptance-matrix.md`
- [x] T113 [P] Document architecture, trust boundaries, setup, dataset licences, limitations, and demo instructions in `README.md`
- [x] T114 [P] Create a concise portfolio case study with measured results and no guarantee language in `docs/PORTFOLIO_CASE_STUDY.md`
- [ ] T115 Run `./gradlew test lint connectedCheck` plus test-data and privacy-boundary scripts, resolving all critical failures before completion and recording the final output in `docs/verification/final-gate.md`
- [x] T116 Complete or verify the deletion audit for every retained consented raw recording whose evidence-retention period has ended in the external capture ledger and `docs/verification/evaluation-data-deletion.md`
- [x] T117 Run Spec Kit convergence analysis and resolve any remaining spec/plan/task/code drift before declaring implementation complete in `specs/001-live-privacy-protection/convergence.md`

---

## Dependencies & Execution Order

### Phase dependencies

- **Phase 1 — Setup**: Starts immediately.
- **Phase 2 — Foundation**: Depends on Phase 1 and blocks every user story.
- **US1 — Protected start**: First product increment after the foundation.
- **US4 — Fail private**: Depends on US1's renderer/camera path and blocks claims from every later story.
- **US2 — Unexpected faces**: Depends on US1 and US4; provides automatic Priority 1 behavior.
- **US5 — Solo indoor UX**: Depends on US1/US4; can proceed alongside late US2 work once stable health states exist.
- **US6 — External publication**: Depends on US1/US4 sanitized output; MediaMTX work can proceed alongside US2/US5 after those contracts stabilize.
- **US3 — Priority 2 PII**: Depends on US4's policy/renderer path; may begin after Phase 4 but is scheduled after the P1 stories.
- **Phase 9 — Final gates**: Depends on every story included in the release candidate; TikTok account availability is not a blocker.

### User-story dependency graph

```text
Setup -> Foundation -> US1 Protected Start -> US4 Fail Private
                                         ├──> US2 Unexpected Faces ──> US5 Indoor UX
                                         ├──> US6 External Publisher
                                         └──> US3 Priority 2 PII

US2 + US5 + US6 + US3 -> Integrated Evidence Gates
```

### Parallel opportunities

- In Setup, manifests/resources, dependencies, MediaMTX, and documentation tasks marked `[P]` can run independently.
- In Foundation, domain entities, fixture schemas, BIV preparation, and tests can run in parallel before final validation.
- For each story, test classes marked `[P]` can be authored together before implementation.
- After US4, US2 face work, US6 encoder/transport work, and US3 PII detector work affect separate modules and can proceed in parallel.
- User-action recording, usability, and TikTok eligibility checks occur only after their harnesses and documentation exist.

### Parallel examples

```text
After Foundation:
  T027 Host-selection tests
  T028 Session-state tests
  T029 Camera binding tests
  T030 GPU renderer tests

After US4:
  US2: T056-T059 face regression/tracking tests
  US6: T073-T076 transport/secret/integration tests
  US3: T087-T092 PII validator/localization/fixture tests
```

---

## Implementation Strategy

### MVP first

1. Complete Setup and Foundation.
2. Complete US1 through T044.
3. Stop and inspect decoded local output on one physical Android phone.
4. Complete US4 before adding broader automation or network publishing.

The MVP proves the hardest architectural boundary: only renderer-sanitized pixels reach an output.
It does not yet claim automatic bystander detection, Priority 2 protection, or TikTok integration.

### Incremental delivery

1. **MVP**: US1 protected start with manual host selection and local sanitized output.
2. **Safety core**: US4 fail-private behavior and decoded-output fault evidence.
3. **Priority 1 product**: US2 unknown-face tracking plus US5 bounded indoor UX.
4. **LIVE demonstration**: US6 MediaMTX publishing; TikTok test only when credentials are available.
5. **Priority 2 expansion**: US3 OCR/barcode/structured-PII protection.
6. **Evidence release**: physical-device, corpus, privacy-audit, and convergence gates.

### User-effort gates

- **T044/T105**: Connect at least one physical Android phone; later add more devices if available.
- **T106**: Recruit at least four consenting adults and capture 12 controlled clips only after the capture protocol and encrypted external store are approved.
- **T108**: Expand the controlled face corpus and physical-device coverage before claiming SC-001; this is not required for the initial prototype.
- **T071**: Arrange representative first-time-user sessions.
- **T085**: Check TikTok external-stream eligibility; an unavailable result does not block implementation.

## Notes

- Every task uses an exact target file or directory and follows the required checklist format.
- `[P]` means file-level parallelism, not permission to bypass dependencies or tests.
- Public datasets remain offline detector evidence and are never bundled with the app.
- No task may weaken a constitutional MUST without an approved constitution amendment.
- Commit after each task or coherent task group; preserve unrelated user changes.

## Phase 10: Convergence

- [x] T118 Add accessible creator-facing session watchlist entry/removal and fixed privacy-zone drawing/editing controls wired to `IndoorPrivacySetupController`, including bounds, clearing, transform-safety, and lifecycle verification per FR-008, US3/AC3, and Constitution IV (missing)
- [ ] T119 Diagnose and improve the offline OCR, barcode, and configured-watchlist path against development fixtures, freeze the implementation before holdout evaluation, and rerun separate development/holdout plus decoded MediaMTX gates without weakening fail-private behavior per FR-007–FR-009, SC-002, SC-009, and Constitution IV/VI (partial)

## Phase 11: Convergence

- [x] T120 Wire the production session lifecycle to `LiveActivity`, binding payload-free healthy/degraded/shielding/stopped/failed state updates and the creator's Stop control to `LiveSessionCoordinator` safe stop per FR-010, FR-016, and US4/AC4 (missing)
- [x] T121 CRITICAL compose real `PrivacySurfaceProcessor` raw-queue depth/recovery state, `ThermalSafetyController` state, and scene-change state into production `SessionHealth`, `VisionScheduler`, private status, and fail-private shield/stop recovery behavior per FR-012, FR-026, and Constitution I/VII (contradicts)

## Phase 12: Convergence

- [x] T122 Compose production `SessionPublicationPort` connection, authentication, network, congestion, queue, and terminal failure health into `LiveSessionCoordinator` and creator-private status, ensuring destination failure degrades or stops publication without an untreated fallback per FR-010, FR-015, FR-025, and US6/AC3 (partial)
