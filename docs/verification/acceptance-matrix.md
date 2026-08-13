# Acceptance matrix

**Snapshot:** 2026-08-13  
**Rule:** `MET` requires direct evidence at the requirement's stated boundary. `PARTIAL` means an
implementation or narrower test exists but a material boundary is unproven. `UNMET` means the
required evidence or external input does not exist. This matrix does not promote adjacent unit,
synthetic, emulator, or public-regression evidence into a broader claim.

## Functional requirements

| Requirement | Status | Evidence and remaining boundary |
|---|---|---|
| FR-001 manual host plus readiness before start | MET | Setup/readiness coordinator tests and API 36 setup/solo-flow tests keep Start disabled until permission, fresh host, privacy effect, transform, renderer, and encoder readiness. |
| FR-002 session-only host; no identity/biometric persistence | MET | Host selection/continuity and privacy-policy tests; static/privacy audit finds geometry/session IDs only and no embeddings, recognition, enrollment, or persistence API. |
| FR-003 protect every non-host face | PARTIAL | Face policy/coordinator tests and YuNet public regression are green; no consented live face corpus or SC-001 coverage result exists. |
| FR-004 protect a new face before delivery | PARTIAL | Entry tracks are protected and buffered output requires an exact decision; no live annotated first-protectable-to-decoded-frame corpus exists. |
| FR-005 carry or expand through short gaps | MET | Deterministic policy and coordinator suites verify bounded prediction, carried/expanded masks, and stale full shield; encoded system fixtures cover carried/expanded decisions. |
| FR-006 revoke uncertain host and require explicit reselection | MET | Association, continuity, host-reselection, privacy-policy, and session-coordinator suites cover crossing/merge/split/expiry and no automatic transfer. |
| FR-007 automatically protect supported codes and structured PII | PARTIAL | Priority 2 is wired into the production scheduler/policy composition and QR has scoped evidence, but automatic text remains unsupported after T119; historical findings/decoded evidence observed no text-pattern protection. |
| FR-008 session watchlists, zones, narrow safe text masks | PARTIAL | Production configuration/policy wiring and configured-zone evidence exist, but configured-watchlist OCR remains unsupported; BIV was 0/16. |
| FR-009 retain sensitive-region protection through misses | MET | Independent-lane freshness/carry/expand/stale/scene-change policy tests pass with conservative full shield on expiry/failure. |
| FR-010 private healthy/degraded/shielding indication | MET | Production `LiveActivity` receives payload-free lifecycle, protection-health, and publisher-health updates. API 36 verifies honest labels, the private no-surface hierarchy, focus/contrast, service-visible changed-state notification, and unchanged-state silence. |
| FR-011 shield or stop, never send untreated frame | MET for inspected paths | Renderer/codec positive control and 20 decoded fault fixtures observed zero forbidden raw matches; physical/OEM and all real failure combinations remain later validation. |
| FR-012 discard unsafe buffered frames during recovery/lifecycle | MET | The production coordinator composes renderer queue depth/recovery, thermal state, and scene change. Component and API 36 composition tests cover capacity, unsafe recovery, scene change, lifecycle stop, verified recovery, and no old-frame release. |
| FR-013 ephemeral raw buffers; controlled evaluation exception | MET in app; external exception unused | Ownership/static gates and no default raw recorder pass. Capture protocol exists, but no consented raw recording has been authorized or retained. |
| FR-014 sanitized health/video only; no microphone audio | MET for inspected artifacts | Capability-bound transport, boundary gate, manifests, AppOps/log audits, decoded MP4, RTMP `ffprobe`, and privacy audit show video-only and no microphone path. |
| FR-015 honest status without anonymity guarantee | MET | Scope disclosure and live-status resource/UI tests; README and app language state visual-only scope and unsupported settings. |
| FR-016 safe end and ephemeral resource release | MET | Session/output/queue cleanup tests plus API 36 solo-flow end state and process/camera cleanup evidence. |
| FR-017 private controls excluded from viewer picture | MET for architecture/tested UI | LiveActivity recursively contains no preview/media surface; camera output is renderer-owned; UI controls are not composed into encoded fixtures. |
| FR-018 non-sensitive evidence sufficient to detect bypass | PARTIAL | Payload-free health/metrics, decoded pixel oracle, packet inspection, and fault evidence exist; full physical-device/session interval accounting remains open. |
| FR-019 excluded spoken/chat/consent/recognition/replacement/third-party-app control | MET | Dependency, manifest, public API, UI language, and privacy-boundary audits preserve all exclusions. |
| FR-020 controlled indoor solo scope only | MET | Mandatory disclosure gates camera/setup and explicitly rejects public, outdoor-moving, and dense-crowd claims; usability protocol retains this scope. |
| FR-021 external sanitized broadcaster | MET for controlled destination | Production API 36 controller/queue/RootEncoder published to pinned MediaMTX and a WebRTC viewer played it; no TikTok mobile camera injection exists. |
| FR-022 external-stream eligibility explicit | MET | Destination UI and tests state TikTok credentials are issued only to eligible accounts and cannot be obtained or bypassed. |
| FR-023 controlled compatible demo distinct from TikTok | MET | MediaMTX destination is labeled not TikTok; production RTMP, independent `ffprobe`, and controlled WebRTC viewer evidence are green. |
| FR-024 masked, ephemeral, cleared destination secrets | MET for inspected flow | Secret ownership/zeroization tests and final API 36 destination audit found the fictional secret zero times in logs/private files after recreation/close. |
| FR-025 destination failure stops or remains fail-private | MET for inspected production composition | Typed asynchronous publisher callbacks reach the coordinator and private UI; connection/reconnect degrades, terminal failure stops safely, and recovery requires fresh configuration/key media. Stream-session tests clear delayed units and no alternate raw path exists. Real TikTok failure evidence remains external. |
| FR-026 thermal degradation, shield/stop, safe recovery | PARTIAL | The real production thermal controller and renderer/scene health feed the scheduler and coordinator; typed-composition tests cover warning, severe shield, unsafe recovery, and verified recovery. Real induced thermal on physical devices remains T105. |

## Success criteria

| Criterion | Status | Current numerator/evidence and blocker |
|---|---|---|
| SC-001 face coverage and ≤100 ms gap at required scale | UNMET | No 180 episodes, 100 unknown appearances, 10,000 positive frames, or three physical tiers. WIDER is 200 still images only; consented face corpus is absent. |
| SC-002 ≥95% Priority 2 protection, lanes separate | UNMET | T119 Noto v2 DEVELOPMENT: automatic text 0/32, watchlist 0/32, QR 8/8, zones 32/32; the current HOLDOUT remains sealed. Production wiring exists but does not substitute for detector accuracy. |
| SC-003 zero untreated frames in every induced failure | PARTIAL | 20 synthetic fault fixtures: 144 protected decoded frames, maximum forbidden-raw ratio 0.0, positive control rejected. Network was control-only and physical failure combinations were not all induced. |
| SC-004 ≥9/10 first-time users complete core flow | UNMET | Protocol is preregistered; no participants recruited or run. |
| SC-005 ≥9/10 distinguish health states | UNMET | Deterministic UI/state tests exist; no participant comprehension data. |
| SC-006 protection-ready within 30 seconds | UNMET | API 36 setup readiness has passed, but no specified first-time-user timing distribution exists. |
| SC-007 ≥95% visually stable continuous intervals | UNMET | Metric oracles pass, but no consented annotated continuous-track intervals exist. |
| SC-008 100% output intervals accounted without sensitive retention | PARTIAL | Deterministic decoded fixtures and payload-free health evidence account for their scoped outputs; no full long physical session/accounting audit exists. |
| SC-009 ≥90% reliable text cases retain outside content | UNMET | Mapper/evaluator tests encode the metric, but Noto v2 DEVELOPMENT produced automatic text 0/32 and watchlists 0/32. Failed PP-OCRv5 and CRNN candidates produced no complete accuracy result; HOLDOUT remains sealed. |
| SC-010 ≥9/10 users complete destination flow | UNMET | API 36 UI flow is green; no participant study. |
| SC-011 viewer/TikTok intervals sanitized; zero audio | PARTIAL | Controlled MediaMTX probe found one H.264 stream, 15 video packets, zero audio, and a playing viewer. Full interval accounting and any authorized TikTok run are absent. |
| SC-012 zero destination-secret occurrences after session | MET for inspected controlled flow | Remediated API 36 destination run: exact fictional secret absent from instrumentation, logcat, saved/recreated state, and private files; secret boundary tests 10/10. |
| SC-013 all 286 corpus items complete before reporting | UNMET | 274/286 present and valid; missing exactly 12 consented face items plus their manifest/truth/report. |
| SC-014 zero created-fixture development/holdout leakage | PARTIAL | Current system and Priority 2 manifests have zero designated seed/payload/source-group/room-motion overlap. Planned consented face split does not exist and cannot yet be included. |

## Evidence index

- Face detector and temporal boundary: [`us2-faces.md`](us2-faces.md)
- Priority 2 detector/configuration findings: [`us3-priority-two.md`](us3-priority-two.md)
- Priority 2 encoded component output: [`us3-encoded-output.md`](us3-encoded-output.md)
- Frozen T119 OCR DEVELOPMENT boundary: [`t119-ocr-development.md`](t119-ocr-development.md)
- Fail-private decoded output: [`us4-fail-private.md`](us4-fail-private.md)
- Controlled RTMP delay/packet/viewer: [`us6-mediamtx.md`](us6-mediamtx.md)
- Current artifact privacy review: [`privacy-audit.md`](privacy-audit.md)
- Offline dependency and Paddle status: [`paddle-ocr-audit.md`](paddle-ocr-audit.md)
- Setup/readiness evidence: [`setup.md`](setup.md)

The matrix must be refreshed after a future qualifying OCR DEVELOPMENT result and separately
authorized HOLDOUT evaluation, physical-device work, the consented corpus, usability study, TikTok
eligibility decision, and a new final gate over the converged workspace. The recorded T115 gate
predates T118–T122 and did not execute the root benchmark-inclusive `connectedCheck`, so it is stale
and reopened rather than evidence of current final completion.
No unmet row may be converted to `MET` merely because its implementation compiles or a narrower
synthetic test passes.
