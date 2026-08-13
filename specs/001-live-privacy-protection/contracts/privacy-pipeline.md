# Contract: Privacy Pipeline

These are logical Java contracts. Exact packages and signatures may be refined during tasks, but
their safety semantics are binding.

## `VisionAnalyzer`

- Accepts an analysis-frame handle, camera timestamp, rotation, and coordinate transform.
- Emits immutable findings keyed to the input timestamp.
- MUST close/release the input handle on success, failure, or cancellation.
- MUST NOT persist pixels or recognized text.
- Errors produce a typed lane failure; they never authorize an untreated frame.

## `HostSelectionController`

- Accepts a creator tap and the current fresh face-track snapshot.
- Returns either one selected session track or a rejection reason.
- MUST NOT select through identity recognition or transfer permission to an ambiguous replacement.
- Host loss sets the old/predicted region to protected until another explicit tap succeeds.

## `PrivacyPolicyEngine`

```text
decide(frameTimestamp, detectorSnapshots, activeTracks, sessionPrivacyConfiguration, health)
  -> FramePrivacyDecision
```

- Default output is `FULL_SHIELD`.
- A regional decision is valid only when required detector lanes are fresh for the scene state.
- New/unknown/ambiguous faces are protected.
- Stale masks are carried and expanded within a bounded interval.
- Changed scenes require a fresh Priority 2 assessment or remain shielded.
- Policy has no access to camera or network APIs.

## `SessionPrivacyConfiguration`

- Contains normalized session-scoped watchlist terms and creator-defined privacy zones.
- Watchlist matching uses documented Unicode normalization, case folding, and word boundaries;
  unmatched ambiguous text is not claimed as automatically understood.
- An active privacy zone protects its complete transformed area from `READY` until stop and cannot
  be weakened by an OCR result.
- Rotation, crop, mirroring, zoom, and camera changes transform or conservatively suspend zones;
  an unsafe transform shields until the creator confirms the updated region.
- Configuration and recognized matches are excluded from logs and telemetry and cleared at stop.

## `FrameDecisionStore`

- Stores immutable decisions in timestamp order within a bounded window.
- Lookup never falls forward from a future decision.
- Expired or missing lookup returns `FULL_SHIELD`, not null/allow.
- Eviction clears all region data for the evicted interval.

## `BufferedFrameProcessor`

- Owns every raw texture from receipt until processed release.
- Joins by the exact camera timestamp where available.
- Deadline or queue-capacity pressure invokes a conservative decision and releases the oldest frame.
- Renderer/GL/surface failure stops downstream raw delivery and enters shield/failed state.
- There is no method that forwards the input texture directly to an output surface.

## `RedactionRenderer`

- Accepts a raw texture plus exactly one `FramePrivacyDecision`.
- `REGIONAL_SAFE`: applies the currently certified treatment to all regions with padding; the
  initial treatment is strong mosaic or opaque coverage.
- `FULL_SHIELD`: replaces all raw pixels with a non-sensitive shield frame.
- Blur remains disabled until it passes a defined encoded-output strength gate. Any treatment that
  fails its gate escalates deterministically to mosaic, opaque coverage, or the full shield.
- Both creator preview video and encoder output originate from the same sanitized render pass.

## `SafetyTelemetry`

- Accepts timings, counts, queue depth, thermal state, and typed failure reasons.
- Rejects images, recognized strings, bounding-box screenshots, and biometric data.
- Test builds can export non-sensitive JSON for acceptance analysis.

## Safety invariants

1. Every encoded video frame maps to an explicit decision.
2. Missing, stale, cancelled, or failed decisions mean shield.
3. Only renderer-owned sanitized surfaces connect to preview/video outputs.
4. Host visibility is explicit, ephemeral, and revocable.
5. Raw-frame lifetime is bounded and ends after rendering or cancellation.
6. Thermal pressure that threatens a privacy deadline degrades, shields, or stops output; it never
   authorizes untreated output.
