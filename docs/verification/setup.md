# Phase 1 Setup Verification

**Verified**: 2026-08-13

## Configuration

- Java source and target level: 17
- Android compile SDK: 37
- Android target SDK: 36
- Android minimum SDK: 23
- Android Gradle Plugin: 9.3.0
- Gradle Wrapper: 9.5.0 with distribution SHA-256 verification
- Modules: `app`, `benchmark`, `privacy-domain`, `test-fixtures`, `transport`,
  `video-pipeline`, and `vision`

Compile SDK 37 is required by the pinned RootEncoder RTMP 2.8.0 AAR metadata. Target SDK remains
36, so this compile-time requirement does not change the V1 runtime target contract.

## Verification performed

```text
JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home \
  ./gradlew test lint checkstyleAll --offline

BUILD SUCCESSFUL
177 actionable tasks: 7 executed, 170 up-to-date
```

Additional checks:

- All seven projects load through the Gradle wrapper.
- Android lint runs with warnings treated as errors except deliberately pinned dependency-version
  checks; no dynamic versions are used.
- Checkstyle 10.21.4 is configured across Java source sets.
- Debug and release manifests merge successfully.
- Neither merged manifest requests `android.permission.RECORD_AUDIO`.
- Release excludes debug-only fault-injection and sanitized-recording flags.
- The pinned MediaMTX container accepted a generated silent H.264 stream as one video track, with
  recording disabled and no recording directory created.
- `git diff --check` passed.

## Temporary setup exception

The app module temporarily suppresses `UnusedResources` because T009 intentionally introduces the
status strings and privacy drawables before the first UI task consumes them. The suppression is
marked for removal with that UI work; it does not suppress security, manifest, API, or code-quality
lint checks.

## Disk guard

Approximately 6.4 GiB remained after dependency resolution and the full verification gate. Large
datasets and avoidable build artifacts must not be downloaded automatically.
