<!--
Sync Impact Report
- Version change: 2.0.0 -> 2.0.1
- Modified principles:
  - II. On-Device Processing and Data Minimization
  - IV. Automatic Protection of Covered Visual Risks -> Automatic and Configured Protection
- Added sections: none
- Removed sections: none
- Decision impact:
  - Replaces the named ML Kit preference with audited fully offline vision runtimes because the
    pinned ML Kit artifacts schedule unapproved operational-metrics egress without a supported
    opt-out; the underlying on-device and data-minimization principles are unchanged.
  - Permits a controlled evaluation-only raw-recording exception with consent and deletion controls.
  - Makes V1 video-only; microphone audio cannot be captured, encoded, retained, or transmitted.
  - Narrows automatic Priority 2 claims to recognizable patterns; ambiguous risks require
    session-scoped creator watchlists or privacy zones.
- Follow-up artifacts: feature specification, plan, data model, contracts, quickstart, and tasks.
- Deferred items: none
-->
# LiveShield Constitution

## Core Principles

### I. Privacy Wins and Fail-Private

Privacy protection MUST take precedence over uninterrupted video and visual quality. A camera
frame MUST NOT enter an outgoing stream until the privacy pipeline has made a timely decision for
that frame. When analysis is stale, overloaded, unavailable, or uncertain, the system MUST first
preserve and expand existing protection and MUST escalate to a full-frame privacy shield when it
cannot safely continue. It MUST NOT silently pass through an unprocessed raw frame. This ordering
is non-negotiable: privacy outranks stream continuity, which outranks aesthetics.

### II. On-Device Processing and Data Minimization

Face analysis, visual-text recognition, barcode analysis, tracking, and protection decisions MUST
run on the user's device. Only sanitized video and explicitly approved non-sensitive operational
signals MAY leave the device. Raw frames held for the safety buffer MUST remain ephemeral, MUST NOT
be uploaded, and MUST be released promptly after processing. Raw recordings MUST NOT be retained by
default. V1 MUST NOT capture, encode, retain, or transmit microphone audio. Logs, crash reports,
analytics, and ordinary test artifacts MUST NOT contain raw frames, recognized personal
information, or biometric data.

A controlled evaluation workflow MAY retain raw test recordings only when every visible person has
explicitly consented, all displayed personal information is fictional, storage is encrypted and
access-controlled outside Git and the application, and each recording has a documented deletion
deadline. This exception MUST NOT enable application telemetry, production retention, upload, or
distribution of raw recordings and MUST NOT begin before the planned device-validation phase.

### III. Ephemeral Host Selection Without Identity Recognition

The creator MUST select the visible host manually for each live session. Host continuity MUST use
only ephemeral tracking information needed by the active session. LiveShield MUST NOT create or
persist face embeddings, identify real people, infer identity from appearance, or maintain a face
recognition database. If host continuity becomes uncertain, that track MUST revert to protected
status until the creator safely selects it again.

### IV. Automatic and Configured Protection of Covered Visual Risks

LiveShield MUST automatically protect unknown faces, supported machine-readable codes, and
pattern-recognizable personal information such as emails, phone numbers, payment-card-like numbers,
and verification codes. Ambiguous semantic risks such as names, addresses, employers, and schools
MUST use session-scoped creator watchlists. Documents, badges, parcels, and visible device screens
MUST use creator-configured privacy zones in V1. Once configured, a watchlist match or active
privacy zone MUST be protected automatically without further confirmation during the session.
Detection confidence and exact category behaviour belong in the feature specification, but
uncertain supported content MUST default toward protection. A false-positive visual obstruction is
preferable to an unprotected privacy leak during a live session.

### V. Reusable Java-First Architecture

The product MUST be structured as a reusable privacy pipeline with a demonstration Android
application, not as one inseparable prototype. Capture, detection, temporal tracking, policy,
rendering, encoding, and transport MUST have explicit interfaces and independently testable
contracts. Application and domain logic MUST be written in Java. Kotlin MAY be introduced only
when an official platform integration or unavoidable dependency requires it, and the reason MUST
be documented. Components MUST remain replaceable so a detector, encoder, or stream transport can
change without rewriting privacy policy.

### VI. Protective Visual Treatment and Honest Behaviour

Blur is the preferred normal visual treatment, but aesthetics MUST NOT weaken protection. Blur
strength and mask coverage MUST satisfy the project's encoded-output privacy tests. When blur
cannot meet those gates, the renderer MUST use stronger mosaic, opaque masking, or full-frame
protection. User messaging SHOULD remain concise and avoid repetitive warning fatigue, but the
product MUST NOT claim guaranteed anonymity, conceal a known material failure, or label an
unverified frame as protected. Behaviour and claims MUST match measured evidence.

### VII. Evidence-Based Completion

A privacy feature is complete only when tests demonstrate that protection exists in the encoded
output, not merely in the local preview. Every protected path MUST have automated unit and
integration coverage, explicit failure-path tests, and physical-device verification. Quality
evidence MUST include latency, dropped or stale analysis, longest uncovered interval, encoded-frame
inspection, memory use, thermal behaviour, and recovery from camera, renderer, encoder, lifecycle,
and transport failures. Known raw-frame escape paths and unresolved critical privacy failures MUST
block completion.

## Product and Technology Constraints

- Priority 1 is automatic protection of every face other than the creator-selected host.
- Priority 2 combines automatic protection for pattern-recognizable information with
  session-scoped creator watchlists and privacy zones for the ambiguous visual-information classes
  established in Principle IV. Detailed detectors, configuration behavior, confidence thresholds,
  and fallback rules MUST be specified before implementation.
- The system MUST use a bounded safety buffer and MUST define behaviour for overflow, stale results,
  detector failure, renderer failure, and device thermal pressure.
- The outgoing stream and any optional recording MUST receive the rendered protection. A UI-only
  overlay does not satisfy this requirement.
- Official, maintained, auditable components MUST be preferred, including Android Jetpack,
  CameraX, Media3, MediaCodec, and audited fully offline vision runtimes where applicable. Every additional dependency MUST have a
  compatible licence, a documented purpose, and a reviewed privacy/data-flow impact.
- Direct TikTok integration, stream protocol, buffer duration, device support, visual design,
  accessibility implementation, and exact model selection are feature-specification or planning
  decisions unless an amendment promotes them to constitutional constraints.

## Development Workflow and Quality Gates

- Work MUST follow the Spec Kit sequence: constitution, specification, clarification where needed,
  plan, tasks, cross-artifact analysis, implementation, and verification.
- Specifications MUST define observable safety behaviour and acceptance criteria before technical
  implementation is planned.
- Plans MUST include a data-flow and threat-boundary review showing every place raw and sanitized
  frames can travel.
- Tests MUST cover healthy, uncertain, and unsafe states before the corresponding feature is
  considered complete.
- Retained evaluation recordings MUST have recorded consent, fictional payload verification,
  encrypted access-controlled storage outside Git and the application, a deletion deadline, and a
  deletion audit. They MUST NOT be collected before the planned device-validation phase.
- Verification MUST include at least one representative physical Android device; emulator-only
  evidence is insufficient for camera, GPU, codec, latency, memory, battery, or thermal claims.
- Performance results MUST identify device, resolution, frame rate, detector configuration, buffer
  duration, and test conditions. Unqualified "real-time" claims are prohibited.
- Any deviation from a MUST rule requires a constitution amendment before implementation; a task or
  plan cannot silently waive it.

## Governance

This constitution is the highest-authority project document. Specifications, plans, tasks, code,
tests, and release claims MUST comply with it. When documents conflict, this constitution governs.

Amendments require: a written reason, the exact principles affected, migration or revalidation
impact, explicit user approval, an updated Sync Impact Report, and a semantic version change.
Removing or weakening a safety principle requires a MAJOR version. Adding a principle or materially
expanding governance requires a MINOR version. Clarifications that do not change obligations require
a PATCH version.

Every specification and plan review MUST include a Constitution Check. Every implementation review
MUST verify the applicable quality evidence and confirm that no raw-frame path bypasses protection.
Security- or privacy-relevant exceptions MUST be visible and time-bounded; undocumented exceptions
are prohibited.

**Version**: 2.0.1 | **Ratified**: 2026-08-12 | **Last Amended**: 2026-08-13
