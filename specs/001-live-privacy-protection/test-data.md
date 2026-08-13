# Initial Test Data Plan: Live Privacy Protection

This document defines the first corpus used while building LiveShield. It is deliberately small,
repeatable, and safe to create. Passing it supports development progress; it is not sufficient on
its own for a production privacy or broad real-world reliability claim.

All created video fixtures and retained evaluation recordings are silent video-only assets. The
manifest validator rejects any created fixture containing an audio stream.

## Corpus inventory

| Group | Starting size | Development / holdout | Purpose |
|---|---:|---:|---|
| WIDER FACE validation subset | 200 | Regression only | Offline face detection across five difficulty slices |
| BIV-Priv-Seg support set | 16 | Smoke only | One official example for each of 16 private-object categories |
| Deterministic renderer clips | 12 | 6 / 6 | Exact mask, transform, deadline, shield, recovery, and encoded-pixel assertions |
| Consented face-tracking clips | 12 | 8 / 4 | Temporal coverage, host continuity, occlusion, and crossing |
| Synthetic Priority 2 appearances | 26 | 13 / 13 | One development and one holdout appearance for each supported category |
| Fault-injection clips | 20 | 10 / 10 | Missing/stale results and camera, renderer, encoder, lifecycle, queue, or network failure |
| **Total** | **286** | **216 public regression/smoke + 42 / 28 created** | Initial development baseline |

Public detector results are reported separately from the 70 created system fixtures. They do not
count toward end-to-end safety success criteria.

## Public detector pack

### WIDER FACE: 200-image face subset

Download the official validation-image archive and face-annotation archive. The current validation
archive is approximately 346 MiB and contains 3,226 images; the annotations provide face boxes and
blur, illumination, invalid, occlusion, and pose attributes.

- Validation images: `https://huggingface.co/datasets/wider_face/resolve/main/data/WIDER_val.zip`
- Official annotations: `http://shuoyang1213.me/WIDERFACE/support/bbx_annotation/wider_face_split.zip`
- Project/licence: `http://shuoyang1213.me/WIDERFACE/`

Build the subset deterministically:

1. Ignore face boxes marked invalid.
2. Assign every image to each applicable slice:
   - `small`: any valid face width or height is at most 20 pixels;
   - `heavy_blur`: any valid face has blur level 2;
   - `heavy_occlusion`: any valid face has occlusion level 2;
   - `difficult_capture`: any valid face has difficult illumination or pose;
   - `baseline`: none of the preceding conditions apply.
3. Within each slice, sort filenames by SHA-256 of `liveshield-wider-v1` plus the filename.
4. Select 40 previously unselected images per slice. Record all applicable slice labels even though
   each selected image occupies one primary selection slot.

WIDER FACE is licensed CC BY-NC-ND. Use the unmodified images only for local, non-commercial
evaluation. Do not commit, redistribute, augment, crop, recompress, or publish derived dataset
assets. Store selected IDs, original-image hashes, source URLs, retrieval date, licence, and results.

`tools/testdata/run-wider-regression.sh` stages only the 200 manifest-selected, byte-verified local
images and their geometry onto a selected test device under `/data/local/tmp`. The media is never
embedded in an APK. The runner reports exact padded-containment, matched-IoU, and unmatched
prediction counts overall and for every applicable slice; these are detector-regression results,
not live-safety or general face-accuracy claims.

### BIV-Priv-Seg: 16-image smoke set

Download the official support-image archive (approximately 15 MiB) and support annotations. It
contains exactly one annotated support image for each of 16 private-object categories with boxes or
segmentation regions. Run all 16 as a fast detector/localization smoke suite.

- Support images: `https://vizwiz.cs.colorado.edu/biv-priv/images/support_images.zip`
- Dataset and annotation links: `https://vizwiz.org/tasks-and-datasets/object-localization/`

This set is CC BY 4.0. Retain attribution in the manifest and any report. Keep the media out of Git
and the release APK even though the licence permits reuse. Sixteen examples are too few for an
accuracy claim; results are individual regression outcomes only.

At the later milestone gate, optionally download the approximately 951 MiB BIV-Priv-Seg query
archive and deterministically select 128 images: seven unique positive images per category plus 16
negative images. That expansion is not required to start implementation.

## Deterministic renderer clips

Create 12 clips: six development and six holdout. Every scenario below must appear at least once;
combined scenarios may share a clip when their expected states remain independently assertable.
Holdout clips use distinct generator seeds, motion paths, dimensions, and colours.

1. Moving protected region over unique per-frame pixels.
2. Multiple overlapping protected regions.
3. A region entering and leaving each frame edge.
4. Rotation and crop transformation.
5. Front-camera-style horizontal mirroring.
6. A privacy decision arriving exactly at its deadline.
7. A decision arriving after its deadline, requiring a full shield.
8. Carried and conservatively expanded protection through a short gap.
9. A visible host becoming uncertain and reverting to protected.
10. Recovery from a full shield while older raw sentinel frames remain queued.

Every source frame contains a unique high-contrast sentinel and frame identifier. The decoded output
is checked frame by frame so even one untreated protected-region pixel or a delayed pre-shield frame
can fail the fixture.

## Consented face-tracking clips

Use at least four consenting adults: at least two appear only in the eight development clips and at
least two appear only in the four holdout clips. Use owned indoor spaces, exclude incidental people,
and retain signed consent separately from the repository. Do not collect these recordings until the
device-validation phase begins. Store raw recordings encrypted and access-controlled outside Git
and the application, assign a documented deletion deadline to each recording, restrict use to local
evaluation, and record deletion completion. Cover every scenario below across the 12 clips; a clip
may cover several scenarios.

1. Unknown person enters and exits slowly.
2. Unknown person enters quickly.
3. Head turns between frontal and profile views.
4. Partial obstruction by a hand or owned object.
5. Unknown person crosses behind the host.
6. Host and unknown person cross or overlap.
7. Host leaves, returns, and must be safely reselected when continuity is uncertain.
8. Small or partly out-of-frame face approaches the camera.
9. Dim or backlit room.
10. Photograph, poster, or device-screen face tests false-face behaviour.

Annotate every positive frame with a persistent session-local object ID, host/unknown role, full
privacy-region polygon, visibility, and the timestamp at which the face becomes protectable.

## Synthetic Priority 2 appearances

Create two distinct appearances for each category: one development and one holdout. Across the 13
categories, balance clean/stationary, moving, rotated/perspective, low-contrast/glare/blurred, and
small/partial/edge conditions. Holdout payloads and templates remain unseen during tuning.

Categories:

1. Machine-readable code
2. Email address
3. Phone number
4. Payment-card-like number
5. Verification code
6. Person name
7. Address
8. Employer
9. School
10. Document
11. Badge
12. Parcel label
13. Device screen

Categories 1–5 test automatic detection. Categories 6–9 test exact session-scoped watchlist
matches. Categories 10–13 test creator-configured privacy zones; they do not claim automatic object
understanding. Reports keep these three mechanisms separate.

Use only fictional people and organisations, reserved example domains, dummy codes, explicitly
test-only card values, and QR/barcode payloads that disclose no secret and do not resolve to a real
person or account. Place matched harmless decoys in the same scenes to expose false-positive
behaviour. For documents, badges, parcels, and screens, label both the sensitive text polygon and
the containing object so narrow protection and conservative expansion can be evaluated separately.

## Fault-injection clips

Create one development and one holdout episode for each fault. Inject it while a sentinel-protected
face or synthetic PII region is visible.

1. Missing analysis result.
2. Late analysis result.
3. Stale or out-of-order timestamp.
4. Detector exception or cancellation.
5. Raw-frame queue reaches capacity.
6. Rendering failure or invalid surface.
7. Camera rebind, orientation change, or lifecycle interruption.
8. Encoder backpressure or reconfiguration.
9. Network disconnect and reconnect.
10. Recovery while old undecided sentinel frames remain queued.

Acceptance is absolute for this group: decoded output may show sanitized video, a full shield, or a
stopped stream, but zero untreated frames.

## Manifest and annotation contract

Each fixture has a manifest record containing:

- schema and corpus version;
- stable fixture ID and development/holdout split;
- source kind and SHA-256 digest;
- scenario IDs;
- consent reference or public dataset/version/licence reference;
- non-sensitive device, lens, resolution, and frame-rate metadata where applicable;
- synthetic payload/template ID or deterministic generator seed;
- scheduled injected faults;
- path to timestamped per-frame truth.

Per-frame JSONL truth records source timestamp, transform metadata, protected polygons, category,
host/unknown role, visibility, protectability or legibility, expected regional/full-shield state,
and required action. It must not contain face embeddings, real PII, stream credentials, or extracted
face crops.

## Public detector-regression data

Public media remains gitignored, is never packaged into the application, and receives a manifest
with source URL, snapshot/version, byte length, checksum, retrieval date, licence, allowed usage,
attribution, deterministic sampling seed, and selected IDs. Tests run without network access after
an explicit fetch/preparation step. Public-image results are detector-only evidence and must not be
presented as proof of live tracking, CameraX timing, fail-private output, device performance, or
participant consent.

Full VizWiz-Priv is deferred because its initial downloads are multi-gigabyte and private content is
distributed in redacted variants that are awkward for our detector objective. VPD-100K is deferred
until its current package, annotation coverage, and licensing are reconciled. Both may inform later
scenario expansion.

## Growth gate

Before describing LiveShield as validated beyond an early development prototype, expand the corpus
to at least 180 independent face episodes, 100 distinct unknown-face appearances, 10,000 annotated
positive face frames, and three physical device tiers; freeze a larger actor/room/payload-disjoint
holdout and inspect decoded output. Report limitations and per-category failures; do not convert a
pass on this initial corpus into a guarantee of anonymity.
