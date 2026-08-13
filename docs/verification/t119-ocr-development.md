# T119 OCR DEVELOPMENT investigation

**Status:** stopped; offline automatic text and configured-watchlist protection are unsupported.  
**Frozen current corpus:** `pii-v1` SHA-256
`9ed88a9bf6b3f66649ea6cc35ed4b74aa30a39fb17d1c3710cd63b39da219bd2`.  
**HOLDOUT:** sealed after deterministic generation; it was not staged, inspected, or evaluated
during T119.

## Corpus domains and controlled changes

The investigation covered two distinct DEVELOPMENT font domains and does not combine their
denominators:

- The legacy 192x128 generator used a 5x7 bitmap font with approximately seven-pixel glyphs and
  one-pixel strokes. Several declared appearances were not representative, and a partial-edge OTP
  did not contain all six visible digits. Results from this domain are diagnostic history only.
- Corpus v2 uses silent 640x360 video, pinned Noto Sans at 32 pixels with two-pixel strokes, pinned
  Pillow 11.3.0/FreeType 2.13.3, complete target visibility, standards-valid offline QR/Data Matrix,
  and deterministic appearance transforms. Both splits were regenerated from code, but only
  DEVELOPMENT was used for diagnosis.

No validator threshold was changed. BGR preprocessing fixed an audited RGB/BGR contract mismatch;
faithful DB postprocessing added rotated mini-box geometry, polygon-masked scoring, and bounded
unclip 1.6. On the legacy DEVELOPMENT corpus, the baseline, BGR-only, and DB-remediated evaluations
all retained QR 8/8, zones 32/32, automatic text 0/32, and watchlists 0/32. On Noto v2 DEVELOPMENT,
the last complete operational PP-OCRv3 run again retained QR 8/8 and zones 32/32 but automatic text
0/32 and watchlists 0/32. These zeros are model-suitability evidence, not a reason to weaken the
structured-PII or exact-watchlist validators.

## Recognition candidates

| Candidate | Operational result | Accuracy result |
|---|---|---|
| Frozen PP-OCRv3 English slim baseline / Paddle Lite 2.11 | Completed the last Noto v2 DEVELOPMENT evidence run | automatic 0/32; watchlist 0/32; QR 8/8; zones 32/32 |
| General PP-OCRv5 / Paddle Lite 2.14-rc | Failed on the first DEVELOPMENT text fixture | Not evaluated; no complete findings |
| OpenCV Zoo CRNN-CH FP16 / OpenCV 4.13 DNN | Native `SIGILL` on first CRNN `Net.forward` | Not evaluated; 0/104 complete observations |
| Targeted `en_PP-OCRv5_mobile_rec` / converted ONNX / proposed custom ORT 1.26 | Host conversion, checker, and Paddle/ORT parity passed; the exact Android package build stopped before compilation because the host lacked `pkg-config` | Not evaluated on Android; no accuracy result |

Failed candidates have no recall, specificity, or SC-009 value. Their incomplete executions must
not be represented as zero-recall accuracy runs.

## Final host-only `en_PP-OCRv5` feasibility gate

The final permitted candidate used the official targeted English recognizer archive only. The
8,007,680-byte archive has SHA-256
`e595b4cf2ffad19fbb5a61ba345d63939577a3ab8717b6e5995642590c9101b4`. Paddle2ONNX 2.1.0,
with optimizer and automatic opset changes disabled, emitted the same opset-17 ONNX bytes twice:
7,843,511 bytes, SHA-256
`70b2450eed39599af6b996c27a2f1a0ef30eeb49f9f66dd3e74f28f652befc89`.
Independent ONNX 1.17 `check_model(..., full_check=True)` passed. The graph has one float input
`[N,3,48,W]`, one float output `[N,T,438]`, no custom domain, and no external tensor data.

ONNX Runtime 1.26 CPU loaded the graph. At widths 160 and 320, zero-input Paddle/ORT maximum
absolute differences were `7.57e-6` and `5.90e-6`, respectively, with identical argmax/CTC index
sequences. Eight fixed DEVELOPMENT-only protected-region crops (email, phone, payment card, OTP,
person name, address, employer, and school) were compared without storing recognized strings. All
were finite, all argmax and collapsed CTC index sequences matched, and the maximum absolute
difference was `1.7166e-5` (`rtol=1e-4`, `atol=2e-5`). This proves converter/runtime numerical
parity for the bounded inputs; it is not recognition-accuracy evidence.

The fixed ARM/type-reduced ORT-format model was 7,997,704 bytes with SHA-256
`c36ffbd17ed13a0e4245f91146782be07ddf1e2965c0f07e987fb850b30ce079`. Its 738-byte required-op
configuration had SHA-256
`924b5ca95aa9dc834e6e5d8ce31eed78fba767a42882c4f6eb4df7b693c1767a`. Host-gate downloads were
39,983,009 bytes against a 100 MB allowance.

The preregistered Android build pinned ORT v1.26.0 commit
`8c546c37b43caaca1fa25db430dab94b901cf277`, NDK 28.0.13004108, Release, ARM64 only,
`minSdk=23`, `targetSdk=36`, CPU only, LTO, Java/shared library, minimal extended runtime, and
reduced type support. Its limits were 3 GiB network, 12 GiB disk, 16 MiB stripped JNI, 18 MiB AAR,
and 25 MiB compressed APK delta including the model. The Docker daemon was unhealthy, so no Docker
reset or mutation was attempted. The official local build reached the exact CMake/vcpkg
configuration, but vcpkg stopped while preparing `abseil` because the host lacked `pkg-config`.
No ORT JNI or AAR was produced.

Observed custom-build footprint before the stop was 2.8 GiB for the pinned NDK, 114 MiB for pinned
CMake, 975 MiB for the shallow ORT source/submodules, 1.2 GiB for the complete isolated source/build
tree at failure, and 137 MiB in the shared vcpkg cache. Host free space remained at least 33 GiB, so
the 12 GiB experiment cap was not approached. The local tools did not expose a trustworthy aggregate
HTTP byte counter; the build stopped early after the shallow source, SDK components, vcpkg registry,
and first dependency downloads, with no evidence that the 3 GiB network allowance was approached.
This is deliberately not reported as an exact transfer-byte total.

Therefore the reproducible-build hashes, JNI/AAR/APK size ceilings, API-23 manifest, ARM64-only
package, 16 KiB ELF load alignment, Java load/API suitability, and offline release-dependency gates
are **UNPROVEN**. Per the preregistered stop rule, `pkg-config` was not installed and the build was
not retried. The exact NDK/CMake acquisitions were uninstalled and the isolated build directory was
moved to Trash; shared caches and the workspace baseline were not removed. There will be no further
OCR candidate, device run, HOLDOUT access, threshold change, or fixture tuning in this workstream.

## Current product boundary

- Supported in scoped DEVELOPMENT evidence: standards-valid QR and configured full-area zones.
- Unsupported: automatic email, phone, payment-card-like, verification-code, and configured
  watchlist findings.
- SC-002 is **UNMET** and SC-009 is **UNMET**.
- Production Priority 2 scheduler/policy wiring remains present, but unsupported OCR findings do
  not become supported merely because the composition path exists.

The workstream is frozen on the PP-OCRv3/Paddle Lite 2.11 unsupported baseline. Reopening OCR would
require new explicit scope and authority; the failed Android feasibility gate must not be bypassed
by adding a stock ORT dependency, raising `minSdk`, weakening package limits, or using HOLDOUT.
