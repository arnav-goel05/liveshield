# Phase 0 Research: Live Privacy Protection

## Camera and redaction path

**Decision**: Use stable CameraX 1.6.1 with one custom Java `CameraEffect` backed by an OpenGL
`SurfaceProcessor`, targeting `PREVIEW | VIDEO_CAPTURE`.

**Rationale**: CameraX supports the target combination as a shared processed stream, so preview and
encoding can receive pixels only after the privacy renderer. Analysis timestamps and official
sensor-to-buffer transforms allow detector results to join the correct rendered frames.

**Alternatives considered**:

- `OverlayEffect`: useful for an early opaque-mask spike and timestamp-queue reference, but its
  public Canvas API cannot sample camera pixels for localized blur.
- `Media3Effect`: supports shader effects but the CameraX adapter remains alpha and exposes no
  public analysis-synchronized deferred queue.
- Camera2: provides low-level control but adds unnecessary device/session complexity.

**Primary sources**: [CameraEffect](https://developer.android.com/reference/androidx/camera/core/CameraEffect),
[SurfaceProcessor](https://developer.android.com/reference/androidx/camera/core/SurfaceProcessor),
[CameraX releases](https://developer.android.com/jetpack/androidx/releases/camera),
[ImageAnalysis](https://developer.android.com/media/camera/camerax/analyze).

## Buffering and failure policy

**Decision**: Separate two queues:

1. A short raw GPU queue, initially capped at 12 frames, exists only to await privacy decisions.
2. A two-second queue of already-sanitized encoded video creates the intentional LIVE delay.

Both are bounded. Raw queue pressure, missing decisions, invalid surfaces, or renderer errors select
the shield or stop output; they never release raw pixels. Exact queue depth is reduced on devices
that cannot satisfy a measured memory budget.

**Rationale**: Sixty 720p RGBA textures require roughly 211 MiB before overhead, while a few
seconds of compressed video require roughly a megabyte at V1 bitrates. A large raw queue would make
memory pressure itself a safety risk.

**Alternatives considered**:

- A two-second raw queue: rejected for memory cost.
- No raw queue: rejected because detector decisions cannot precede their capture frames.
- Blocking ImageAnalysis backpressure: rejected because it can stall all bound camera use cases and
  is not an owned fail-private queue.

## Video encoding and transport

**Decision**: Implement a CameraX `VideoOutput` backed by a hardware H.264 `MediaCodec` input
surface. V1 is video-only and neither requests microphone permission nor captures, encodes, retains,
or transmits audio. Feed copied encoded video access units into a two-second sanitized delay queue,
then into RootEncoder 2.8.0's low-level `RtmpClient`. Keep the
publisher destination-neutral. Publish to a pinned local MediaMTX server for repeatable development
and viewer inspection. If an eligible TikTok test account supplies an RTMP server and stream key,
publish the same sanitized stream directly to that TikTok ingest destination.

The controlled MediaMTX target must accept video-only ingest. TikTok compatibility is conditional
on the eligible account and endpoint accepting a silent video-only stream; rejection is recorded as
unsupported or unverified and never authorizes microphone capture.

**Rationale**: CameraX explicitly permits app-defined `VideoOutput` implementations. RootEncoder's
current `RtmpClient` exposes video configuration and send methods, so it can packetize our existing
encoded units without owning the camera or adding a second encoder.
MediaMTX supplies a mature, low-setup RTMP ingest and browser viewer. TikTok documents an official
external-encoder workflow in which eligible creators obtain an RTMP server and stream key and enter
them into OBS. LiveShield occupies that external-broadcaster position; it cannot and does not
replace the camera source inside TikTok's Android application. TikTok's public developer-product
catalogue does not list a general LIVE publishing or mobile virtual-camera API, so external-stream
credentials are a product dependency rather than something LiveShield can provision.

**Alternatives considered**:

- Native WebRTC ingest: rejected for V1 because JNI, signaling, ICE/STUN/TURN, peer lifecycle, and
  overlapping capture/encoder abstractions add disproportionate scope.
- SRT: useful over lossy long-haul networks, but native/transport complexity is unnecessary on LAN.
- HLS: browser-friendly but adds segment latency and weakens the controlled-delay demonstration.
- Custom sockets: rejected as a non-interoperable protocol project.
- Android overlay, screen-capture, or accessibility interception: rejected because those mechanisms
  cannot replace TikTok's encoded camera input and would create misleading privacy claims.

**Primary sources**: [CameraX VideoOutput](https://developer.android.com/reference/androidx/camera/video/VideoOutput),
[MediaCodec](https://developer.android.com/reference/android/media/MediaCodec),
[RootEncoder](https://github.com/pedroSG94/RootEncoder),
[MediaMTX architecture](https://mediamtx.org/docs/features/architecture),
[browser playback](https://mediamtx.org/docs/read/web-browsers),
[TikTok external encoder guide](https://seller-uk.tiktok.com/university/essay?default_language=en-GB&identity=1&knowledge_id=7738055662569218),
[TikTok developer products](https://developers.tiktok.com/products/), and
[TikTok LIVE access guidance](https://www.tiktok.com/live/studio/help/article/Before-you-go-LIVE/Apply-for-LIVE-access?lang=es).

## Face protection

**Decision**: Use the bundled YuNet 2023mar ONNX model through the audited OpenCV 4.13.0 Android
runtime. The detector emits boxes only. Deterministic IoU association provides session-local hints,
and the pure-Java track layer adds overlap, distance, scale, velocity, and age. Host status never
transfers automatically to a new ambiguous track.

**Rationale**: The pinned model is immediately available and fully offline, and its exact size and
digest are checked at build time and runtime. It avoids ML Kit's mandatory operational-metrics
transport. Predicting regions between detections provides per-output-frame masks without face
recognition, embeddings, or persistent identifiers.

**Alternatives considered**:

- Face embeddings/recognition: prohibited by the constitution and unsafe during identity errors.
- Contour mode: detects only the most prominent face and conflicts with useful multi-face tracking.
- ML Kit face detection: rejected because its mandatory operational metrics violate the
  no-unapproved-egress boundary.
- Building a custom native runtime: deferred; the official AAR is large but gives a reproducible
  Java API and API-23-compatible native baseline for the first implementation.

**Primary sources**: [OpenCV FaceDetectorYN](https://docs.opencv.org/4.x/df/d20/classcv_1_1FaceDetectorYN.html),
[OpenCV Android Maven package](https://central.sonatype.com/artifact/org.opencv/opencv/4.13.0), and
[YuNet model](https://github.com/opencv/opencv_zoo/tree/main/models/face_detection_yunet).

## Priority 2 information protection

**Decision**: Plan a separately audited offline PaddleOCR/Paddle-Lite lane and a separately audited
offline ZXing lane; neither runtime is added yet. Map matched character ranges back to OCR element
boxes and protect only their padded union. Validate email patterns strictly, phone numbers with
libphonenumber, payment cards with normalized Luhn checks, and OTPs with nearby context. Enable
privacy-relevant barcode formats only.

For ambiguous names, schools, employers, and address fragments, creators provide a session-local
watchlist. Fixed pre-LIVE privacy zones protect the complete configured area of known monitors,
document piles, badges, parcels, or windows throughout the session. OCR may localize a watchlist
match but cannot independently classify an arbitrary name, organization, or address. If an
automatic or watchlist text boundary is uncertain, expand its mask; a text result never weakens an
active privacy zone.

**Rationale**: Current official mobile primitives can validate structured PII but cannot reliably
understand arbitrary names or identify all documents, badges, parcels, and screens. The combined
rules and zones satisfy a useful indoor workflow without claiming a nonexistent universal model.

**Alternatives considered**:

- ML Kit generic object detection: its coarse categories do not cover the required objects.
- A large VLM/SAM pipeline: not credible for sustained phone inference.
- Automatic arbitrary-name detection: too context-dependent without creator input.

**Primary sources**: [PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR),
[ZXing](https://github.com/zxing/zxing), and
[libphonenumber](https://github.com/google/libphonenumber).

## Analysis scheduling

**Decision**: Begin measurement at 720p/30 fps output, face completion target 10–15/s, barcode
3–5/s, and OCR 1–2/s. A cheap scene-change signal runs on analysis frames. Stable scenes carry
timestamped masks; substantial change gates output until a fresh Priority 2 decision arrives or the
deadline produces a shield. Each detector has one in-flight request and independent scheduling.

**Rationale**: Running every detector on every frame is neither required nor sustainable. Face
protection cannot wait for OCR, while changed scenes cannot inherit a stale claim of PII safety.

**Alternatives considered**: Fixed all-detector cadence and synchronous detector chaining were
rejected because one slow lane would block unrelated protection.

## UI approach

**Decision**: Java Activities/Fragments with XML Views. One setup screen handles permissions,
watchlist/zones, camera preview, and host selection. One live screen shows private health, delay,
viewer connection, and end-session controls outside the sanitized video surface.

**Rationale**: Views are mature and Java-native, minimize language mixing, and keep the portfolio
focus on camera/media engineering rather than UI framework integration.

## Thermal degradation

**Decision**: Treat thermal state as a privacy-policy input. A soft thermal threshold enters
`DEGRADED` and may reduce detector cadence, resolution, or output frame rate only while decision
freshness remains inside its measured safety bounds. A severe thermal state, stale detector lane,
or unsuccessful reconfiguration selects the full-frame shield and stops publication if safe output
cannot recover within the bounded interval.

**Rationale**: Measuring temperature without changing behavior would not satisfy the fail-private
requirement. Performance adaptation is allowed only when it preserves the same encoded-output
coverage gates; it cannot silently trade privacy for continuity.

## Evaluation

**Decision**: Begin with three evidence layers that remain separately reported:

1. Deterministic synthetic media proves renderer, transform, queue, shielding, recovery, and
   encoded-pixel behaviour.
2. Licensed public subsets provide detector regression for challenging faces and private regions.
3. Consented physical-device captures with synthetic PII provide end-to-end temporal and encoded
   output evidence.

The initial corpus contains a 216-image public detector pack and 70 owned fixtures. The public pack
uses 200 deterministically stratified WIDER FACE validation images and all 16 BIV-Priv-Seg support
images. The owned pack uses 12 deterministic renderer clips, 12 consented face-tracking clips, 26
synthetic Priority 2 appearances, and 20 fault-injection clips. Development and holdout created
fixtures are split by actor, synthetic payload, room/motion combination, and generator seed rather
than by adjacent frames. Exact inventory and annotation rules are defined in
[test-data.md](test-data.md).

Verify actual decoded encoded output, not preview screenshots. Inject missing results, stale
timestamps, queue overflow, GL errors, surface replacement, encoder failure, network loss,
lifecycle changes, and thermal pressure. Unique raw-frame sentinels make a one-frame bypass
mechanically detectable after decoding.

**Rationale**: The constitutional claim concerns viewer pixels. Unit accuracy or preview overlays
cannot prove that raw frames did not bypass the renderer. Public image datasets also cannot prove
temporal tracking, camera timestamp alignment, consent, phone performance, or safe recovery.

**Public data decision**: Use 200 WIDER FACE validation images: 40 unique selections from small,
heavy-blur, heavy-occlusion, difficult illumination/pose, and baseline slices. Select by a fixed
hash ordering after excluding invalid boxes. WIDER FACE is CC BY-NC-ND, so it remains unmodified,
local, non-commercial evaluation data and is never redistributed. Its validation archive is about
346 MiB. [Official project](http://shuoyang1213.me/WIDERFACE/)

Use the complete 16-image BIV-Priv-Seg support set as a private-object smoke pack. It is about 15
MiB, provides one annotated example per category, and is CC BY 4.0; retain attribution. It is too
small for an accuracy claim. At a later milestone, optionally select 128 deterministic examples
from the roughly 951 MiB query archive. [Official dataset](https://vizwiz.org/tasks-and-datasets/object-localization/)

Keep all public media outside Git and the APK, and record source URLs, retrieval date, byte length,
hashes, selected IDs, sampling seed, licence, and allowed usage. Defer full VizWiz-Priv because its
downloads are multi-gigabyte and its private images are distributed in redacted variants. Defer
[VPD-100K](https://vpd-100k.github.io/) until its package, annotation coverage, and licensing are
reconciled.

Use physical low/mid/high phones for 30-minute runs and report p50/p95/p99 detector latency, output
fps, queue age/depth, shield activations, peak memory, battery, and temperature. Android discourages
emulator performance conclusions. [Macrobenchmark guidance](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview).
