# Quickstart verification audit

**Audit date:** 2026-08-13  
**Status:** HISTORICAL AUDIT — the former T109 clean-checkout acceptance task was retired before the
initial private-repository commit

## Result

This document preserves the pre-commit quickstart audit. The former requirement to execute the
entire guide from a separate clean checkout was removed from the task ledger by user decision.
Repository commit and push are ordinary source-control setup, not evidence that every command in
this guide passed.

The audited command sequence is the specification
[quickstart](../../specs/001-live-privacy-protection/quickstart.md).

This audit did not run Gradle, Docker, an emulator, a physical device, public downloads,
recruitment, or consented capture. Existing reports from earlier local runs remain bounded evidence
for those runs; they are not evidence that the current quickstart passed from a clean checkout.

## Command audit

| Quickstart area | Current repository entry point | Classification | Current boundary or blocker |
|---|---|---|---|
| Repository provenance | `git rev-parse`, `git status` | Informational | Record the revision and disclose local changes when running the guide |
| Host test-data tests | Python `unittest` discovery under `tools/testdata/tests/` | Executable after checkout exists | Not run in this audit |
| Renderer/fault generation | `generate_system_fixtures.py` | Executable; FFmpeg required | Produces 32 silent synthetic records |
| Priority 2 generation | `generate_priority2_fixtures.py` | Executable; FFmpeg required | Produces 26 fictional silent records |
| Public fetch | `fetch-public-data.sh` with four explicit assets | Network/licence-bound | Pinned byte lengths/hashes; media remains ignored |
| Public preparation | `prepare_public_manifest.py` | Executable after download | Produces 216 public detector-only records |
| Manifest validation | `validate_manifest.py` with explicit profiles | Executable | Current manifests total 274, not 286 |
| Full initial corpus | `full-v1` validation | Blocked | Missing authorized 12-record `face-v1.jsonl` and truth |
| Host Gradle/privacy gate | `test`, lint variants, Checkstyle, privacy boundaries, APK assembly | Executable after checkout exists | Not run here by instruction |
| Android connected suites | module `connectedDebugAndroidTest` tasks | Device-bound | Requires compatible emulator/device and serialized run |
| WIDER regression | `run-wider-regression.sh` | Device/data-bound | Requires prepared 200-image media and vision test APK |
| BIV/Priority 2 device corpus | Android test classes | Partly stale/manual | No reviewed single staging runner exists |
| API 36 RTMP integration | `run-api36-rtmp-integration.sh` | Emulator/host-tool-bound | Existing T084 report is not a clean-checkout rerun |
| Trusted-LAN demonstration | pinned Docker Compose relay plus installed app | External/manual | Requires Docker, LAN, camera flow, decoded viewer evidence |
| Encoded fail-private evidence | current video-pipeline instrumentation | Device-bound | Old `-PencodedFixture` quickstart command was stale and removed |
| Physical performance | `:benchmark:connectedCheck` | Device-blocked | Physical benchmark execution remains absent; prolonged thermal/battery measurements were not collected |
| Usability | T071/T072 | Human-blocked | Recruitment and outcome report absent |
| Consented face corpus | T106/T107 | Human/privacy-blocked | Capture must wait for reviewed protocol and external store |
| Growth corpus | T108 | Later human/data gate | Not part of initial 286-record validation |
| TikTok publication | T085/T086 | Optional external-account gate | No availability record or credentials test exists |
| Corpus/privacy acceptance | T110–T112 | Downstream blocked | Depend on missing corpus and complete rerun artifacts |

## Current safe fixture inventory

The pre-commit audit found these three safe fixture manifests:

| Manifest | Records | Groups | SHA-256 |
|---|---:|---|---|
| `public-v1.jsonl` | 216 | 200 WIDER FACE, 16 BIV-Priv-Seg | `e0bbbf99dc5f0cc2551536e8ef99f87363b397c7731f564d961700b77c79d67a` |
| `system-v1.jsonl` | 32 | 12 renderer, 20 fault injection | `c69267d5c769dbb0b65b2d1e9600e1fbb5e198f5658d320861400277296640f2` |
| `pii-v1.jsonl` | 26 | 26 synthetic Priority 2 | `089dc495b70499c97436253c605b5b4178cc6c5f3845cc5ec3f7cf96b7fdb8d8` |
| **Present total** | **274** | **216 public + 58 synthetic** | — |

The missing 12 consented-face records are the difference between 274 and the planned 286. A
placeholder manifest would falsely imply capture authorization and is prohibited.

## Stale statements corrected in the specification quickstart

- Android SDK 36 was corrected to compile SDK 37 with an API 36 target/runtime distinction.
- Public fetch/preparation scripts were changed from “future/nonexistent” to their exact current
  arguments and content-lock workflow.
- The corpus sequence now includes the current system and Priority 2 generators and exact validator
  profiles.
- Generic `connectedCheck` and the nonexistent future `-PencodedFixture` workflow were replaced by
  current module tasks, bounded runners, and an explicit manual-staging limitation.
- The pinned MediaMTX runner is separated from the manual Docker trusted-LAN demonstration.
- Public, synthetic, emulator, physical-device, consented, usability, and optional TikTok evidence
  are explicitly kept separate.

## Current use

Use the specification quickstart as an operator guide and retain exact revision, commands, exits,
counts, versions, hashes, and evidence boundaries when running any section. Unrun device, human,
account, corpus, or external sections remain open under their own tasks; committing the repository
does not make those sections pass.

## Audit validation

The updated quickstart and this report were checked for local Markdown links, fenced shell syntax,
referenced repository paths, and whitespace errors. No runtime command in the quickstart was
executed as part of this audit.
