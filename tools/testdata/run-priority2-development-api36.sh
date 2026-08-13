#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
SERIAL=${ANDROID_SERIAL:-emulator-5554}
STAGE=/data/local/tmp/liveshield-priority2-development
OUT=$ROOT/build/t119
PLAN=$OUT/development-stage-plan.json
DEV_MANIFEST=$OUT/pii-development-v1.jsonl
TEST_APK=$ROOT/vision/build/outputs/apk/androidTest/debug/vision-debug-androidTest.apk
PACKAGE=com.liveshield.vision.test
RUNNER=androidx.test.runner.AndroidJUnitRunner
METHOD=com.liveshield.vision.pii.PriorityTwoFindingsDeviceTest#allDevelopmentAndHoldoutFixturesEmitCompletePayloadFreeFindings

mkdir -p "$OUT"
JAVA_HOME=$("$ROOT/tools/testdata/resolve_android_studio_java_home.sh")
export JAVA_HOME
"$ROOT/gradlew" :vision:assembleDebugAndroidTest
python3 "$ROOT/tools/testdata/verify_priority2_development_apk.py" --apk "$TEST_APK" \
  > "$OUT/development-apk-preflight.json"
python3 "$ROOT/tools/testdata/priority2_development_stage.py" \
  --manifest "$ROOT/test-fixtures/manifests/pii-v1.jsonl" \
  --media-root "$ROOT/test-fixtures/media" \
  --truth-root "$ROOT/test-fixtures/annotations" \
  --json "$PLAN" --development-manifest "$DEV_MANIFEST"

cleanup() {
  "$ADB" -s "$SERIAL" shell am force-stop "$PACKAGE" >/dev/null 2>&1 || true
  "$ADB" -s "$SERIAL" shell pm clear "$PACKAGE" >/dev/null 2>&1 || true
  "$ADB" -s "$SERIAL" shell rm -rf "$STAGE" >/dev/null 2>&1 || true
}
trap cleanup EXIT

test "$("$ADB" -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')" = 36
test "$("$ADB" -s "$SERIAL" shell getprop ro.product.cpu.abi | tr -d '\r')" = arm64-v8a
test -f "$TEST_APK"
cleanup
"$ADB" -s "$SERIAL" shell mkdir -p "$STAGE/media" "$STAGE/truth"
"$ADB" -s "$SERIAL" push "$DEV_MANIFEST" "$STAGE/pii-development-v1.jsonl" >/dev/null

python3 - "$PLAN" <<'PY' | while IFS=$'\t' read -r source source_name truth truth_name; do
import json,sys
for value in json.load(open(sys.argv[1], encoding="utf-8")):
    print(value["source"], value["deviceSourceName"],
          value["truth"], value["deviceTruthName"], sep="\t")
PY
  "$ADB" -s "$SERIAL" push "$source" "$STAGE/media/$source_name" >/dev/null
  "$ADB" -s "$SERIAL" push "$truth" "$STAGE/truth/$truth_name" >/dev/null
done

# Preflight the exact flat paths implied by piiMedia/piiTruth and safeRelative().
python3 - "$PLAN" <<'PY' | while IFS=$'\t' read -r source_name truth_name; do
import json,sys
for value in json.load(open(sys.argv[1], encoding="utf-8")):
    print(value["deviceSourceName"], value["deviceTruthName"], sep="\t")
PY
  "$ADB" -s "$SERIAL" shell test -f "$STAGE/media/$source_name"
  "$ADB" -s "$SERIAL" shell test -f "$STAGE/truth/$truth_name"
done

"$ADB" -s "$SERIAL" install -r -t "$TEST_APK" >/dev/null
"$ADB" -s "$SERIAL" logcat -c
INSTRUMENTATION=$OUT/development-instrumentation.txt
"$ADB" -s "$SERIAL" shell am instrument -w -r \
  -e class "$METHOD" \
  -e piiManifest "$STAGE/pii-development-v1.jsonl" \
  -e piiTruth "$STAGE/truth" \
  -e piiMedia "$STAGE/media" \
  -e piiFindings t119-development-findings.jsonl \
  -e piiSplit DEVELOPMENT \
  -e piiOcrDiagnostics true \
  "$PACKAGE/$RUNNER" >"$INSTRUMENTATION" 2>&1 || true
"$ADB" -s "$SERIAL" logcat -d > "$OUT/development-logcat.txt"

grep -q '^OK (1 test)' "$INSTRUMENTATION"
grep -q '^INSTRUMENTATION_CODE: -1' "$INSTRUMENTATION"
! grep -q '^FAILURES!!!' "$INSTRUMENTATION"
"$ADB" -s "$SERIAL" shell run-as "$PACKAGE" \
  cat files/t119-development-findings.jsonl > "$OUT/development-findings.jsonl"
test "$(wc -l < "$OUT/development-findings.jsonl" | tr -d ' ')" = 104
python3 - "$OUT/development-findings.jsonl" <<'PY'
import json,sys
rows=[json.loads(line) for line in open(sys.argv[1], encoding="utf-8")]
keys={(row["fixtureId"], row["frameIndex"]) for row in rows}
assert len(rows)==len(keys)==104
assert all("failure" not in row for row in rows)
print("validated payload-free DEVELOPMENT findings: rows=104 failures=0")
PY
