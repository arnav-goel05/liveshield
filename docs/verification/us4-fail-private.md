# US4 fail-private fault evidence

**Executed:** 2026-08-13  
**T055 bounded result:** PASS for all 20 synthetic fixtures across typed control, production
policy/queue/lifecycle seams, and decoded H.264 output  
**Device:** Android API 36 emulator  
**Network boundary:** a production RTMP disconnect was not executed in this gate

## What this execution proves

The 20 `FAULT_INJECTION` entries in the locked `system-v1` manifest were evaluated in two
complementary gates:

1. `FaultFixtureProductionSeamTest` parsed the exact manifest and dispatched the debug-only
   `FaultInjectionController`. Its handlers invoked production `DefaultPrivacyPolicyEngine`,
   `GlBufferedFrameProcessor`, and `LiveSessionCoordinator` APIs. The one JVM test passed with no
   failures or skips. Combined scenarios dispatched 24 typed checkpoints covering all nine target
   enums.
2. `EncodedPrivacyVerifierTest#allTwentyFaultFixturesProduceZeroForbiddenRawBypass` dispatched the
   same typed targets, derived decisions with `DefaultPrivacyPolicyEngine`, rendered with the real
   GL redactor, encoded video through `SanitizedVideoOutput`, then extracted and decoded every H.264
   sample. The fresh API 36 run finished in 16.846 seconds with `OK (1 test)`.

The device gate encoded 144 protected frames from 160 truth records; 16 terminal records required
no output. It observed 136 full-shield frames, 8 regional-protection frames, zero forbidden raw
matches in protected pixels, and a minimum required redaction-color ratio of 1.0. Each output had
one H.264 video track and no audio track. The untreated positive control was rejected by the same
pixel oracle.

This is combined evidence, not a stronger causal claim: the device controller callbacks record the
typed dispatch, while the scenario driver supplies the corresponding production-policy inputs.
The JVM test independently proves that typed callbacks bind to the actual policy, queue, and
lifecycle seams. The controller callback alone does not manufacture the decoded decision.

## Production-seam coverage

| Typed path | Fixture appearances | Production seam exercised | Observed safe outcome |
|---|---:|---|---|
| `DETECTOR_STALL` | 6 | Missing/stale `DetectorSnapshot` policy and late queue deadline | Full shield |
| `DETECTOR_FAILURE` | 2 | Typed analyzer failure passed to `DefaultPrivacyPolicyEngine` | Full shield |
| `QUEUE_CAPACITY` | 4 | `GlBufferedFrameProcessor` capacity latch and verified recovery | Shield queued frames; discard old ownership |
| `GL_FAILURE` | 2 | Renderer exception inside `GlBufferedFrameProcessor` | Shield remaining queue; latch unsafe |
| `SURFACE_LOSS` | 2 | Buffer processor typed failure path | Shield queued frame; latch unsafe |
| `CAMERA_FAILURE` | 2 | `LiveSessionCoordinator.onComponentFailure(CAMERA, ...)` | Stop and close protected graph |
| `LIFECYCLE_INTERRUPTION` | 2 | `LiveSessionCoordinator.close()` | Stop and close protected graph |
| `ENCODER_FAILURE` | 2 | `LiveSessionCoordinator.onEncoderState(FAILED, false)` | Stop output and protected graph |
| `NETWORK_LOSS` | 2 | Typed dispatcher only in this no-destination gate | No raw-output authorization; production network claim unavailable |

Renderer/surface and camera/lifecycle fixture scenarios intentionally exercise two typed targets,
which is why 20 fixtures produce 24 path dispatches.

## Per-fixture decoded evidence

`Raw` is the maximum forbidden-raw match ratio; `redacted` is the minimum required shield/opaque
color ratio. Output hashes identify ephemeral MP4 files inspected on this emulator; files were
deleted after inspection.

| Fixture | Split | Scenario | Truth | Encoded | Stopped | Max raw | Min redacted | Output SHA-256 | Result |
|---|---|---|---:|---:|---:|---:|---:|---|---|
| fault-dev-01 | DEVELOPMENT | missing-analysis-result | 8 | 8 | 0 | 0.0 | 1.0 | `2f1b8af3032b488bde6fa8c5544e40e6be80ed09b0e2a4fe93d6c649f12daf10` | PASS |
| fault-dev-02 | DEVELOPMENT | late-analysis-result | 8 | 8 | 0 | 0.0 | 1.0 | `2224424f396e4438cc23c5aa79b65c5b341e9302e3407c5a9771076a3a91e44a` | PASS |
| fault-dev-03 | DEVELOPMENT | stale-out-of-order-timestamp | 8 | 8 | 0 | 0.0 | 1.0 | `717241c7ad8c84cab70e7e4f1e960d6aee01b33b93dc2368d1c05e755dc357f9` | PASS |
| fault-dev-04 | DEVELOPMENT | detector-exception-cancellation | 8 | 8 | 0 | 0.0 | 1.0 | `fa2204f00d64032e563f5819d1029a01b29e7ed83d998482d0f16c18feec7767` | PASS |
| fault-dev-05 | DEVELOPMENT | raw-frame-queue-capacity | 8 | 8 | 0 | 0.0 | 1.0 | `fa2204f00d64032e563f5819d1029a01b29e7ed83d998482d0f16c18feec7767` | PASS |
| fault-dev-06 | DEVELOPMENT | renderer-failure-invalid-surface | 8 | 8 | 0 | 0.0 | 1.0 | `f0ec1fbdd472e1f50d09621cfca08607c5d09f1fb879925b887044f175eea869` | PASS |
| fault-dev-07 | DEVELOPMENT | camera-rebind-lifecycle-interruption | 8 | 4 | 4 | 0.0 | 1.0 | `00920ddcd0de0f6f3b5c416f7457a47d167924954b99702a4ad1a86666ff5637` | PASS |
| fault-dev-08 | DEVELOPMENT | encoder-backpressure-reconfiguration | 8 | 4 | 4 | 0.0 | 1.0 | `00920ddcd0de0f6f3b5c416f7457a47d167924954b99702a4ad1a86666ff5637` | PASS |
| fault-dev-09 | DEVELOPMENT | network-disconnect-reconnect | 8 | 8 | 0 | 0.0 | 1.0 | `8bc8d66c97af1c9b4a03f7574b8c7a8b610d012df5b37d714122c4b812a44e73` | PASS |
| fault-dev-10 | DEVELOPMENT | recovery-old-undecided-queue | 8 | 8 | 0 | 0.0 | 1.0 | `d734c59fb6334894c155b92a16ed6736fd1d8138eb84039117319f206700afe6` | PASS |
| fault-holdout-01 | HOLDOUT | missing-analysis-result | 8 | 8 | 0 | 0.0 | 1.0 | `58c5ead2139761aab96aff8193b5ce0e953ea6a0b0121601a214343b25901b5b` | PASS |
| fault-holdout-02 | HOLDOUT | late-analysis-result | 8 | 8 | 0 | 0.0 | 1.0 | `6e01801a32cc882ba788321ea47f2e1d21e98d2dba70b5675be7e579e854acee` | PASS |
| fault-holdout-03 | HOLDOUT | stale-out-of-order-timestamp | 8 | 8 | 0 | 0.0 | 1.0 | `70e6d10cb06118888cc1270a4b70674f52da5b9aa8b9d175076bd9bb3371cab7` | PASS |
| fault-holdout-04 | HOLDOUT | detector-exception-cancellation | 8 | 8 | 0 | 0.0 | 1.0 | `56abaeb2d2051a5633147a54767f2a0c29efa4560fd5f21725f8372178e68bdd` | PASS |
| fault-holdout-05 | HOLDOUT | raw-frame-queue-capacity | 8 | 8 | 0 | 0.0 | 1.0 | `56abaeb2d2051a5633147a54767f2a0c29efa4560fd5f21725f8372178e68bdd` | PASS |
| fault-holdout-06 | HOLDOUT | renderer-failure-invalid-surface | 8 | 8 | 0 | 0.0 | 1.0 | `cdaee91e8e7362c55ce0f4ff5f8dc5e59ec903b7680156958d1ff2f4b116b655` | PASS |
| fault-holdout-07 | HOLDOUT | camera-rebind-lifecycle-interruption | 8 | 4 | 4 | 0.0 | 1.0 | `2d628f8b953bd4ceab398e8d5ce011ca212be0aa96ff067b83ed27402bf5804c` | PASS |
| fault-holdout-08 | HOLDOUT | encoder-backpressure-reconfiguration | 8 | 4 | 4 | 0.0 | 1.0 | `2d628f8b953bd4ceab398e8d5ce011ca212be0aa96ff067b83ed27402bf5804c` | PASS |
| fault-holdout-09 | HOLDOUT | network-disconnect-reconnect | 8 | 8 | 0 | 0.0 | 1.0 | `5ee5058f01cf1276d09349c3ef330f3fde900fd0da4b336502146eb6e0b87633` | PASS |
| fault-holdout-10 | HOLDOUT | recovery-old-undecided-queue | 8 | 8 | 0 | 0.0 | 1.0 | `6b556c205bc18f10afb4cce91838275a04d59fc5d2c9510b4d240675f13e7ddf` | PASS |

## Reproducible commands and locks

Fixture validation:

```sh
python3 tools/testdata/validate_manifest.py \
  test-fixtures/manifests/system-v1.jsonl \
  --media-root test-fixtures/media \
  --truth-root test-fixtures/annotations \
  --profile system-v1 --expected-count 32
```

The validator reported 32 fixtures. Manifest SHA-256:
`c69267d5c769dbb0b65b2d1e9600e1fbb5e198f5658d320861400277296640f2`.

Production-seam source gate:

```sh
./gradlew :app:testDebugUnitTest \
  --tests com.liveshield.app.session.FaultFixtureProductionSeamTest \
  :app:verifyFaultInjectionDebugOnly :app:checkstyleAndroid \
  :app:lintDebug :app:lintRelease \
  :video-pipeline:compileDebugAndroidTestJavaWithJavac \
  :video-pipeline:verifyApi23VideoAsyncContract \
  :video-pipeline:checkstyleAndroid :video-pipeline:lintDebug
```

This completed 245 tasks successfully. The exact JVM XML recorded 1 test, 0 failures, 0 errors,
and 0 skips; SHA-256:
`ebf8e85e77b7e857a4e6e949dc8db356b5814ab2e049685229784fb23c7ff3be`.

Device command:

```sh
adb shell am instrument -w -r \
  -e class 'com.liveshield.video.EncodedPrivacyVerifierTest#allTwentyFaultFixturesProduceZeroForbiddenRawBypass' \
  com.liveshield.video.test/androidx.test.runner.AndroidJUnitRunner
```

- Test APK SHA-256: `466a3254d7d490e23d147422afd705759d6839111beb1c657095844bd83bced4`
- Instrumentation output SHA-256: `14300411c28c4bc6de2f718328d5090f95d9fd305d9a103572ee38394181a86d`
- Logcat SHA-256: `daf202b3f0156af171717883169c4f8f1f92d487cf1cfe51af1b1ce8c52dd97e`
- Extracted 21-line evidence SHA-256:
  `2b9d60e1b96e4f09b1d97a0616064480e31bd130ec3f286f0e2f7781976b3b65`

No fatal exception, process crash, or ANR was present for the test process. Emulator codec logs did
contain Media Quality Service absence, Codec2 query warnings, and decoder-release diagnostics; all
AVC encode/decode, timestamp, track, and pixel assertions nevertheless passed.

## Limitations

- The inputs are deterministic synthetic media with fictional/no payload data, not live CameraX
  frames or participant recordings.
- The network fixtures prove typed dispatch, fail-private policy output, and encoded pixels. They do
  **not** inject a disconnect into a production `RtmpStreamPublisher`; T076/T084 cover actual local
  publication separately. No network-production result is claimed here.
- Camera, lifecycle, and encoder JVM paths prove production coordinator stop/cleanup calls, while
  the device gate proves the associated scenario decisions remain protected after encoding. It does
  not physically unplug a camera or corrupt a codec.
- Zero bypass means zero forbidden raw matches in these 144 decoded protected frames under the
  declared oracle. It is not a universal zero-risk or physical-device performance claim.
