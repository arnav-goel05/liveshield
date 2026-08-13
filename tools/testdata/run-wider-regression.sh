#!/usr/bin/env bash
set -euo pipefail

workspace_root="$(cd "$(dirname "$0")/../.." && pwd)"
adb_binary="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}/platform-tools/adb"
device_serial="${ANDROID_SERIAL:-emulator-5554}"
device_root="/data/local/tmp/liveshield-public-v1"
test_apk="$workspace_root/vision/build/outputs/apk/androidTest/debug/vision-debug-androidTest.apk"

if [[ ! -x "$adb_binary" ]]; then
  echo "adb is unavailable at $adb_binary" >&2
  exit 1
fi
if [[ ! -f "$test_apk" ]]; then
  echo "Build :vision:assembleDebugAndroidTest before running this script." >&2
  exit 1
fi

"$adb_binary" -s "$device_serial" shell mkdir -p \
  "$device_root/annotations" "$device_root/media"
"$adb_binary" -s "$device_serial" push \
  "$workspace_root/test-fixtures/manifests/public-v1.jsonl" \
  "$device_root/public-v1.jsonl"
"$adb_binary" -s "$device_serial" push \
  "$workspace_root/test-fixtures/annotations/public-v1/wider/." \
  "$device_root/annotations/"
"$adb_binary" -s "$device_serial" push \
  "$workspace_root/evaluation-data/public/media/wider/." \
  "$device_root/media/"
"$adb_binary" -s "$device_serial" install -r "$test_apk"
"$adb_binary" -s "$device_serial" logcat -c
"$adb_binary" -s "$device_serial" shell am instrument -w -r \
  -e class com.liveshield.vision.face.WiderFaceRegressionTest \
  -e widerManifest "$device_root/public-v1.jsonl" \
  -e widerAnnotations "$device_root/annotations" \
  -e widerMedia "$device_root/media" \
  com.liveshield.vision.test/androidx.test.runner.AndroidJUnitRunner
"$adb_binary" -s "$device_serial" logcat -d -s WiderFaceMetrics:I '*:S'
"$adb_binary" -s "$device_serial" shell am force-stop com.liveshield.vision.test
