# Privacy artifact audit

**Reviewed:** 2026-08-13  
**Scope:** retained T115 debug/release manifests, current first-party production sources and public
boundaries, retained verification reports, selected device logs, saved-state/private-file checks,
decoded media assertions, and dependency manifests  
**Result:** PASS for the inspected artifacts, with the explicit coverage gaps below

This audit does not turn absent evidence into a universal guarantee. It records which current
artifacts were inspected, which risky data classes were found or excluded, and which physical-device,
consented-data, HOLDOUT, and current OCR-accuracy boundaries remain unverified.

## Summary

| Risk | Inspected evidence | Result |
|---|---|---|
| Microphone permission or access | Merged debug/release manifests; API 36 setup, destination, accessibility, and decoded-output logs; AppOps checks | No `RECORD_AUDIO`; no app microphone capture observed |
| Audio output | First-party API boundary gate; encoded MP4 and RTMP `ffprobe` assertions | No first-party audio path; inspected outputs contain video only |
| Untreated/raw pixels | Renderer ownership/static boundary; decoded fixture pixel oracle and positive control | No forbidden raw match in the inspected protected frames |
| Recognized PII | Analyzer public-state/reflection tests; log/source review | Findings expose categories/offsets/geometry, not recognized strings; no real PII artifact retained |
| Biometrics | Dependency/source boundary and tracking contracts | No embedding, template, identity store, or recognition dependency/API |
| Stream secrets | Destination ownership tests, UI device audit, logcat, saved-state/private-file inspection | Fictional test secret absent after the remediated run; no session secret persisted |
| Screenshots and saved UI | UI hierarchy/device artifact checks | No screenshot artifact retained; inspected saved/private state contained no secret or camera image |

## Merged manifests and dependencies

The manifests generated for the earlier T115 snapshot were inspected directly:

| Variant | SHA-256 | Permissions |
|---|---|---|
| Debug | `a6cab23cfad31907b5b32d6f16635eee3bb50eee82bfbd31ee66f0e3d5bd2c42` | `CAMERA`, `INTERNET`, `ACCESS_NETWORK_STATE`, signature-level dynamic-receiver permission |
| Release | `6cf09ccb1419d6ce10d40cd59554b078416e6a6cd24a04d78788ff83b2761fe0` | `CAMERA`, `INTERNET`, `ACCESS_NETWORK_STATE`, signature-level dynamic-receiver permission |

Neither inspected manifest requests `RECORD_AUDIO` or `CAPTURE_AUDIO_OUTPUT`. Backups are disabled. The release
manifest contains the app activities, disabled CameraX metadata service, AndroidX Startup profile
installer, and profile receiver; it contains no ML Kit initializer, Google Data Transport/CCT,
Firebase, MediaPipe Tasks, analytics, crash-upload, microphone, media-recording, or unknown network
service/provider.

The release dependency gates separately reject ML Kit, Google Data Transport, Firebase, and
MediaPipe Tasks and require the audited offline OpenCV/YuNet, Paddle, and ZXing artifacts. Production
composition now routes offline face/text/barcode lanes, session watchlists and zones, renderer,
thermal/scene/queue health, and typed publisher health without adding payload fields. T119's last
complete Noto v2 DEVELOPMENT run retained QR 8/8 and zones 32/32 but automatic text 0/32 and
watchlists 0/32; it is not a successful general Priority 2 result. The vision module itself declares
no network permission or client.

## Microphone and audio

`tools/privacy/check-boundaries.sh` (SHA-256
`83936b970b047955ed61d3e55ff75d5cbd0ae52009768676fa5a4f2a1db883a2`) passes against the current
repository. Its negative fixtures prove it rejects authored or merged `RECORD_AUDIO`, first-party
`AudioRecord`/`MediaRecorder`, audio encoders/codecs, and audio publisher calls. Third-party source
trees are deliberately excluded; the production adapter is instead constrained to a video-only
public surface and checked never to call RootEncoder's available audio functions.

Device runs for setup, destination entry, accessibility, face analysis, GPU/codec, and RTMP
publication repeatedly inspected cleared logcat for microphone/audio-capture activity. The exact
SoloIndoorFlow run also asserted `RECORD_AUDIO` was absent and denied at runtime. The destination
and accessibility runs began with camera denied and observed no camera or microphone start. These
are bounded run-specific observations, not proof about every Android or OEM implementation.

Decoded-output evidence adds a media-level check:

- the 20 fault fixtures produced one H.264 video track and zero audio tracks per output;
- the API 36 MediaMTX run exposed exactly one H.264 video stream, 15/15 sampled packets on that
  stream, and zero audio tracks, packets, or `ffprobe` audio mentions;
- the deterministic fixture generator verifies every committed MP4 is H.264 video-only.

## Raw pixels and media artifacts

The raw boundary is structural and behavioral:

- CameraX raw input terminates in the renderer-owned `SurfaceProcessor` and bounded GL texture pool;
- transport public APIs reject `Image`, `ImageProxy`, `Bitmap`, `Surface`, generic object payloads,
  and raw byte-buffer ingress; encoded access units require renderer-issued sanitized authority;
- raw handles are private, owner-checked, bounded, and released exactly once on success, timeout,
  capacity, cancellation, surface loss, and failure.

The strongest retained behavioral evidence is the API 36 fault-fixture run documented in
[`us4-fail-private.md`](us4-fail-private.md): 144 encoded protected frames, 16 required stopped
records, maximum forbidden-raw ratio `0.0`, and minimum required redaction ratio `1.0`. Its untreated
positive control was rejected by the same oracle. This proves the inspected deterministic fixture
set, not all possible camera scenes or physical devices.

Ephemeral MP4s and decoded frames used by the device verifier were deleted after inspection. No raw
camera capture, decoded output image, or screenshot is committed in the repository. Public WIDER
and BIV source images remain gitignored evaluation inputs and are not packaged in the application.

## Recognized PII and biometrics

Text classification deliberately receives recognized strings inside short-lived method scope, but
public findings contain only a coarse category, source offsets, geometry, confidence/freshness, and
timestamps. Reflection tests reject `String`, `char[]`, `byte[]`, `ByteBuffer`, throwable/logging,
and arbitrary payload state in the validator/match and mapped-finding contracts. The OCR diagnostic
surface contains only stage enum, exception class, and numeric matrix metadata.

The completed PP-OCRv3 DEVELOPMENT run and the failed PP-OCRv5/CRNN candidate logs expose only
payload-free diagnostics; they contain no input pixels or recognized text. PP-OCRv5 failed on the
first text fixture and CRNN failed before a complete observation, so neither has an accuracy value.
Synthetic Priority 2 fixtures use reserved example/test-only values; they are not real personal
information.

Face processing uses bounding geometry and optional ephemeral detector hints only. No face
embedding, biometric template, stable identity, name, account link, or persistence API exists.
Tracking IDs are session-local and reset; manual host visibility cannot transfer to a new or
ambiguous track. WIDER images are public detector-regression inputs, not biometric enrollment.

## Stream-secret and saved-state review

`StreamDestination` accepts secret data only through the exact mutable-buffer owner factories and a
scoped callback. Tests prove defensive ownership, constant-length masking, callback-copy and owner
zeroization, idempotent close, generic cause-free errors, redacted `toString`, and the absence of a
String-secret, serialization, Parcelable, save, or getter API.

An early UI test itself leaked its fictional literal through Espresso's action description. That
test artifact was rejected, then replaced with a custom action whose description and `toString`
are constant redacted text and whose mutable source/temporary buffers are wiped. The final API 36
destination run passed 2/2 and found:

- the exact fictional secret absent from instrumentation output and cleared logcat;
- only the constant redacted action description present;
- recreation cleared the destination secret;
- the app private-file audit contained directories only and no persisted file;
- no camera or audio capture occurred.

This audit searches and reports exact test secrets, not real credentials. No real RTMP or TikTok
secret was supplied or retained. MediaMTX evidence used a fixed non-secret local path.

## Logs, crashes, and screenshots

The retained verification documents record per-run fatal/native/privacy scans. Green GPU, codec,
setup, destination, flow, face, and RTMP runs had no app fatal exception, ANR, tombstone, or native
crash. Known emulator EGL/Codec2/media-quality warnings were nonfatal and are not suppressed in the
underlying run evidence.

Important negative results remain visible rather than sanitized away:

- Noto v2 DEVELOPMENT observed automatic text 0/32 and watchlists 0/32; PP-OCRv5 and CRNN failed
  operationally and therefore have no accuracy result. The regenerated HOLDOUT remained sealed;
- the final accessibility run passed exact changed-state service notification and post-idle
  unchanged-state silence; earlier rejected runs remain useful diagnostic history.

No app screenshot artifact is currently retained. UI tests inspect hierarchy, resource semantics,
and accessibility events; therefore this audit can state that no screenshot leaked data, but cannot
claim a completed screenshot-based visual privacy review.

## Coverage gaps and required re-audit

The following artifacts are absent or incomplete and must be audited when they exist:

- the 12 user-authorized consented face clips, their annotations, access/deletion ledger, and
  decoded results;
- a future qualifying OCR DEVELOPMENT run followed by separately authorized Noto v2 HOLDOUT and
  decoded-output evidence; current QR 8/8 and zones 32/32 do not compensate for automatic/watchlist
  0/32 each, and SC-002/SC-009 remain unmet;
- prolonged physical-device traces, screenshots if explicitly captured, crash output,
  microphone-indicator observation, and memory/battery/thermal logs;
- TikTok credentials and viewer evidence, only if an eligible test account is explicitly provided;
- a new final gate over the converged T118–T122 workspace. The retained report predates those
  changes and its hashes describe only that older snapshot; the rerun is no longer a task gate.

Until those exist, this PASS applies only to the current inspected artifacts. Any new dependency,
permission, telemetry field, media surface, evaluation capture, destination flow, or retained test
artifact reopens the corresponding section of this audit.
