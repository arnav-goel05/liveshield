# US3 Priority 2 findings evidence

> Historical boundary: the table below predates T119 corpus v2 and must not be treated as current
> Noto v2 HOLDOUT evidence. The frozen v2 manifest is `9ed88a9b...9bd2`; its HOLDOUT remained sealed
> throughout T119. See `t119-ocr-development.md` for the current automatic/watchlist support state.

**Executed:** 2026-08-13  
**Device boundary:** Android API 36 ARM64 emulator  
**Result:** the real detector/configuration runner completed all 208 synthetic observations, but
only the configured-zone lane detected its targets. This does **not** meet the 95% Priority 2
criterion.

## What ran

`PriorityTwoFindingsDeviceTest` processed the frozen `pii-v1` manifest: 26 silent synthetic MP4
fixtures, split into 13 development and 13 holdout fixtures, with eight truth timestamps per
fixture. It used:

- the real offline PaddleOCR analyzer plus `StructuredPiiValidator`/`OcrPrivacyClassifier` for
  email, phone, payment-card-like, verification-code, and configured-watchlist cases;
- the real offline ZXing analyzer for the standards-valid, deterministically generated QR and Data
  Matrix fixtures; and
- `SessionPrivacyConfiguration` plus `PriorityTwoPolicy` for creator-configured full-area zones.

Staged MP4s were hash-verified, copied to app-private cache, reverified for length and SHA-256, and
opened through a descriptor held for the complete timestamp decode. Every private media copy was
deleted after its fixture. The findings output was a basename-only file in app-owned `filesDir`,
pulled only through `run-as`. It contained 208 unique fixture/frame keys, 208/208 released analysis
inputs, zero typed analyzer failures, and 64 findings. No recognized text, barcode payload,
watchlist value, pixels, or frame bytes were written to the findings file.

The instrumentation class passed 6/6 tests in 55.983 seconds. Logcat contained no fatal exception
or native abort. Paddle Lite emitted its already-audited optimizer/runtime version warnings; native
initialization and all inference calls nevertheless completed.

## Evaluator results

The evaluator intentionally emits no overall average. Each row below is retained so that a strong
zone result cannot hide an unsupported automatic or watchlist category. `0/0` false positives is
undefined because the detector emitted no findings; it is not evidence of specificity.

| Split | Lane | Category | Recall | Localization coverage | Excessive mask | False positives |
|---|---|---|---:|---:|---:|---:|
| DEVELOPMENT | AUTOMATIC_PATTERN | EMAIL | 0/8 | 0/142208 | 0/524288 | 0/0 |
| DEVELOPMENT | AUTOMATIC_PATTERN | MACHINE_READABLE_CODE | 0/8 | 0/66304 | 0/524288 | 0/0 |
| DEVELOPMENT | AUTOMATIC_PATTERN | PAYMENT_CARD | 0/8 | 0/149952 | 0/524288 | 0/0 |
| DEVELOPMENT | AUTOMATIC_PATTERN | PHONE | 0/8 | 0/109312 | 0/524288 | 0/0 |
| DEVELOPMENT | AUTOMATIC_PATTERN | VERIFICATION_CODE | 0/8 | 0/50592 | 0/524288 | 0/0 |
| DEVELOPMENT | CONFIGURED_WATCHLIST | ADDRESS | 0/8 | 0/142208 | 0/524288 | 0/0 |
| DEVELOPMENT | CONFIGURED_WATCHLIST | EMPLOYER | 0/8 | 0/109312 | 0/524288 | 0/0 |
| DEVELOPMENT | CONFIGURED_WATCHLIST | PERSON_NAME | 0/8 | 0/152064 | 0/524288 | 0/0 |
| DEVELOPMENT | CONFIGURED_WATCHLIST | SCHOOL | 0/8 | 0/149952 | 0/524288 | 0/0 |
| DEVELOPMENT | CONFIGURED_ZONE | BADGE | 8/8 | 152064/152064 | 0/524288 | 0/8 |
| DEVELOPMENT | CONFIGURED_ZONE | DEVICE_SCREEN | 8/8 | 109312/109312 | 0/524288 | 0/8 |
| DEVELOPMENT | CONFIGURED_ZONE | DOCUMENT | 8/8 | 50592/50592 | 7616/524288 | 0/8 |
| DEVELOPMENT | CONFIGURED_ZONE | PARCEL_LABEL | 8/8 | 142208/142208 | 22528/524288 | 0/8 |
| HOLDOUT | AUTOMATIC_PATTERN | EMAIL | 0/8 | 0/149952 | 0/524288 | 0/0 |
| HOLDOUT | AUTOMATIC_PATTERN | MACHINE_READABLE_CODE | 0/8 | 0/66304 | 0/524288 | 0/0 |
| HOLDOUT | AUTOMATIC_PATTERN | PAYMENT_CARD | 0/8 | 0/152064 | 0/524288 | 0/0 |
| HOLDOUT | AUTOMATIC_PATTERN | PHONE | 0/8 | 0/50592 | 0/524288 | 0/0 |
| HOLDOUT | AUTOMATIC_PATTERN | VERIFICATION_CODE | 0/8 | 0/142208 | 0/524288 | 0/0 |
| HOLDOUT | CONFIGURED_WATCHLIST | ADDRESS | 0/8 | 0/149952 | 0/524288 | 0/0 |
| HOLDOUT | CONFIGURED_WATCHLIST | EMPLOYER | 0/8 | 0/50592 | 0/524288 | 0/0 |
| HOLDOUT | CONFIGURED_WATCHLIST | PERSON_NAME | 0/8 | 0/109312 | 0/524288 | 0/0 |
| HOLDOUT | CONFIGURED_WATCHLIST | SCHOOL | 0/8 | 0/152064 | 0/524288 | 0/0 |
| HOLDOUT | CONFIGURED_ZONE | BADGE | 8/8 | 109312/109312 | 0/524288 | 0/8 |
| HOLDOUT | CONFIGURED_ZONE | DEVICE_SCREEN | 8/8 | 50592/50592 | 7616/524288 | 0/8 |
| HOLDOUT | CONFIGURED_ZONE | DOCUMENT | 8/8 | 142208/142208 | 22528/524288 | 0/8 |
| HOLDOUT | CONFIGURED_ZONE | PARCEL_LABEL | 8/8 | 149952/149952 | 0/524288 | 0/8 |

Lane totals are 0/80 automatic-pattern targets, 0/64 configured-watchlist targets, and 64/64
configured-zone targets. The zone result proves that the complete configured region persists and
is not narrowed by missing OCR. The non-zero excess-mask results are the expected cost of one fixed
creator zone covering a moving target. Automatic and watchlist results show that the current OCR
and barcode path did not localize these small moving synthetic elements; no threshold was tuned and
the authorized execution was not retried.

## Separate BIV smoke

The separately executed `BivPrivSmokeTest` loaded the real offline OCR runtime and released all
16/16 licensed public inputs, but emitted no regions: matched objects 0/16, localization coverage
0/16, excessive-mask numerator/denominator 0/0, and false-positive numerator/denominator 0/0.
This is an OCR/native localization smoke over broad BIV private-object images, not evidence that
LiveShield supports automatic detection of those object categories. BIV is not mixed into the
synthetic Priority 2 table or its denominators.

## Reproduction and content locks

The manifest was validated before device execution:

```sh
python3 tools/testdata/validate_manifest.py \
  test-fixtures/manifests/pii-v1.jsonl \
  --media-root test-fixtures/media \
  --truth-root test-fixtures/annotations \
  --expected-count 26 --profile priority2-v1
```

The findings were evaluated exactly once:

```sh
python3 tools/testdata/evaluate_priority2.py \
  --suite priority2-v1-device \
  --manifest test-fixtures/manifests/pii-v1.jsonl \
  --findings build/t100/priority2-findings.jsonl \
  --media-root test-fixtures/media \
  --truth-root test-fixtures/annotations \
  --json build/t100/priority2-evaluation.json \
  --markdown build/t100/priority2-evaluation.md
```

| Artifact | SHA-256 |
|---|---|
| Frozen `pii-v1` manifest | `e405fcbb15925b94745a31e23d359024bd5403f9befa6833cd19f5cf6e7ced03` |
| Payload-free findings JSONL | `b25494bef48f4d4c2e7e34c4d0b8aceb73f0d20201e32e32cdf30c3516b63baa` |
| Evaluator JSON | `df046aa2804329bfaaacd8164fb240a42e09b256859459cb46dbda2c749d5b5d` |
| Evaluator Markdown | `018236ff38a2bf24d541f0b18b7792aca1e72cf49c1862c89ec73d757237353d` |
| Instrumentation APK | `3559b1292e3c09a7482ef56ead4ceec1ba424a0a1d046750cc96c0d767225c89` |

## Evidence boundary and next work

This is detector/configuration findings evidence on synthetic 320-by-180 silent video. It is not a
CameraX live session, renderer test, encoded-output inspection, physical-device performance run,
or TikTok publication. Configured zones were test setup derived from the intended complete truth
region; they were not automatically detected. Rotated appearances were represented in the pixels
while the runner used identity camera transforms. T101 must independently inspect encoded output;
this report makes no encoded sanitization claim.

SC-002 remains **UNMET** because automatic-pattern and configured-watchlist recall is 0%, well below
the required 95%, even though configured-zone recall and localization coverage are 100% on this
corpus. The next engineering step is diagnosis on development fixtures only, followed by a frozen
implementation change and a separately authorized development/holdout rerun; these reported
holdout results must not be tuned against.
