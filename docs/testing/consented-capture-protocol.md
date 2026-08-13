# Consented face-capture protocol

**Status:** planning control only; this document does not authorize capture  
**Scope:** the later, controlled physical-device validation of the 12 `CONSENTED_FACE` clips  
**Media:** silent video only; no microphone capture, audio track, or ambient-audio recording

## Non-negotiable boundary

Capture may begin only after an explicit device-validation go decision records the date, operator,
approved devices, encrypted storage reference, deletion schedule, and signed authorization
references. Completing this document, building the app, or starting an emulator does not open that
phase. A dry run must use generated media and an empty room.

Every visible person must be an adult who can freely give informed consent. Do not involve minors,
incidental bystanders, people unable to give informed consent, or people whose health, dependency,
employment, immigration, financial, or other circumstances could make participation vulnerable or
coerced. Stop if an unapproved person enters; discard that take before evaluation. Capture only in
an owned or explicitly authorized controlled indoor room with entrances managed during recording.

Raw video, consent forms, participant-reference mappings, access ledgers, and deletion audits stay
in encrypted, access-controlled evaluation storage outside Git, the application, Gradle assets,
cloud-sync folders, chat, issue trackers, and ordinary build/test artifacts. The repository may
contain only opaque manifest references and non-sensitive annotations accepted by the validator.

## Roles and separation

- **Capture custodian:** confirms authorization, controls the room/device, imports media into the
  encrypted store, and erases capture-device temporary copies.
- **Evaluation operator:** receives time-bounded access only to clips assigned for annotation or
  decoded-output evaluation.
- **Deletion verifier:** confirms primary, temporary, export, and backup deletion. When practical,
  this is not the person who performed deletion.
- **Participant:** may pause, skip an action, stop, or withdraw without giving a reason.

Use opaque references such as `auth-face-0001`, `actor-dev-0001`, and `asset-face-0001`. The mapping
between a person and those references belongs with the signed form in a separately encrypted
authorization store, not beside media or in the repository.

## Encrypted storage and access setup

- Use a dedicated encrypted volume or encrypted evaluation workspace with a project-specific key;
  an unlocked shared workstation or an ordinary unencrypted external disk is not sufficient.
- Keep the recovery key outside the media store and limit it to the project owner/capture custodian.
  Enable screen lock and automatic volume lock, and do not expose the store over a shared network.
- Grant least-privilege, individually attributable access for a fixed purpose and expiry. Shared
  accounts, public links, removable plaintext exports, and permanent evaluator access are banned.
- Keep the authorization mapping separately encrypted from media. An evaluator who only needs an
  opaque clip must not receive the identity mapping or signed form.
- Treat the access ledger as append-only. Record allowed, denied, failed, export, revocation, and
  deletion events promptly; never replace an old event to conceal a correction.
- Review active access before and after each capture/evaluation session. Revoke access when work
  ends, on withdrawal, at the deletion deadline, or after any suspected compromise.
- If loss, unauthorized access, mistaken sharing, or encryption failure is suspected, stop access
  and capture, preserve only non-sensitive incident evidence, notify the project owner and affected
  participant through the external contact route, and do not resume without a documented review.

## Before any recording

All boxes must be checked for every session:

- [ ] The planned device-validation phase has an explicit written go decision; its identifier is
  recorded outside the repository.
- [ ] The participant is at least 18, can freely consent, is not in an excluded vulnerable group,
  and has signed the separate [consent form template](templates/consented-capture-consent-form.md).
- [ ] Every person who could enter the frame is authorized; the controlled room has no incidental
  people, reflective views of people, family photographs, real badges, mail, calendars, or screens.
- [ ] The device is in Do Not Disturb/airplane mode where compatible with the test; notifications,
  cloud photo backup, automatic uploads, crash attachment, and gallery sync are disabled.
- [ ] Microphone permission is absent/denied. The capture configuration is video-only and a dry
  take has exactly one video stream and zero audio streams.
- [ ] All visible text, codes, documents, screens, and props pass the
  [fictional-payload checklist](templates/fictional-payload-checklist.md).
- [ ] A new opaque asset reference, authorization reference, actor reference, and room/motion
  reference are assigned without embedding a name, email, phone number, date of birth, or location.
- [ ] A development/holdout assignment is fixed before recording. An actor, room/motion combination,
  or adjacent clip segment cannot cross the split.
- [ ] The encrypted storage location exists, is unlocked only for the session, is not cloud synced,
  and is restricted to the named project roles.
- [ ] A deletion deadline and withdrawal route are written on the consent form and retention entry.

## Capture procedure

1. Re-explain the purpose, requested actions, video-only boundary, retention, access, withdrawal,
   and limits of anonymity. Confirm consent again immediately before recording.
2. Frame the authorized room and ask the participant to confirm that no real private material is
   visible. Record only the minimum takes needed for the assigned scenarios.
3. Use the planned device, lens, resolution, frame rate, detector configuration, and buffer setting.
   Record these non-sensitive facts; do not record device account identifiers or precise location.
4. Do not capture microphone audio. Do not livestream or send the raw feed to a network endpoint.
5. If an incidental or non-consenting person, real personal information, unexpected notification,
   or unsafe prop appears, stop immediately, mark the take rejected, and securely delete it without
   viewing it for evaluation.
6. Import directly into encrypted evaluation storage using an opaque filename. Calculate SHA-256,
   compare the imported file, then erase the capture-device and import-staging copies.
7. Verify exactly one video stream and zero audio streams before accepting the clip. `ffprobe` may
   be used locally; its output must not include paths containing participant information:

   ```sh
   ffprobe -v error -show_entries stream=index,codec_type -of csv=p=0 INPUT.mp4
   ```

   Acceptance requires one `video` row and no `audio` row. Any audio-bearing take is rejected and
   deleted; stripping audio does not make an unauthorized source acceptable.
8. Enter the access/retention metadata using the
   [ledger schema](templates/consented-capture-ledger.schema.json). Never enter a real identity,
   raw path, recognized text, image, credential, or free-form note.

## Annotation and evaluation

- Annotate every positive frame with source timestamp, session-local object ID, host/unknown role,
  full privacy polygon, visibility, and protectable time as defined in the test-data plan.
- Do not create face crops, embeddings, identity labels, inferred demographics, recognized text,
  or biometric templates. Session-local IDs are reset between sessions.
- Development actors, rooms/motions, and adjacent frames remain separate from holdout. Holdout
  media cannot be used for threshold/model tuning.
- Raw review happens only in the approved encrypted workspace. Derived decoded-output evidence must
  be sanitized before it can enter normal verification artifacts.
- The repository manifest uses `sourceKind: CONSENTED_CAPTURE`, `mediaStreams: ["VIDEO"]`, opaque
  authorization/storage/access references, `capturePhase: DEVICE_VALIDATION`, and the current
  deletion status. It never points at a repository copy of raw media.

## Retention, withdrawal, and deletion

Set a per-asset deadline before capture. The default deadline is the earlier of 30 calendar days
after the asset's final planned validation use or 90 calendar days after capture. A shorter period
is preferred. An extension requires a new documented justification, participant permission where
the original form does not cover it, a new absolute deadline, and project-owner approval before the
old deadline. “Until the project ends” is not a valid deadline.

A participant may withdraw through the route on the form without giving a reason. On receipt:

1. stop new use and revoke access immediately;
2. change the manifest/retention status to `WITHDRAWN` without exposing identity;
3. locate all primary, staging, annotation-working, export, and backup copies through opaque refs;
4. delete them as soon as operationally possible, targeted within 72 hours for online/primary
   copies and seven days for controlled backups;
5. record deletion using the [deletion-audit schema](templates/deletion-audit.schema.json); and
6. confirm completion through the agreed participant contact route without placing contact data in
   the project repository.

Scheduled deletion follows the same inventory. A deletion audit is complete only after primary
media, capture-device/staging copies, working exports, derived raw crops (which should never exist),
and controlled backups are addressed. Keep only the minimal non-sensitive audit record required to
show that opaque asset refs were deleted; do not keep the media hash if that retention is not needed.
Delete asset-linked annotations and non-sanitized derived files as well. A previously published
aggregate result may remain only when it contains no participant-level material and cannot
reasonably be linked back to the withdrawn asset; explain that limit before consent.

## Validation and review gate

Before accepting the 12-clip corpus, a reviewer checks:

- [ ] 12 unique `CONSENTED_FACE` records: 8 development and 4 holdout.
- [ ] At least four consenting adults, with at least two exclusive to development and two exclusive
  to holdout; actor and room/motion leakage keys do not cross splits.
- [ ] Every record has a current external authorization, encrypted-storage reference, access
  record, fictional-payload verification, absolute deletion deadline, and audit status.
- [ ] Every retained source hash matches the encrypted source; all accepted sources have one video
  stream and zero audio streams.
- [ ] No raw media, signed form, identity map, filled ledger, or sensitive annotation is tracked by
  Git or packaged into any APK/AAB.
- [ ] Every scenario in the test-data plan is covered and timestamped truth passes schema checks.
- [ ] Holdout access before the frozen evaluation is absent or explicitly recorded as a protocol
  violation that blocks claims.

Run the repository-safe manifest gate only after authorized media and truth exist in the external
evaluation workspace:

```sh
python3 tools/testdata/validate_manifest.py PATH/face-v1.jsonl \
  --media-root ENCRYPTED_MEDIA_ROOT \
  --truth-root SANITIZED_TRUTH_ROOT \
  --profile face-v1 --expected-count 12
```

The command validates metadata, hashes, paths, video-only declarations, authorization fields,
deadlines, split isolation, and truth shape. It cannot verify adulthood, informed consent, room
control, actual encryption, absence of audio in the media, withdrawal handling, or secure deletion;
those require the signed checklist, stream inspection, storage review, access ledger, and audit.
