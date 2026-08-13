# ML Kit Runtime Metrics Audit

**Status:** Resolved by dependency removal on 2026-08-13. No telemetry exception was approved and
the constitution was not weakened.

## Historical finding

The earlier prototype used bundled ML Kit face, text, and barcode artifacts. Google states that ML
Kit input data such as images/video and detector outputs are processed on-device and are not sent
to Google servers. Bundling the face model also avoided a runtime model download. That did not
resolve the separate operational-metrics egress described below.

Primary sources:

- [ML Kit Terms and Privacy](https://developers.google.com/ml-kit/terms)
- [ML Kit model installation paths](https://developers.google.com/ml-kit/tips/installation-paths)

## What the SDK still collects

Google's Android data-disclosure documentation says all ML Kit features collect device and app
information, a per-installation identifier for bundled features, performance metrics, API
configuration including image format and resolution, input/output sizes, feature version, event
types, and error codes for diagnostics and usage analytics. Google says this data is encrypted in
transit and not transferred to third parties. These are operational metrics, but LiveShield has not
explicitly approved them to leave the device.

Primary source: [ML Kit Android data disclosure](https://developers.google.com/ml-kit/android-data-disclosure)

## Repository and API 36 evidence

- The resolved `face-detection-16.1.7.pom` directly brings in Google Data Transport API, CCT
  backend, and runtime artifacts. The resolved CCT AAR declares `android.permission.INTERNET` and
  registers `TransportBackendDiscovery`.
- The merged app manifest consequently includes `INTERNET`.
- During the 2026-08-13 API 36 setup launch, Logcat recorded `FIREBASE_ML_SDK` events in
  `TransportRuntime.SQLiteEventStore` and scheduled CCT uploads to
  `firebaselogging.googleapis.com`. This happened during detector initialization before a camera
  frame was analyzed.
- The captured runtime log is `/tmp/liveshield-t042-launch-logcat.txt`. It contains metadata and no
  captured pixels, recognized strings, or detector results.

## Can collection be disabled?

No supported ML Kit Android configuration or API for disabling these metrics is documented in the
official terms, data-disclosure guide, face-detection guide, or bundled-model guide. Firebase
Analytics collection switches are not applicable: these events use ML Kit's direct Google Data
Transport/CCT dependency, not an app-added Firebase Analytics dependency.

Removing `INTERNET` would prevent upload only in an offline build and would also prevent LiveShield
from publishing sanitized RTMP. It would not stop ML Kit from creating and scheduling local metric
records. Excluding or modifying ML Kit's transport dependencies is unsupported and may break SDK
initialization; it is not an acceptable production privacy guarantee.

## Remediation and current evidence

- All three declared ML Kit artifacts were removed. Text now uses the separately audited, bundled
  PaddleOCR/Paddle Lite offline path; barcode uses the separately audited ZXing core path.
- Face analysis now uses the pinned OpenCV 4.13.0 Android AAR and bundled YuNet model. Both build
  and runtime loaders verify the reviewed 232,589-byte model digest.
- `verifyOfflineVisionDependencies` fails the release graph if ML Kit, Google Data Transport,
  Firebase, MediaPipe, or ML Kit Play Services modules reappear.
- The 2026-08-13 release graph and production manifests contain none of those groups, registrars,
  providers, or CCT scheduler components. `INTERNET` remains only for explicit sanitized RTMP.

This resolves the identified ML Kit egress blocker by removal. The replacement OCR/barcode paths
have independent dependency, artifact, manifest, and no-egress gates.
