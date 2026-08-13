# US3 Priority 2 encoded-output evidence

**Executed:** 2026-08-13  
**Device:** Android API 36 ARM64 emulator  
**Result:** production renderer/encoder components preserved the accepted T100 decisions exactly:
configured zones protected 64/64 decoded targets, while missed automatic and watchlist findings left
144/144 decoded targets exposed. A separate external MediaMTX run preserved the 104 DEVELOPMENT
frames after the production two-second queue and RootEncoder publisher. Every inspected output and
the relay declaration contained one H.264 video track and zero audio tracks.

## Evidence boundary

This is a production-**component** path:

`checksum-locked T100 findings -> FramePrivacyDecision -> GlRedactionRenderer ->`
`renderer-authorized SanitizedVideoOutput -> H.264 MP4 -> decoded-pixel oracle`

It uses the real GL renderer, capability-bound encoder surface, H.264 encoder, debug mux sink,
sample-PTS join, decoder, and the established raw/mask pixel thresholds. It does not use truth to
create decisions. Truth polygons are loaded only after encoding to inspect the corresponding target
pixels.

The complete 208-frame DEVELOPMENT+HOLDOUT table below is component-path evidence. A separately
authorized DEVELOPMENT-only run additionally exercised:

`sanitized H.264 -> StreamSessionController -> two-second queue -> RootEncoder -> pinned MediaMTX`
`-> independent ffmpeg reader -> decoded-pixel oracle`

The current app source now constructs the offline text/barcode scheduler and Priority 2 policy, but
this verifier still composes frozen findings and fixture frames directly. It does not exercise the
live CameraX `PrivacySurfaceProcessor` input or prove an end-to-end TikTok session. The external
relay run covers 13 DEVELOPMENT categories/104 evaluation frames; HOLDOUT relay coverage remains
limited to the earlier component encode/decode evidence.

## Inputs and decision integrity

The test required the exact payload-free T100 findings file with SHA-256
`b25494bef48f4d4c2e7e34c4d0b8aceb73f0d20201e32e32cdf30c3516b63baa` and rejected a changed
file. It joined all 208 unique fixture/frame decisions to the frozen 26-fixture `pii-v1` corpus.
Empty successful findings stayed empty regional decisions; they were not replaced with truth masks,
full shields, or stop outcomes. The 64 configured-zone findings were the only regional masks.

T100 findings are decoded-output coordinates. Before rendering, the test adapter maps each finding
back into sensor space, asserts an output-to-sensor-to-output round trip within `1e-9`, and then lets
the production renderer apply the camera transform exactly once. This fixed a diagnosed test-only
double-rotation defect for 90-degree appearances without changing findings, decisions, or pixel
thresholds.

## Decoded results

`Protected` requires at least 90% certified mask-color pixels and at most 10% raw matches within the
complete truth target. `Exposed` means that unchanged empty decision produced a decoded target that
did not meet the protection oracle. `Max raw` and `min mask` are the most adverse frame ratios for
that eight-frame fixture.

| Split | Lane | Category | Protected | Exposed | Max raw | Min mask | Audio tracks |
|---|---|---|---:|---:|---:|---:|---:|
| DEVELOPMENT | AUTOMATIC_PATTERN | MACHINE_READABLE_CODE | 0/8 | 8/8 | 1.000000 | 0.106505 | 0 |
| DEVELOPMENT | AUTOMATIC_PATTERN | EMAIL | 0/8 | 8/8 | 1.000000 | 0.033448 | 0 |
| DEVELOPMENT | AUTOMATIC_PATTERN | PHONE | 0/8 | 8/8 | 1.000000 | 0.033773 | 0 |
| DEVELOPMENT | AUTOMATIC_PATTERN | PAYMENT_CARD | 0/8 | 8/8 | 1.000000 | 0.000000 | 0 |
| DEVELOPMENT | AUTOMATIC_PATTERN | VERIFICATION_CODE | 0/8 | 8/8 | 1.000000 | 0.051184 | 0 |
| DEVELOPMENT | CONFIGURED_WATCHLIST | PERSON_NAME | 0/8 | 8/8 | 1.000000 | 0.026375 | 0 |
| DEVELOPMENT | CONFIGURED_WATCHLIST | ADDRESS | 0/8 | 8/8 | 1.000000 | 0.023871 | 0 |
| DEVELOPMENT | CONFIGURED_WATCHLIST | EMPLOYER | 0/8 | 8/8 | 1.000000 | 0.040373 | 0 |
| DEVELOPMENT | CONFIGURED_WATCHLIST | SCHOOL | 0/8 | 8/8 | 1.000000 | 0.000000 | 0 |
| DEVELOPMENT | CONFIGURED_ZONE | DOCUMENT | 8/8 | 0/8 | 0.061497 | 1.000000 | 0 |
| DEVELOPMENT | CONFIGURED_ZONE | BADGE | 8/8 | 0/8 | 0.022587 | 1.000000 | 0 |
| DEVELOPMENT | CONFIGURED_ZONE | PARCEL_LABEL | 8/8 | 0/8 | 0.031431 | 1.000000 | 0 |
| DEVELOPMENT | CONFIGURED_ZONE | DEVICE_SCREEN | 8/8 | 0/8 | 0.032803 | 1.000000 | 0 |
| HOLDOUT | AUTOMATIC_PATTERN | MACHINE_READABLE_CODE | 0/8 | 8/8 | 1.000000 | 0.340561 | 0 |
| HOLDOUT | AUTOMATIC_PATTERN | EMAIL | 0/8 | 8/8 | 1.000000 | 0.000000 | 0 |
| HOLDOUT | AUTOMATIC_PATTERN | PHONE | 0/8 | 8/8 | 1.000000 | 0.055004 | 0 |
| HOLDOUT | AUTOMATIC_PATTERN | PAYMENT_CARD | 0/8 | 8/8 | 1.000000 | 0.041246 | 0 |
| HOLDOUT | AUTOMATIC_PATTERN | VERIFICATION_CODE | 0/8 | 8/8 | 1.000000 | 0.028445 | 0 |
| HOLDOUT | CONFIGURED_WATCHLIST | PERSON_NAME | 0/8 | 8/8 | 1.000000 | 0.037461 | 0 |
| HOLDOUT | CONFIGURED_WATCHLIST | ADDRESS | 0/8 | 8/8 | 1.000000 | 0.000000 | 0 |
| HOLDOUT | CONFIGURED_WATCHLIST | EMPLOYER | 0/8 | 8/8 | 1.000000 | 0.064171 | 0 |
| HOLDOUT | CONFIGURED_WATCHLIST | SCHOOL | 0/8 | 8/8 | 1.000000 | 0.029181 | 0 |
| HOLDOUT | CONFIGURED_ZONE | DOCUMENT | 8/8 | 0/8 | 0.040135 | 1.000000 | 0 |
| HOLDOUT | CONFIGURED_ZONE | BADGE | 8/8 | 0/8 | 0.036102 | 1.000000 | 0 |
| HOLDOUT | CONFIGURED_ZONE | PARCEL_LABEL | 8/8 | 0/8 | 0.000000 | 1.000000 | 0 |
| HOLDOUT | CONFIGURED_ZONE | DEVICE_SCREEN | 8/8 | 0/8 | 0.070283 | 1.000000 | 0 |

Aggregate decoded result:

- automatic patterns: 0/80 protected and 80/80 exposed;
- configured watchlists: 0/64 protected and 64/64 exposed;
- configured zones: 64/64 protected and 0/64 exposed;
- encoded samples: 208/208 joined to exact source PTS; and
- media tracks: one H.264 video track and zero audio tracks in every fixture output.

The zone maximum raw-match ratio was 0.070283, below the unchanged 0.10 ceiling, and every zone
fixture's minimum mask-color ratio was 1.0, above the unchanged 0.90 floor. Automatic/watchlist
targets retained a maximum raw-match ratio of 1.0, confirming actual exposure rather than an
ambiguous missing metric.

## External MediaMTX DEVELOPMENT verification

The bounded external run used the production publication controller and exact two-second delay. A
sanitized priming GOP opened the relay track while a background readiness poller preserved the
8 fps media cadence. MediaMTX and the independent reader then completed an explicit readiness
handshake. A fresh codec-configuration/IDR boundary discarded the unevaluated priming tail before
the indexed evaluation segment.

- MediaMTX declared exactly `1 track (H264)` for both publisher and reader; no audio track existed.
- The capture contained 159 decoded 192x128 H.264 frames: 55 bounded priming frames plus exactly
  104 evaluation frames.
- The evaluator excluded the 55-frame prefix, cryptographically matched all 104 suffix frames to
  the exact sanitized reference, and applied the unchanged raw <= 0.10 / mask >= 0.90 oracle.
- Automatic patterns remained 0/40 protected; configured watchlists remained 0/32 protected;
  configured zones were 32/32 protected.
- All four zone categories had minimum mask ratio 1.0. Their worst raw-match ratios were document
  0.061497, badge 0.022587, parcel label 0.031431, and device screen 0.032803.
- The automatic/watchlist maximum raw-match ratio remained 1.0, so the relay did not accidentally
  conceal those known misses.

The relay reader reported an input/output error only when the publisher closed the stream. The
complete 159-frame capture, exact 104-frame suffix, track declaration, and evaluator had already
passed; no retry or threshold change occurred.

## Execution evidence

The final bounded method passed once:

```text
EncodedPrivacyVerifierTest#priorityTwoFindingsRemainHonestThroughDecodedH264
Time: 64.682
OK (1 test)
```

No retry, detector tuning, finding substitution, or threshold change occurred. A prior run exposed
the output/sensor coordinate adapter defect at the rotated development device-screen fixture; the
inverse-transform regression and source quality gate passed before this separately authorized run.
No fatal exception, native abort, or ANR was present in the final log.

The final external method also passed once:

```text
RtmpApi36IntegrationTest#republishesExactPriorityTwoSanitizedH264ToHostRelay
Time: 31.781
OK (1 test)
validated relay frames=104 fixtures=13
```

| Artifact | SHA-256 |
|---|---|
| T100 findings input | `b25494bef48f4d4c2e7e34c4d0b8aceb73f0d20201e32e32cdf30c3516b63baa` |
| Instrumentation APK | `2974bf929e92f7f89022ec3c3aacf0231fea92c532a817a0d4d2808ea2cb0d0c` |
| Final Logcat | `68e9c428b500d48304630127d4a09786eb61e6d453b83ca0da65d6f1fbb78a97` |
| 27 payload-free evidence lines | `ba4445fd039137cde5ee8d94525b4fb93b46ddcf2933006d2eb2e16cc582aabf` |
| External sanitized reference | `6da1527c7700e596483a4ed583362b3370b8b95cf051d35a8de0f2967f601b9e` |
| External MediaMTX capture | `3486fdb355d37fbbc0ed06d06e9c7fa2c3e1fc30dbec016c1fef6733ac76ea78` |
| External payload-free metrics | `7f44a22146073b175dd973ceefaa4f44db5fd5c1bf6f13fa8b91b7d9535ac7b2` |

## Status and next work

T101 establishes that configured zones survive both the real component encode/decode path and the
production queue/RootEncoder/MediaMTX relay on DEVELOPMENT fixtures, and that the relay remains
video-only. It also establishes, mechanically and per category, that current automatic and
watchlist misses are delivered untreated. Therefore SC-002 and SC-009 remain **UNMET**, and
Priority 2 must not be described as successfully protecting all required content in the live app.

Required follow-up is to improve the real offline automatic/watchlist findings without tuning to
the frozen holdout, define a fail-private live-app behavior when required assessment cannot safely
complete, and run a live CameraX-to-relay device verification that includes HOLDOUT evidence.
