# Feature Specification: Live Privacy Protection

**Feature Branch**: `001-live-privacy-protection`

**Created**: 2026-08-12

**Status**: Ready for implementation

**Input**: User description: "Protect a live creator's video from unintended faces and visible
personal information before frames reach viewers, while processing locally and failing private;
operate as an external broadcaster for eligible TikTok LIVE accounts."

## Clarifications

### Session 2026-08-13

- Q: May LiveShield retain a small, controlled set of raw test recordings made with consenting
  adults and fictional information? → A: Yes, only as a later evaluation-only exception with
  consent, fictional information, encrypted storage outside Git, restricted access, and defined
  deletion controls.
- Q: Should V1 transmit microphone audio even though it cannot detect or remove spoken personal
  information? → A: No. V1 is video-only, and microphone audio does not leave the phone.
- Q: Should V1 automatically protect recognizable items while letting the creator specify extra
  words and screen areas to protect? → A: Yes. Pattern-recognizable risks are automatic; ambiguous
  names, organizations, containers, and screens use session-scoped watchlists or privacy zones.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Begin a Protected LIVE (Priority: P1)

A creator previews the camera, selects their own face as the host, confirms that protection is
ready, and starts a live session. The creator remains visible while every other detected face is
protected in the outgoing video.

**Why this priority**: Without a safe start state and explicit host selection, the product cannot
distinguish the intended presenter from incidental people without using prohibited identity
recognition.

**Independent Test**: A staged session with one host and one incidental person can verify that the
host remains visible, the other person is protected in the viewer output, and raw frames never
appear before readiness is confirmed.

**Acceptance Scenarios**:

1. **Given** the creator has not selected a host, **When** they attempt to begin a live session,
   **Then** no camera video is sent and the creator is asked to select one visible face.
2. **Given** the creator selects a visible face, **When** protection becomes ready and the session
   begins, **Then** that face is visible and all other visible faces are protected in the outgoing
   video.
3. **Given** multiple faces are present before the session, **When** the creator selects the host,
   **Then** only the selected track is allowed to remain visible.

---

### User Story 2 - Protect Unexpected People (Priority: P1)

During a live session, a new person may enter the frame, turn away, move rapidly, become partially
hidden, or cross the host. LiveShield protects that person automatically and keeps the protection
stable while the person remains visible or briefly loses detection.

**Why this priority**: Unexpected bystanders are the central harm addressed by Priority 1, and a
brief tracking gap is enough to expose someone to viewers.

**Independent Test**: A consented actor can enter and cross the frame under varied lighting,
movement, pose, and obstruction while evaluators inspect the outgoing video for exposed frames.

**Acceptance Scenarios**:

1. **Given** a protected session is active, **When** a new face enters the frame, **Then** the face
   is protected before its corresponding frame reaches viewers.
2. **Given** a protected face is briefly obstructed or missed, **When** the person continues moving,
   **Then** protection persists over the predicted region rather than disappearing immediately.
3. **Given** the host and another person cross paths, **When** tracking becomes ambiguous, **Then**
   the ambiguous faces remain protected instead of transferring host permission automatically.
4. **Given** the selected host can no longer be tracked confidently, **When** the uncertainty limit
   is reached, **Then** LiveShield protects that uncertain face while the rest of the protected
   video continues and requires the creator to select the host again before revealing the face.

---

### User Story 3 - Protect Visible Personal Information (Priority: P2)

During the session, LiveShield automatically protects machine-readable codes and suspected
pattern-recognizable personal information, including contact details, payment-card-like numbers,
and verification codes. Before streaming, the creator can add session-scoped words such as names,
addresses, schools, or employers and mark privacy zones over documents, badges, parcels, or visible
device screens that also remain protected during the session.

**Why this priority**: A creator can expose consequential information without another person being
present, particularly when streaming from a home, workplace, school, or public event.

**Independent Test**: Staged cards, labels, badges, documents, and screens containing known test
information can be moved through the frame while evaluators verify that sensitive text is covered
and unrelated surrounding content remains visible.

**Acceptance Scenarios**:

1. **Given** a supported machine-readable code enters the frame, **When** it becomes readable,
   **Then** viewers receive a protected region rather than a usable code.
2. **Given** a supported personal-information pattern becomes legible, **When** it is classified as
   sensitive, **Then** it is automatically protected without creator confirmation.
3. **Given** the creator configured a watchlist term or privacy zone before streaming, **When** the
   term appears or the zone becomes active, **Then** it is protected without another confirmation;
   a safely isolated text match uses a narrow mask while a configured zone protects its full area.
4. **Given** sensitive information remains stationary, **When** repeated analysis produces an
   intermittent miss, **Then** the previous protection persists through the short gap.
5. **Given** sensitive text is suspected but its boundary cannot be isolated safely, **When** the
   uncertainty limit is reached, **Then** LiveShield conservatively expands protection around the
   suspected region rather than exposing the text.

---

### User Story 4 - Fail Private During Degradation (Priority: P1)

The creator receives a private status indication when protection is healthy, uncertain, or unsafe.
If the system cannot protect frames reliably, viewers receive a privacy shield instead of raw video.

**Why this priority**: A safety feature that silently disables itself under load creates greater
risk by giving the creator false confidence.

**Independent Test**: Analysis delays, processing failures, queue exhaustion, camera changes, and
session interruptions can be deliberately induced while the outgoing video is inspected.

**Acceptance Scenarios**:

1. **Given** protection results become briefly stale, **When** the session remains recoverable,
   **Then** existing protected regions are retained and expanded conservatively.
2. **Given** safe output can no longer be produced, **When** the unsafe threshold is reached,
   **Then** viewers receive a full-frame privacy shield and never an untreated camera frame.
3. **Given** protection becomes healthy again, **When** the creator-visible status confirms
   recovery, **Then** protected video resumes without revealing buffered untreated frames.
4. **Given** a failure occurs, **When** the creator reviews the private status, **Then** it identifies
   whether protection is healthy, degraded, or shielding without exposing detected information.
5. **Given** device heat threatens the measured analysis or rendering deadline, **When** LiveShield
   cannot remain within that deadline after safe degradation, **Then** it sends a full-frame shield
   or stops publication and never releases an untreated queued frame during recovery.

---

### User Story 5 - Protect a Solo Indoor Creator (Priority: P1)

The first version is optimized for a solo creator streaming from a controlled indoor space such as
a bedroom, home office, studio, or small work area.

**Why this priority**: Indoor creators face realistic risks from housemates, family members,
documents, parcels, screens, badges, and background information while keeping the first version's
movement and crowd conditions bounded enough to evaluate credibly.

**Independent Test**: A representative first-time creator can run a staged session in a home or
small studio containing synthetic sensitive information and an unexpected consenting bystander.

**Acceptance Scenarios**:

1. **Given** a solo creator in a controlled indoor space, **When** they prepare and run a protected
   session, **Then** they can identify the host, understand protection status, and finish the
   session without specialist assistance.
2. **Given** a public venue, moving outdoor session, or dense event crowd, **When** the creator
   considers using V1, **Then** the product does not represent those conditions as validated V1
   operating contexts.

---

### User Story 6 - Publish a Protected Stream to TikTok LIVE (Priority: P1)

An eligible creator provides the external-stream destination issued for their TikTok account and
starts the protected broadcast from LiveShield. Viewers receive the sanitized, intentionally silent
video-only stream through the
creator's TikTok LIVE; the creator does not start a separate camera broadcast inside the TikTok
mobile application.

**Why this priority**: LiveShield is useful during an actual TikTok LIVE only when it is the
external broadcaster. A normal Android application cannot replace the camera feed inside TikTok's
mobile capture flow.

**Independent Test**: The complete creator flow can first be tested against a controlled compatible
viewer and then, when an eligible test account supplies external-stream access, against a private or
limited TikTok LIVE test.

**Acceptance Scenarios**:

1. **Given** the creator has valid external-stream access, **When** they provide the issued
   destination and secret and complete privacy setup, **Then** LiveShield can start the sanitized
   broadcast without opening TikTok's mobile camera flow.
2. **Given** the creator lacks external-stream access, **When** they try to configure TikTok as the
   destination, **Then** LiveShield explains the eligibility dependency and offers the controlled
   demonstration destination without implying that it can intercept TikTok's camera.
3. **Given** destination authentication or connection fails, **When** no confirmed publishing
   session exists, **Then** no untreated video leaves the device and the creator receives a clear
   private error.
4. **Given** a destination secret has been entered, **When** it is displayed, logged, retained, or
   the session ends, **Then** it is masked, excluded from logs, not retained by default, and removed
   from session memory when no longer needed.
5. **Given** any V1 broadcast or diagnostic recording is inspected, **When** its media tracks and
   packets are enumerated, **Then** it contains one sanitized video track and zero audio tracks or
   audio packets.

### Edge Cases

- No face or several possible hosts are visible during setup.
- The host leaves and later returns, or deliberately switches with another presenter.
- A protected person is extremely small, side-facing, masked, poorly lit, motion-blurred, or
  partially outside the frame.
- Two people cross and tracking identities merge, split, or switch.
- The creator changes camera, device orientation, zoom, or framing during a session.
- A mirror, photograph, poster, or face on a device screen resembles a real person.
- Text is rotated, reflected, stylized, multilingual, partly obstructed, or visible for only a
  short interval.
- Harmless text resembles a phone number, name, address, or payment-card number.
- A code or sensitive item is too small to read reliably but may become readable to viewers later.
- Analysis becomes slow because of heat, low resources, many faces, or many text regions.
- The camera, protection stage, outgoing session, or application lifecycle is interrupted.
- The creator's TikTok account is not eligible for external broadcasting, access is revoked, or
  TikTok changes the issued destination or eligibility rules.
- The external-stream destination or secret is malformed, expired, rejected, or accidentally
  pasted into the wrong field.
- The device microphone is active for another application or system feature while LiveShield is
  running; LiveShield must neither capture nor transmit that audio in V1.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST prevent an outgoing live session from starting until one currently
  visible face has been manually selected as the host and protection is ready.
- **FR-002**: The system MUST treat host permission as session-only and MUST NOT identify, enroll,
  recognize, or persist the identity or biometric representation of any person.
- **FR-003**: The system MUST automatically protect every face that is not the currently selected,
  confidently tracked host.
- **FR-004**: The system MUST protect newly appearing faces before their corresponding frames are
  delivered to viewers.
- **FR-005**: The system MUST preserve or conservatively expand protection during short detection
  and tracking gaps.
- **FR-006**: The system MUST revoke visible-host treatment when host continuity becomes uncertain
  and MUST protect that face while the rest of the safely protected video continues. It MUST
  require an explicit creator action before that face becomes visible again.
- **FR-007**: The system MUST automatically protect supported machine-readable codes and
  pattern-recognizable personal information, including email addresses, phone numbers,
  payment-card-like numbers, and verification codes, without waiting for creator confirmation.
- **FR-008**: The creator MUST be able to configure session-scoped text watchlists for names,
  addresses, employers, and schools, and privacy zones for documents, badges, parcels, and visible
  device screens. A matched watchlist term or active privacy zone MUST be protected automatically
  during the session without further confirmation. When a sensitive text boundary can be safely
  isolated, protection MUST be limited to that text region so unrelated surrounding content stays
  visible. When it cannot be isolated safely, protection MUST expand conservatively around the
  suspected or configured region.
- **FR-009**: The system MUST retain active protection conservatively through short intermittent
  misses of stationary or moving sensitive regions.
- **FR-010**: The creator MUST receive a private, concise indication of healthy, degraded, and
  shielding states that is not included in the viewer output.
- **FR-011**: When safe protected video cannot be produced, the system MUST replace the complete
  outgoing picture with a privacy shield and MUST NOT send an untreated camera frame.
- **FR-012**: The system MUST prevent untreated buffered frames from being released during recovery,
  camera changes, session termination, or lifecycle interruption.
- **FR-013**: The system MUST keep raw buffered frames ephemeral, MUST NOT upload them, and MUST NOT
  retain a raw recording by default. A separate evaluation workflow MAY retain an explicitly
  consented raw test recording when it contains only consenting adults and fictional information,
  remains encrypted and access-controlled outside Git and the application, and has a documented
  deletion deadline and deletion-audit status.
- **FR-014**: The system MUST allow only sanitized video and non-sensitive session-health signals to
  leave the device. V1 MUST NOT capture, encode, retain, or transmit microphone audio.
- **FR-015**: The system MUST make clear through its normal status language that visual protection
  is active or unavailable without claiming guaranteed anonymity.
- **FR-016**: The system MUST support ending a session safely and MUST release ephemeral camera and
  buffer data after the session ends.
- **FR-017**: The system MUST distinguish the creator's private controls and warnings from the
  sanitized picture delivered to viewers.
- **FR-018**: The system MUST expose enough non-sensitive session evidence to determine whether any
  frame bypassed privacy protection, without storing recognized personal information or raw frames.
- **FR-019**: Spoken personal information, chat moderation, persistent consent management, facial
  recognition, synthetic identity replacement, viewer-chat integration, and control of a LIVE
  session running inside a third-party mobile application MUST remain outside this feature.
- **FR-020**: V1 MUST be presented and evaluated for solo creators in controlled indoor spaces;
  outdoor mobile streams, public venues, and dense event crowds MUST remain outside the validated
  V1 context.
- **FR-021**: The system MUST operate as the external broadcaster and MUST support publishing the
  sanitized stream to a creator-authorized compatible destination without routing video through
  the TikTok mobile camera flow.
- **FR-022**: The system MUST make external-stream eligibility an explicit prerequisite and MUST
  NOT claim that every TikTok account can use direct LIVE publishing.
- **FR-023**: The system MUST provide a controlled compatible demonstration destination when an
  eligible TikTok test account is unavailable, while clearly distinguishing that demonstration
  from verified TikTok publication.
- **FR-024**: Destination secrets MUST be masked, excluded from logs and session evidence, not
  retained by default, and cleared when the session ends.
- **FR-025**: Connection, authentication, or destination failure MUST stop publication or keep the
  output fail-private; it MUST NOT create an alternate untreated video path.
- **FR-026**: Device thermal pressure MUST enter a defined degraded state before privacy deadlines
  are missed and MUST escalate to a full-frame shield or stopped publication whenever safe regional
  output cannot be maintained. Recovery MUST discard undecided pre-shield frames.

### Key Entities *(include if feature involves data)*

- **Live Session**: One creator-controlled period with readiness, active, degraded, shielding,
  recovery, and ended states.
- **Ephemeral Face Track**: A temporary person-region trajectory with host/protected policy,
  confidence, timing, and no persistent identity.
- **Sensitive Visual Finding**: A temporary code, pattern-recognizable text, watchlist match, or
  creator-configured privacy-zone region with category, confidence, timing, and protection state.
- **Protection Decision**: The action for a specific frame and region: host-visible, blurred,
  strengthened, or full-frame shielded.
- **Session Health Signal**: Non-sensitive evidence describing whether protection was ready,
  degraded, shielding, or recovered.
- **Stream Destination**: A creator-authorized compatible external broadcast endpoint, its
  non-secret display label, eligibility state, and session-scoped connection status.
- **Evaluation Fixture**: A versioned synthetic, licensed-public, or consented staged test item
  with source provenance, expected protected regions, scenario labels, and a development, holdout,
  regression, or smoke designation.

### Initial Validation Corpus

The starting validation corpus MUST contain six separately reported groups:

- **200 WIDER FACE validation images** selected deterministically as 40 unique images from each of
  five slices: small faces, heavy blur, heavy occlusion, difficult illumination/pose, and baseline.
- **16 BIV-Priv-Seg support images**, one officially annotated example for each available private
  object category, used as a smoke/regression pack rather than a statistical performance benchmark.
- **12 deterministic renderer clips** covering moving and overlapping masks, frame edges,
  rotation, crop, mirroring, decision deadlines, carry-forward, shielding, and safe recovery.
- **12 staged face-tracking clips** covering entry speed, pose change, partial obstruction,
  host/bystander crossing, host loss, small faces, frame edges, dim or backlit conditions, and
  photo/poster/screen false-face challenges.
- **26 synthetic Priority 2 appearances**, with one development and one holdout appearance for each
  of the 13 supported categories. Machine-readable codes, emails, phones, payment-card-like numbers,
  and verification codes test automatic detection; names, addresses, employers, and schools test
  configured watchlists; documents, badges, parcels, and device screens test configured privacy
  zones.
- **20 fault-injection clips** covering missing, late, stale, or failed analysis; queue capacity;
  rendering or surface failure; camera/lifecycle change; encoder backpressure; network loss; and
  recovery with old frames queued.

Created fixtures MUST use consenting adults and fictional information only. Retained raw recordings
MUST use the evaluation-only controls in FR-013 and MUST NOT be collected until the corresponding
device-validation phase begins. Each item MUST have a stable
identifier, scenario labels, source/consent or licence provenance, expected protected regions or
shield state, and a development, holdout, regression, or smoke designation. Adjacent frames from one
clip MUST remain in the same split. Holdout actors, payloads, room/motion combinations, and generated
seeds MUST NOT be used to tune behaviour before evaluation.

The two licensed public subsets provide offline detector regression only and MUST be reported
separately from the 70 created fixtures. WIDER FACE is restricted to local non-commercial
evaluation and MUST NOT be redistributed or modified. BIV-Priv-Seg attribution MUST be retained.
Neither subset can establish live tracking, device performance, consent, fail-private timing, or
encoded-output safety.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: In the release-level staged V1 scenario set, comprising at least 180 independent face
  episodes, 100 distinct unknown-face appearances, 10,000 annotated positive face frames, and three
  physical device tiers, at least 99% of annotated frames containing an unknown face fully cover the
  annotated facial region, with no uncovered interval longer than 100 milliseconds after the face
  first becomes protectable within the safety buffer. The 12-clip initial corpus reports directional
  counts only and cannot establish this criterion.
- **SC-002**: In the agreed staged V1 scenario set, at least 95% of legible automatically supported
  Priority 2 items, configured watchlist matches, and configured privacy-zone intervals are
  protected before their corresponding output reaches viewers. Results MUST be reported separately
  for automatic detection, watchlists, and privacy zones.
- **SC-003**: In every induced protection failure, queue exhaustion, and recovery test, zero
  untreated camera frames appear in the viewer output.
- **SC-004**: In an initial study of at least 10 representative first-time users, at least 9 of 10
  can select a host, confirm readiness, begin a protected session, understand a shielding event,
  and end the session without assistance.
- **SC-005**: In the same initial study, at least 9 of 10 participants correctly distinguish
  healthy, degraded, and shielding states in a comprehension test.
- **SC-006**: A creator can progress from opening the feature to a protection-ready state within
  30 seconds under normal test conditions.
- **SC-007**: Protection remains visually stable in at least 95% of annotated continuous track
  intervals, without rapid on/off flicker that exposes the underlying region.
- **SC-008**: Session evidence accounts for 100% of output intervals as protected video or
  full-frame shielded video without retaining raw imagery or recognized personal information.
- **SC-009**: In at least 90% of correctly detected automatic items and configured watchlist
  matches whose text boundary is reliable, non-sensitive content outside the annotated sensitive
  text region remains visible while the sensitive text is covered. Privacy-zone cases are reported
  separately because the creator intentionally selected the complete region for protection.
- **SC-010**: With valid controlled-viewer credentials, at least 9 of 10 representative first-time
  users can configure the destination, start a protected broadcast, recognize a connection failure,
  and end the session without assistance.
- **SC-011**: In both the controlled viewer and any authorized TikTok integration test, 100% of
  published video intervals are accounted for as sanitized video or full-frame shielded video, and
  zero published outputs contain an audio track or audio packet.
- **SC-012**: Destination secrets appear zero times in application logs, session evidence,
  screenshots, and retained application data after the session ends.
- **SC-013**: All 286 starting corpus items have complete provenance, scenario, split, and expected
  outcome records before they are included in a reported test run.
- **SC-014**: Created-fixture development and holdout manifests share zero actor identities, synthetic payloads,
  room-and-motion combinations, or deterministic generator seeds designated as holdout material.

## Assumptions

- V1 is an external live broadcaster. Its guaranteed development and evaluation target is a
  controlled compatible viewer; an end-to-end TikTok LIVE test additionally depends on an eligible
  account receiving external-stream access from TikTok.
- LiveShield does not inject video into or modify TikTok's mobile camera flow. For a protected
  TikTok LIVE, the creator starts the outgoing broadcast from LiveShield using destination details
  issued for that account.
- TikTok eligibility, account approval, audience controls, moderation, comments, gifts, and
  platform availability remain owned by TikTok and may vary by account or region.
- A compatible V1 destination accepts video-only RTMP. A platform or account configuration that
  requires audio is unsupported in V1 and does not authorize adding microphone capture.
- V1 is optimized and validated for a solo creator in a controlled indoor space. Mobile public
  streaming and dense event crowds are later contexts.
- One creator-selected host is visible at a time. Multi-host permission is a later feature.
- V1 is video-only. It does not capture or transmit microphone audio, and spoken-information
  protection remains outside its scope.
- Protection begins from a safe buffered state; the creator does not receive a zero-delay mode that
  can bypass privacy decisions.
- Strong mosaic or opaque masking is the initial certified regional treatment. Blur is preferred
  aesthetically but remains disabled until a defined encoded-output strength gate passes; any
  failed treatment escalates to stronger masking or a full-frame shield.
- Priority 2 automatically detects only pattern-recognizable codes and personal information.
  Ambiguous names and organizations require a session watchlist; documents, badges, parcels, and
  screens require creator-configured privacy zones. When sensitive regions cannot be isolated
  safely, V1 prioritizes broader protection over uninterrupted visibility.
- Evaluation uses staged, consented participants and synthetic personal information rather than
  vulnerable people or real credentials.
- The 286-item initial corpus, including 216 public images and 70 created fixtures, is a development
  baseline, not release-level evidence. Dataset size
  and scenario coverage will expand before claims beyond the bounded V1 context are made.
