# Contract: Sanitized Stream Transport

## `SanitizedVideoOutput`

- Implements the CameraX video-output surface contract.
- Supplies only a hardware encoder surface downstream of `RedactionRenderer`.
- Emits copied H.264 access units with monotonic PTS, flags, and codec configuration.
- Marks every unit `SANITIZED`; an absent mark is a programming error and blocks publication.
- Encoder reconfiguration clears unsafe pending state and resumes on a fresh keyframe.

## `DelayedAccessUnitQueue`

- Accepts only sanitized video units.
- Releases units in decode-safe order when the configured delay elapses.
- Has byte and duration bounds; congestion drops sanitized data or stops the stream.
- Stop/restart clears old units so a later session cannot publish prior content.
- Reconnect starts from codec configuration plus a fresh video keyframe.

## `StreamPublisher`

```text
connect(endpoint, sessionConfig)
publishVideo(EncodedAccessUnit sanitizedVideo)
disconnect(reason)
observeHealth() -> PublisherHealth
```

- Rejects raw images, pixel buffers, and non-sanitized video units at its type boundary.
- Does not own camera capture, privacy analysis, rendering, or encoding.
- Reports connection, cache, bytes, sent/dropped units, and failure codes without payload content.
- Accepts an opaque session-scoped destination secret, never returns it through health or telemetry,
  and clears it on disconnect.
- Exposes no microphone permission, audio encoder, audio-unit, or audio-publication API in V1.

## `RtmpStreamPublisher`

- Wraps pinned RootEncoder `RtmpClient` low-level methods.
- Sends H.264 SPS/PPS before video media.
- Maps copied payloads/PTS/flags into client calls without re-encoding.
- Is replaceable by a future WHIP/WebRTC publisher without altering privacy modules.

## Destination contracts

### Local demonstration

- App publishes to `rtmp://<LAN_HOST>:1935/liveshield`.
- Pinned MediaMTX receives RTMP and exposes browser WebRTC at
  `http://<LAN_HOST>:8889/liveshield`.
- LAN demo configuration is development-only; production transport requires authentication and
  encryption review.

### TikTok external broadcast

- The creator supplies the RTMP server and stream key issued for an eligible TikTok account.
- LiveShield starts the outgoing sanitized broadcast; it does not open, control, or replace the
  camera inside TikTok's mobile application.
- The endpoint and secret are accepted only after privacy readiness and connection validation.
- The secret is masked in UI, excluded from logs and metrics, held only for the active session by
  default, and cleared on stop or failed setup.
- TikTok account eligibility and issuance of external-stream details are external prerequisites;
  the app must not imply that it can obtain or bypass them.
- A destination that rejects video-only RTMP is unsupported in V1; this does not authorize an audio
  path.

## Failure behaviour

| Failure | Required behaviour |
|---|---|
| Network unavailable | Keep or drop only sanitized bounded units; show private status |
| Encoded queue full | Drop sanitized GOP/request keyframe or disconnect; never affect raw gate |
| Encoder error | Enter shield/failed state and stop publisher |
| Publisher reconnect | Clear stale session units; resume at fresh configuration/keyframe |
| Session stop | Disconnect, clear video delay queue, release codec resources |
