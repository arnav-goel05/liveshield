# US2 face-protection preliminary evidence

**Executed:** 2026-08-13  
**Status:** preliminary detector-regression and deterministic-logic evidence only  
**Device evidence:** Android API 36 arm64 emulator for the WIDER FACE run  
**Outcome:** directional evidence recorded; SC-001 and SC-007 are not established

## Evidence boundary

This report keeps two different evidence layers separate:

1. The WIDER FACE result is a static-image regression of the offline YuNet detector. It measures
   localization against public still-image annotations, not protection in live or encoded video.
2. The temporal results are hand-computable synthetic unit fixtures for the metric, association,
   host-continuity, and coordinator logic. Their artificial timestamps are not device latency.

No consented face-tracking clip has been captured or evaluated. Consequently this report contains
no measured physical-device detection-to-protection delay, live longest unprotected gap, live
fragmentation rate, or live ID-switch rate.

## Static WIDER FACE regression

One fresh instrumentation execution completed in 11.399 seconds with `OK (2 tests)`. The runner
processed all 200 selected, locally staged WIDER FACE validation images. It verified each selected
JPEG's byte length and SHA-256 before inference, loaded its linked truth, and removed the staged
public media after the run. The frozen detector settings were score threshold `0.60` and NMS
threshold `0.30`; the reporting oracle used match IoU `0.20` and padding fraction `0.25`.

| Slice | Images | Padded containment | One-to-one matched | False positives | Mean matched IoU |
|---|---:|---:|---:|---:|---:|
| Overall | 200 | 1,344 / 3,113 | 1,443 / 3,113 | 81 / 1,524 predictions | 0.711820 |
| Small | 89 | 944 / 2,599 | 1,026 / 2,599 | 69 / 1,095 predictions | 0.684460 |
| Heavy blur | 108 | 993 / 2,714 | 1,078 / 2,714 | 74 / 1,152 predictions | 0.687661 |
| Heavy occlusion | 103 | 997 / 2,678 | 1,086 / 2,678 | 75 / 1,161 predictions | 0.692083 |
| Difficult capture | 101 | 893 / 2,159 | 960 / 2,159 | 51 / 1,011 predictions | 0.699299 |
| Baseline | 40 | 107 / 111 | 108 / 111 | 1 / 109 predictions | 0.826628 |

The five slice memberships overlap. Their image, truth-face, prediction, and outcome counts must
not be added together. The 40-image primary selection slots describe deterministic selection;
images can acquire multiple reporting labels from their annotations.

`Padded containment` means the annotated face is fully inside a prediction expanded by 25% of the
prediction width and height on every side. `One-to-one matched` uses one prediction at most once
and accepts IoU at least 0.20 or padded containment. These are regression metrics, not a claim that
43% containment is sufficient for viewer privacy. An unmatched prediction is counted as a false
positive; the table deliberately reports its numerator and prediction denominator.

## Deterministic temporal logic evidence

These fixtures verify that the Java metric and policy code responds deterministically to declared
sequences. Durations below are tiny artificial nanosecond values chosen for unit arithmetic. They
must not be presented as camera, inference, renderer, encoder, or viewer timing.

| Deterministic fixture or suite | Actually asserted result | Scope limit |
|---|---|---|
| Hand-computed crossing metric fixture | 4 / 6 positive frames protected; longest gap 2 frames / 80 artificial ns; 1 fragmentation; 1 ID switch | Metric-oracle test only; it intentionally contains a gap and switch |
| Three-episode aggregate fixture | 6 / 8 positive frames protected; micro coverage 0.75; macro episode coverage 5 / 6; maximum gap 2 frames / 80 artificial ns; 1 fragmentation; 1 ID switch; 1 episode with a gap | Two positive synthetic episodes plus one zero-positive episode; adjacent frames are not independent samples |
| Synthetic decoded-PTS annotation join | 2 / 3 unknown-face frames protected; longest gap 1 frame / 40 artificial ns; 1 fragmentation; 1 ID switch; maximum synthetic PTS join delta 2 ns | Evaluator correctness test, not decoded production video or a latency measurement |
| Face association suite | 9 / 9 retained JVM tests passed: entry, bounded short-gap prediction, crossing ambiguity, merge/split, impossible hint reuse, exact expiry, ordering, configuration, and reset | Contract scenarios; no corpus-level ID-switch numerator or denominator |
| Host continuity suite | 5 / 5 retained JVM tests passed; ambiguity, expiry, replacement, and return do not silently transfer host visibility | Policy logic only; no participant or camera evidence |
| Face analysis coordinator suite | 10 / 10 retained JVM tests passed; unknown/ambiguous faces remain protected, brief absence uses bounded prediction, and malformed/out-of-order input shields | Synthetic observations, not real detector frames or decoded pixels |

The numeric ID-switch results above are expected outputs of metric-oracle fixtures, not observed
errors from the production associator. The association tests instead prove specific conservative
transitions: a crossing becomes ambiguous and protected, an impossible detector-hint jump gets a
new protected session track, and an expired host cannot pass visibility to a replacement. They do
not yield an empirical ID-switch rate.

## Renderer and encoded-output fixture context

The deterministic `system-v1` inventory contains 12 renderer and 20 fault fixtures, split evenly
between development and holdout. Its manifest includes decision-at-deadline, carried/expanded gap,
host-uncertain protection, and old-queue recovery scenarios. This inventory is useful for renderer
and queue behavior, but it contains synthetic sentinels rather than consented tracked people.

The separately retained T048 API 36 evidence inspected 144 encoded frames from all 20 fault truth
fixtures and observed zero forbidden raw-pixel matches in protected regions; 16 terminal truth
records required stopped output. As documented in
[US4 fail-private evidence](us4-fail-private.md), that run supplied truth-driven outcomes and did
not inject every production failure seam. It neither measures face detection delay nor upgrades the
synthetic temporal results into live tracking evidence.

## Reproduction commands and content locks

Public corpus validation:

```sh
python3 tools/testdata/validate_manifest.py \
  test-fixtures/manifests/public-v1.jsonl \
  --media-root evaluation-data/public/media \
  --truth-root test-fixtures/annotations \
  --profile public-v1 --expected-count 216
```

Result after the run: `validated 216 fixture(s)`. This total contains the 200 WIDER regression
fixtures plus 16 separately scoped BIV-Priv-Seg smoke fixtures.

WIDER instrumentation:

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew :vision:assembleDebugAndroidTest --no-daemon
ANDROID_SERIAL=emulator-5554 tools/testdata/run-wider-regression.sh
```

Deterministic temporal suites can be reproduced without public media:

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew \
  :test-fixtures:test \
  :vision:testDebugUnitTest \
  :privacy-domain:test \
  :video-pipeline:testDebugUnitTest \
  --no-daemon
```

| Durable content | SHA-256 |
|---|---|
| `test-fixtures/manifests/public-v1.jsonl` | `e0bbbf99dc5f0cc2551536e8ef99f87363b397c7731f564d961700b77c79d67a` |
| Ordered WIDER truth-file digest listing | `923dc458bf02cfffcde89d7f556fedb4c1c49ceb31b9c9938e511ff549d47fcc` |
| Ordered selected WIDER media digest listing | `6b792b0d64690aaf5d6ca3cf89b60c89b7fa327d36cecbd340bc05dbf72f7bdd` |
| YuNet model | `8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4` |
| WIDER regression runner test | `6a8339ed867a4716ca49eb2e14e8621778ec88d9cc7941cac5d0bb2bb7e2ffb8` |
| `system-v1` manifest | `c69267d5c769dbb0b65b2d1e9600e1fbb5e198f5658d320861400277296640f2` |

The WIDER selection remains local, unmodified, non-commercial evaluation under CC BY-NC-ND 4.0.
The images are outside Git and the APK. Dataset provenance, archive locks, and the non-redistribution
boundary are in [public-data locks](../testing/public-data-locks.md).

## Success-criterion status

- **SC-001: not established.** The required 180 independent face episodes, 100 distinct unknown
  appearances, 10,000 annotated positive frames, three physical device tiers, 99% decoded-frame
  coverage, and at-most-100-ms live gap evidence do not exist yet. WIDER supplies 200 still images,
  not independent live episodes or decoded protected frames.
- **SC-007: not established.** No consented continuous track intervals have been decoded and
  annotated, so the required at-least-95% visually stable interval result and flicker observation
  have not been measured.

The next evidence step is the separately governed consented-capture phase in
[the consented-capture protocol](../testing/consented-capture-protocol.md), followed by per-frame
decoded-output evaluation with the existing annotation and temporal-metric tools. Until then, the
results in this report are preliminary engineering evidence only.
