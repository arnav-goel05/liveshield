# LiveShield: a fail-private Android live-video pipeline

## The problem

A solo creator can control their own appearance, but a live camera also exposes bystanders,
screens, documents, codes, and other visible information. A conventional overlay is not a strong
boundary: untreated pixels may still reach preview, encoding, or transport underneath it.

LiveShield explores a narrower question: can an Android pipeline make sanitized video the only
publishable product, and become more private when analysis is late or uncertain?

The V1 scope is deliberately constrained to one manually selected host in a controlled indoor
scene. It is visual and video-only. It neither records microphone audio nor promises anonymity or
universal sensitive-content recognition.

## My approach

I split the system into five Java-first boundaries:

1. CameraX supplies analysis frames and one custom `SurfaceProcessor` input.
2. Offline face, text, and barcode adapters emit timestamped geometry and coarse categories.
3. A pure-Java policy joins freshness, host continuity, scene state, configured watchlists/zones,
   and component health into an explicit regional-protection or full-shield decision.
4. A renderer-owned OpenGL texture pool applies that decision before both preview and H.264 encode.
5. Transport accepts only immutable access units carrying renderer-issued sanitized authority,
   holds them for an exact two-second delay, and publishes video-only RTMP.

This structure keeps raw pixels in a bounded device-memory zone. There is no app API that sends a
raw camera surface, bitmap, image, recognized string, biometric representation, or stream secret
into transport or telemetry. Missing, stale, failed, or malformed analysis never means “show raw.”
Production composition connects session watchlists/zones and offline detector lanes to that policy,
routes queue/recovery, thermal, and scene health into fail-private decisions, and presents typed
publisher health in a creator-private status Activity without media-surface ownership.

## Engineering decisions

### One sanitized path

Preview and encoding share the privacy effect rather than applying independent UI decoration.
Renderer capabilities bind downstream surfaces to the owning processor. The encoder input surface
is private, and the transport bridge accepts only copied H.264 units marked `SANITIZED`.

### Bounded ownership and deterministic recovery

The raw queue has one owner, fixed capacity, exact timestamp lookup, and exactly-once release.
Capacity, deadline, renderer, surface, and lifecycle failures discard unsafe ownership and latch a
shield until recovery is verified. Old undecided frames cannot reappear after reconnect.

### Conservative host handling

Host selection is a session-local track permission, not identity. Short gaps produce protected
prediction; crossings, merges, splits, impossible jumps, and expiry revoke visibility. A replacement
face must be selected explicitly.

### Secrets and audio exclusions

Destination secrets use mutable buffers, are password-masked, excluded from saved state and test
descriptions, loaned through a zeroizing callback, and cleared on lifecycle close. Static gates reject
`RECORD_AUDIO`, first-party capture/encoder/publish audio calls, raw media types in transport, and
secret-bearing public APIs. The delivered stream is silent by design.

## Measured evidence

These results are intentionally reported by evidence layer rather than combined into one headline
accuracy number.

### Encoded fail-private fixtures

On an API 36 ARM64 emulator, 20 deterministic fault fixtures exercised typed controls, production
policy/queue/lifecycle seams, OpenGL redaction, H.264 encode, MP4 extraction, and decoded-pixel
inspection:

- 144 protected frames encoded; 16 terminal truth records correctly produced no frame;
- maximum forbidden-raw match ratio: `0.0`;
- minimum required redaction-color ratio: `1.0`;
- one H.264 video track and zero audio tracks per output;
- the untreated positive control was rejected by the same oracle.

The network-labelled fixtures did not physically disconnect the production RTMP client, so that
part remains control/policy evidence rather than a causal network-failure claim. Full details are in
[`verification/us4-fail-private.md`](verification/us4-fail-private.md).

### Local live publication

One controlled API 36 run exercised the production session controller, exact delay queue,
RootEncoder RTMP client, pinned MediaMTX 1.15.5 relay, independent `ffprobe`, and a headless Chrome
WebRTC viewer:

| Measurement | Result |
|---|---:|
| Sanitized units released | 76 |
| Configured queue delay | 2,000.000 ms |
| Minimum / mean / maximum release delay | 2,000.401 / 2,010.364 / 2,028.577 ms |
| Probed stream | 1 H.264 video stream |
| Sampled packets | 15/15 on video stream 0 |
| Audio tracks or packets | 0 |
| Browser state | playing, 160 x 90, `readyState=4` |

The delay metric ends at publisher handoff. Browser playback proves delivery advanced but is not a
glass-to-glass latency measurement. See
[`verification/us6-mediamtx.md`](verification/us6-mediamtx.md).

### Public face-detector regression

The frozen YuNet configuration ran over 200 selected WIDER FACE validation images. Overall,
1,443/3,113 annotated faces had a one-to-one match under the declared IoU-or-padded-containment
rule; 1,344/3,113 were fully contained by the 25%-per-side padded prediction, with 81 unmatched
predictions among 1,524. The easier 40-image baseline slice matched 108/111 with mean matched IoU
0.826628.

This is a still-image detector regression, not live protection coverage or a general accuracy
benchmark. Slice memberships overlap. See [`verification/us2-faces.md`](verification/us2-faces.md).

## What failed and what I changed

- CameraX initially stopped during binding because the custom `VideoOutput` returned a null
  `MediaSpec`. A non-null AVC media contract and source-required lifecycle observable moved the
  graph through camera open, sanitized swap, and encoder readiness.
- A nominal renderer padding guard covered only 97.22% after H.264 compression. I kept the verifier
  threshold fixed and strengthened the renderer guard from 2% to 4%; the fresh decoded suite then
  passed.
- ML Kit was removed after dependency and runtime inspection found non-optional metrics transport.
  YuNet/OpenCV, PaddleOCR/Paddle-Lite, and ZXing replaced the cloud-linked artifacts behind offline
  release-graph and manifest gates.
- API 36 accessibility testing showed that one logical live-region update may generate an event
  burst. The final requirement-aligned test drains that burst, proves an exact service-visible
  status notification, then verifies an unchanged render emits no matching event.
- T119 preserved an honest model boundary: the last complete Noto v2 DEVELOPMENT run retained QR
  8/8 and configured zones 32/32, but automatic text and watchlists were each 0/32. PP-OCRv5 and
  CRNN candidates failed before complete evaluation, so they have no accuracy result. Thresholds
  were not weakened and HOLDOUT remained sealed.

## Current limits and next evidence

The project is not “done” merely because most automated gates pass.

- The corpus is 274/286. The missing 12 consented face clips require the approved external capture,
  annotation, retention, and deletion workflow.
- The required 180 face episodes, 100 unknown appearances, 10,000 positive frames, three physical
  device tiers, and 30-minute thermal/battery sessions have not been collected.
- Production Priority 2 wiring and historical BIV/decoded component evidence exist, but current OCR
  automatic/watchlist protection is unsupported; SC-002 and SC-009 remain unmet and Noto v2 HOLDOUT
  remains sealed.
- TikTok is optional and unverified; it requires a user-authorized account that actually exposes
  external RTMP credentials. The app cannot bypass that eligibility.
- MediaMTX evidence is a trusted-LAN development result, not production infrastructure or an
  internet-security review.

T119 is explicitly blocked: automatic text and watchlist OCR are unsupported. Physical-device,
consented-corpus, usability, TikTok, and final physical-benchmark work were retired as task gates,
so their absent evidence remains a limitation rather than pending completion. The implementation
effort is closed at this bounded scope, and
LiveShield remains an evidence-led
prototype with explicit unmet criteria, not a privacy guarantee.
