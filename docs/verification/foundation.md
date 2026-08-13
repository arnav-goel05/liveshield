# Foundation Verification

**Executed:** 2026-08-13  
**Scope:** T026 foundational privacy-domain contracts, test-data safety tooling, and the explicit
`public-v1` offline detector corpus.

## Privacy-domain JVM tests

The tests were cleaned and forced to execute rather than accepted from Gradle's up-to-date cache:

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew :privacy-domain:cleanTest :privacy-domain:test --rerun-tasks --no-daemon
```

Result: `BUILD SUCCESSFUL in 8s`; all four actionable tasks executed.

| Suite | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| `FrameDecisionStoreTest` | 6 | 0 | 0 | 0 |
| `FramePrivacyDecisionTest` | 2 | 0 | 0 | 0 |
| `HostSelectionControllerTest` | 7 | 0 | 0 | 0 |
| `PrivacyValueObjectsTest` | 6 | 0 | 0 | 0 |
| `LiveSessionStateMachineTest` | 7 | 0 | 0 | 0 |
| `LiveSessionTest` | 4 | 0 | 0 | 0 |
| **Total** | **32** | **0** | **0** | **0** |

This verifies the currently implemented immutable value objects, full-shield default, bounded
decision-store miss/stale/future/expiry/eviction behavior, host selection, and legal session-state
transitions at the JVM boundary. It does not substitute for later camera/GPU/encoded-output tests.

After the shared build setup enabled the required core-library desugaring consistently, the entire
multi-module JVM test graph was also forced to execute:

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew test --rerun-tasks --no-daemon
```

Result: `BUILD SUCCESSFUL in 40s`; 106/106 actionable tasks executed. Android manifest processing
reported one non-failing warning that the debug test manifest's `usesCleartextTraffic` replacement
had no other declaration to replace. No test failed or was skipped because of that warning.

## Test-data safety and reproducibility tests

The complete standard-library-only test-data suite was forced through discovery:

```bash
python3 -m unittest discover -s tools/testdata/tests -v
```

Result: `Ran 30 tests in 0.071s` and `OK`; zero failures, errors, or skips.

The tests cover schema contracts, exact fixture counts, source digests, truth outcomes, forbidden
sensitive fields, audio rejection, development/holdout leakage, consented-capture controls,
archive byte/hash rejection, deterministic WIDER selection, official zero-face parsing, BIV
conversion/attribution, ZIP traversal rejection, JPEG header dimensions, and the evaluation-only
`BIV_PRIVATE_OBJECT` boundary. That category is accepted only for `LICENSED_PUBLIC` fixtures in
group `BIV_PRIV_SEG`; synthetic and consented fixtures are rejected if they use it.

## Exact public corpus validation

```bash
python3 tools/testdata/validate_manifest.py \
  test-fixtures/manifests/public-v1.jsonl \
  --media-root evaluation-data/public/media \
  --truth-root test-fixtures/annotations \
  --profile public-v1 \
  --expected-count 216
```

Result: `validated 216 fixture(s): test-fixtures/manifests/public-v1.jsonl`.

| Evidence | Result |
|---|---:|
| WIDER FACE regression fixtures | 200 |
| BIV-Priv-Seg smoke fixtures | 16 |
| Unique fixture IDs | 216 |
| Unique selected media paths | 216 |
| Existing truth files | 216 |
| WIDER primary selection slots | 40 each: small, heavy blur, heavy occlusion, difficult capture, baseline |
| Selected media bytes | 39,375,761 |
| Manifest SHA-256 | `e4fda1c2eb8b50dd80ae9249659290567b65ac2c05e16c57b269d8b72494d6c2` |

Running the preparation script twice produced that same manifest SHA-256. The validator recomputed
each selected image digest and byte length and loaded every linked truth file.

## Archive locks, storage, and licences

The exact URLs, byte lengths, SHA-256 locks, retrieval date, BIV upstream-checksum limitation, and
attribution boundary are recorded in [`docs/testing/public-data-locks.md`](../testing/public-data-locks.md).

| Local data | Disk use after preparation |
|---|---:|
| Verified source archives | Removed after validation; reproducibly downloadable from recorded locks |
| Only the 216 selected images | 38 MiB |
| Complete retained `evaluation-data/public/` workspace | 39 MiB |
| Generated truth files | 1.4 MiB |
| Generated manifests and attribution | 260 KiB |
| Free workspace volume after emergency reproducible-cache/archive cleanup | approximately 2.8 GiB |

The source archives were never fully extracted. After their hashes, deterministic output, and all
216 selected-media digests were verified, the reproducibly downloadable archives were removed to
respond to critical system-wide disk pressure. The selected evaluation media remains under the
gitignored `evaluation-data/public/` directory; rerunning the explicit fetch step restores the
archives from the locks below.

After later concurrent emulator/instrumentation work outside this corpus, final observed free space
fell to approximately 2.5 GiB while `evaluation-data/public/` remained 404 MiB. Further downloads or
emulator runs must wait until that shared temporary/build storage is reconciled.

- WIDER FACE is CC BY-NC-ND 4.0 and is restricted here to local, non-commercial, unmodified
  evaluation. Selected JPEG members are extracted byte-for-byte; no crop, resize, recompression,
  augmentation, redistribution, or APK packaging occurs.
- BIV-Priv-Seg is CC BY 4.0. Attribution is retained in
  `test-fixtures/manifests/BIV_PRIV_SEG_ATTRIBUTION.md`.
- The 16 BIV classes remain their official safe labels in fixture scenario IDs and use the distinct
  evaluation-only truth category; they are not relabeled as supported LiveShield objects.

## Evidence boundary

These public still images are **offline detector regression and localization-smoke evidence only**.
They do not demonstrate or accept live tracking, CameraX timestamps, renderer behavior,
fail-private operation, encoded-output safety, phone performance, participant consent, or TikTok
publication. Those claims remain blocked on their later task-specific tests and physical-device
evidence.
