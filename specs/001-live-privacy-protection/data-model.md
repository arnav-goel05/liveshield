# Data Model: Live Privacy Protection

All runtime entities are ephemeral unless explicitly identified as non-sensitive session metrics.
Raw images, recognized text, and biometric representations are never persisted by the application.
The separate controlled evaluation corpus follows the consent, encryption, access, and deletion
controls in the constitution and is never application state or telemetry.

## LiveSession

| Field | Type | Rule |
|---|---|---|
| `sessionId` | random identifier | Exists only for the active/test session |
| `state` | `SETUP`, `READY`, `LIVE`, `DEGRADED`, `SHIELDING`, `STOPPING`, `ENDED`, `FAILED` | State transition rules below |
| `startedAtNs` | monotonic timestamp | Set when sanitized publishing begins |
| `hostTrackId` | optional ephemeral track id | Set only by explicit tap; cleared on uncertain continuity |
| `watchlist` | set of normalized strings | Device/session-local; cleared at end |
| `privacyZones` | list of normalized regions | Fixed creator-defined regions; cleared at end |
| `streamDestination` | optional `StreamDestination` | Required before `READY`; credentials cleared at end |
| `health` | `SessionHealth` | Never contains recognized text or pixels |

### State transitions

```text
SETUP -> READY              host selected and all required models/pipeline surfaces ready
READY -> LIVE              creator starts sanitized publisher
LIVE -> DEGRADED           result age/queue/thermal warning crosses soft threshold
LIVE|DEGRADED -> SHIELDING safe regional output cannot be guaranteed
SHIELDING -> LIVE          fresh valid decisions and stable surfaces restored
* -> STOPPING -> ENDED     explicit stop and all queues securely drained/released
* -> FAILED                sanitized output cannot continue; publisher stops
```

No transition from `SETUP`, `READY`, `FAILED`, or `ENDED` may emit raw camera video.

## EphemeralFaceTrack

| Field | Type | Rule |
|---|---|---|
| `trackId` | session-local integer | Not stable across sessions |
| `detectorTrackingId` | optional integer | Association hint only |
| `bounds` | normalized rectangle | Sensor-coordinate canonical form |
| `velocity` | x/y/scale estimate | Used only for short prediction |
| `lastDetectedNs` | timestamp | Drives age and mask expansion |
| `confidenceState` | `FRESH`, `PREDICTED`, `AMBIGUOUS`, `EXPIRED` | Never equated with identity |
| `policy` | `HOST_VISIBLE` or `PROTECTED` | Defaults to `PROTECTED` |

Validation: a track can become `HOST_VISIBLE` only through a creator tap while fresh. Ambiguity,
crossing, expiry, or reassociation clears host visibility.

## SensitiveVisualFinding

| Field | Type | Rule |
|---|---|---|
| `findingId` | ephemeral identifier | Session-only |
| `category` | `FACE`, `AUTO_BARCODE`, `AUTO_EMAIL`, `AUTO_PHONE`, `AUTO_CARD`, `AUTO_OTP`, `WATCHLIST_MATCH`, `PRIVACY_ZONE` | Records protection provenance without claiming unsupported semantics |
| `bounds` | normalized rectangle list | Padded union of matched elements or fixed zone |
| `sourceTimestampNs` | timestamp | Correlates finding to camera frame |
| `validUntilNs` | timestamp | Conservative carry-forward window |
| `confidenceClass` | `VALIDATED`, `CONTEXTUAL`, `UNCERTAIN` | No raw recognized value retained |
| `action` | `BLUR`, `MOSAIC`, `OPAQUE`, `SHIELD` | Policy may strengthen, never weaken on age |

## FramePrivacyDecision

| Field | Type | Rule |
|---|---|---|
| `timestampNs` | camera timestamp | Exactly one decision per output frame |
| `status` | `REGIONAL_SAFE` or `FULL_SHIELD` | Defaults to `FULL_SHIELD` |
| `regions` | immutable list of protected regions | Empty only for a positively evaluated host-only frame |
| `basis` | fresh/carried/expanded/timeout/error | Non-sensitive audit reason |
| `expiresAtNs` | timestamp | Stale decisions cannot authorize raw output |

## BufferedRawFrame

| Field | Type | Rule |
|---|---|---|
| `timestampNs` | camera timestamp | Key for decision join |
| `textureSlot` | bounded-pool handle | Never serialized or exposed outside renderer |
| `deadlineNs` | timestamp | Deadline expiry yields `FULL_SHIELD` |
| `state` | queued/rendering/released | Exactly-once ownership and release |

## EncodedAccessUnit

| Field | Type | Rule |
|---|---|---|
| `trackType` | video | V1 has no audio access units |
| `payload` | copied encoded bytes | Bounded queue; cleared on stop |
| `presentationTimeUs` | monotonic timestamp | Preserves video decode order |
| `flags` | config/keyframe/end flags | Required for reconnect and packetization |
| `releaseAtNs` | timestamp | PTS plus configured sanitized delay |
| `privacyAttestation` | `SANITIZED` | Publisher rejects any other/missing value |

## SessionHealth

Contains only numeric or enum data: state, latest decision age, detector lane ages, raw queue depth,
encoded queue duration/bytes, output fps, dropped sanitized units, shield activation count,
thermal state, and last non-sensitive failure code.

## StreamDestination

| Field | Type | Rule |
|---|---|---|
| `kind` | `LOCAL_DEMO` or `TIKTOK_EXTERNAL` | TikTok kind requires externally granted account access |
| `displayLabel` | non-secret string | Safe for private UI and metrics |
| `endpoint` | validated RTMP URI | Mask user-info/query secrets if present; clear at session end |
| `secret` | opaque character buffer | Never stringify, log, persist, or include in health; wipe after disconnect |
| `state` | `UNCONFIGURED`, `VALIDATED`, `CONNECTING`, `PUBLISHING`, `FAILED`, `CLEARED` | Only `PUBLISHING` may release delayed units |
| `failureCode` | optional non-sensitive enum | Must not contain server responses that echo credentials |

The TikTok destination is configured from server and stream-key values issued to an eligible test
account. It is not a TikTok login token and does not grant LiveShield access to comments, gifts,
moderation, or the TikTok mobile camera.

## EvaluationFixture

This is development/test data and is never part of runtime telemetry or the production APK.

| Field | Type | Rule |
|---|---|---|
| `fixtureId` | stable identifier | Unique across every corpus version |
| `corpusVersion` | semantic version | Changes when media, truth, or split changes |
| `sourceKind` | `SYNTHETIC`, `CONSENTED_CAPTURE`, `LICENSED_PUBLIC` | Determines provenance requirements |
| `split` | `DEVELOPMENT`, `HOLDOUT`, `REGRESSION`, or `SMOKE` | Derived items inherit the source split; frames from one clip cannot cross splits |
| `scenarioIds` | non-empty set | Maps to renderer, face, PII, or fault scenarios |
| `sourceDigest` | SHA-256 | Detects silent media changes |
| `provenanceRef` | consent record or dataset/version/licence reference | Required; kept outside app telemetry |
| `deviceContext` | optional non-sensitive device/camera metadata | Required for consented device captures |
| `expectedFrames` | ordered `ExpectedFrame` references | Frame truth aligned by source timestamp |

## EvaluationCaptureAuthorization

This evaluation-only record is held outside the repository and application. A safe manifest stores
only its opaque reference and non-sensitive status.

| Field | Type | Rule |
|---|---|---|
| `authorizationId` | opaque identifier | Referenced by a consented fixture; contains no participant name |
| `consentRecorded` | boolean | True for every visible participant before capture |
| `fictionalPayloadVerified` | boolean | No real personal information or credentials appear |
| `encryptedStorageRef` | opaque reference | Resolves only for authorized evaluators outside Git/app |
| `authorizedAccessRef` | opaque reference | Identifies the controlled access list |
| `deletionDeadline` | date | Required before capture |
| `deletionAuditStatus` | `PENDING`, `DELETED`, or `WITHDRAWN` | Must become `DELETED` or `WITHDRAWN` when evidence retention ends |

## ExpectedFrame

| Field | Type | Rule |
|---|---|---|
| `fixtureId` | fixture identifier | Links to exactly one fixture |
| `frameIndex` | non-negative integer | Unique within fixture |
| `sourceTimestampNs` | timestamp | Used for input/output alignment |
| `transform` | crop, rotation, mirror, sensor-to-buffer mapping | Preserves coordinate truth |
| `objects` | protected polygons with category, role, and protectable/legible state | Contains no face embeddings or real PII text |
| `expectedState` | regional protection or full shield | Defaults to full shield when unspecified |
| `generatorSeedOrPayloadId` | optional non-secret identifier | Holdout values cannot appear in development |

## Relationships

```text
LiveSession 1 ── * EphemeralFaceTrack
LiveSession 1 ── * SensitiveVisualFinding
LiveSession 1 ── 0..1 StreamDestination
FramePrivacyDecision * ── * tracks/findings (immutable snapshots)
BufferedRawFrame 1 ── 1 FramePrivacyDecision
FramePrivacyDecision 1 ── 0..* EncodedAccessUnit
LiveSession 1 ── 1 SessionHealth
EvaluationFixture 1 ── * ExpectedFrame
```
