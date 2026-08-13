# Final verification gate

Date: 2026-08-13

## Outcome: historical incomplete gate; no longer an open task

The former T115 task was retired by user decision. This report preserves the exact root command and
its incomplete physical-benchmark result rather than relabeling it as successful:

```text
./gradlew test lint connectedCheck --continue
```

Host tests and lint ran, and every non-benchmark connected module reached a terminal green result
or an explicitly argument-gated skip. The root command remained red because the benchmark module
correctly rejected the API 36 emulator. That benchmark error is not suppressed: macrobenchmark
timing must be collected on a physical device.

This report supersedes the earlier statement that T115 was green. The earlier inventory predated
T118–T122 and had substituted selected connected module tasks for the benchmark-inclusive root
`connectedCheck`; it therefore cannot establish current final completion.

## Current connected-module results

The current terminal XML under `build/android-test-results/` records:

| Module / variant | Discovered | Passed | Expected skips | Failed | Boundary |
|---|---:|---:|---:|---:|---|
| `app` / debug | 19 | 19 | 0 | 0 | Current setup, private live UI, T118 controls, T120 lifecycle, T121 safety composition, and T122 publisher status |
| `vision` / debug | 32 | 29 | 3 | 0 | Three external-input suites skipped only because their explicit arguments were absent |
| `video-pipeline` / debug | 30 | 28 | 2 | 0 | Two external-findings encoded-output methods skipped only because their explicit arguments were absent |
| `transport` / debug | 3 | 0 | 3 | 0 | Three explicit MediaMTX/relay methods skipped without an external relay configuration |
| **Non-benchmark total** | **84** | **76** | **8** | **0** | Terminal green/expected-skip evidence |
| `benchmark` / benchmark | 2 | 0 | 0 | 2 | Both reached instrumentation and failed solely on unsuppressed `EMULATOR` |

The non-benchmark rows are emulator evidence, not physical-device, external-relay, participant,
consented-corpus, or TikTok evidence. An absent opt-in argument creates the declared skip; malformed
or incomplete supplied input remains a failure.

## Benchmark defect sequence

The root gate exposed real build defects rather than a product-performance result:

1. The initial benchmark reached instrumentation but reported the unsuppressed errors
   `EMULATOR DEBUGGABLE NOT-SELF-INSTRUMENTING` for both methods. The benchmark module had targeted
   the app's debug variant and instrumented the target process.
2. The build was corrected to use a signed, release-derived, explicitly non-debuggable app
   `benchmark` variant and a self-instrumenting `com.android.test` benchmark APK. A focused attempt
   then failed before instrumentation with
   `INSTALL_PARSE_FAILED_NO_CERTIFICATES`: the newly introduced custom test build type was unsigned.
3. The benchmark test build type was explicitly debug-signed. The final focused attempt installed
   both APKs, reached both benchmark methods, and reported only:

   ```text
   java.lang.AssertionError: ERRORS (not suppressed): EMULATOR
   ```

   The terminal XML records two tests, two failures, zero errors, and zero skips. `DEBUGGABLE` and
   `NOT-SELF-INSTRUMENTING` are absent, so the two build defects are resolved. The remaining failure
   is the intended physical-device gate.

No `androidx.benchmark.suppressErrors` argument was added. Suppressing `EMULATOR` would convert an
invalid timing environment into misleading performance evidence and is prohibited by the project
verification boundary.

## Benchmark source and package gate

The post-fix source/package command was:

```text
./gradlew :benchmark:verifyMacrobenchmarkConfiguration \
  :benchmark:compileBenchmarkJavaWithJavac \
  :benchmark:checkstyleAndroid --console=plain
```

It completed `BUILD SUCCESSFUL` in 14 seconds: 185 actionable tasks, 5 executed, 4 from cache, and
176 up-to-date. Packaging also ran the target app's `lintVitalBenchmark` tasks.

`verifyMacrobenchmarkConfiguration` mechanically requires:

- a release-derived app `benchmark` variant with `debuggable=false`;
- a self-instrumenting benchmark manifest whose package and instrumentation target are both
  `com.liveshield.benchmark`;
- exactly one ARM64 target APK and one benchmark test APK;
- a valid Android APK signature and reported certificate digest on both APKs; and
- zero benchmark `suppressErrors` source configuration.

| APK | Bytes | SHA-256 | Signer certificate SHA-256 |
|---|---:|---|---|
| `app-arm64-v8a-benchmark.apk` | 39,029,571 | `15a2b507874d79782c6ebc554c9956eefbd4a026b94ffac855b0ce6861cd1075` | `959aff4309a6663ec1d008a587a2e40b1e4a9d4ecbcffaab801eda2cbc643d53` |
| `benchmark-benchmark.apk` | 37,458,093 | `7f48c63cedc114fe6bc92c42dac53da0f9752313a00fc50621a4289978f1273d` | `959aff4309a6663ec1d008a587a2e40b1e4a9d4ecbcffaab801eda2cbc643d53` |

Android SDK `apksigner verify --print-certs` accepted both APKs. The shared certificate is the local
debug signing identity used only to install the controlled benchmark pair; the measured target
remains nondebuggable.

## Current convergence inventory

The root run now includes the production convergence work absent from the older T115 snapshot:

- T118: disclosure-gated, session-only watchlist add/remove and fixed-zone draw/edit controls with
  transform-safety and recreation clearing;
- T120: production `LiveActivity` lifecycle/state binding and idempotent creator Stop handoff without
  media-surface ownership;
- T121: renderer raw-queue/recovery, real thermal, and scene health composition into the scheduler,
  fail-private policy, and private UI; and
- T122: typed asynchronous publisher connection/authentication/network/congestion/queue health,
  terminal safe stop, and fresh-epoch recovery without endpoint or secret payloads.

Priority 2 remains bounded by the frozen T119 result: Noto v2 DEVELOPMENT observed QR 8/8 and zones
32/32, automatic text 0/32, and watchlists 0/32. Failed PP-OCRv5/CRNN candidates have no accuracy
result, HOLDOUT remains sealed, and SC-002/SC-009 remain unmet. See the
[acceptance matrix](acceptance-matrix.md) and
[T119 development report](t119-ocr-development.md).

## Optional future physical completion path

1. Connect an authorized compatible physical ARM64 Android device.
2. Run the benchmark-inclusive root command without any benchmark suppression.
3. Require both benchmark methods to pass and retain their physical-device reports together with
   terminal host and connected-module results.
4. Recompute the final report inventory and artifact hashes for that exact current workspace.

The current emulator run proves the benchmark packaging and instrumentation boundary reaches its
intended physical-device rejection; it does not provide valid startup or frame-timing measurements.
Physical completion is no longer a task gate.

Retired external work remains absent: physical endurance evidence, consented face data, usability,
TikTok eligibility/publication, and the complete 286-item corpus. None is inferred from this gate.
