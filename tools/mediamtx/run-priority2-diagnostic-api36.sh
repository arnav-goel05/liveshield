#!/bin/bash
set -euo pipefail
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
ADB=${ADB:-"${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}/platform-tools/adb"}
SERIAL=${ANDROID_SERIAL:?ANDROID_SERIAL required}
SOURCE_MP4=${T101_DIAGNOSTIC_MP4:?T101_DIAGNOSTIC_MP4 required}
[[ -f "$SOURCE_MP4" ]] || { echo diagnostic-mp4-missing >&2; exit 2; }
OUT=$(mktemp -d "$ROOT/transport/build/reports/priority2-diagnostic.XXXXXX")
relay=""
cleanup() {
  set +e
  [[ -z "$relay" ]] || kill "$relay" 2>/dev/null
  "$ADB" -s "$SERIAL" shell am force-stop com.liveshield.transport.test >/dev/null 2>&1
  "$ADB" -s "$SERIAL" uninstall com.liveshield.transport.test >/dev/null 2>&1
  "$ADB" -s "$SERIAL" shell rm -f /data/local/tmp/t101-diagnostic.mp4
}
trap cleanup EXIT
export JAVA_HOME=${JAVA_HOME:-"/Applications/Android Studio.app/Contents/jbr/Contents/Home"}
"$ROOT/gradlew" -p "$ROOT" :transport:assembleDebugAndroidTest >"$OUT/build.txt"
shasum -a 256 "$SOURCE_MP4" >"$OUT/source.sha256"
"$ADB" -s "$SERIAL" push "$SOURCE_MP4" /data/local/tmp/t101-diagnostic.mp4 >"$OUT/push.txt"
bin=$("$ROOT/tools/mediamtx/fetch-pinned-mediamtx.sh")
"$bin" "$ROOT/dev/mediamtx/mediamtx.yml" >"$OUT/mediamtx.log" 2>&1 & relay=$!
for ignored in {1..100}; do nc -z 127.0.0.1 1935 && break; sleep .1; done
"$ADB" -s "$SERIAL" install -r \
  "$ROOT/transport/build/outputs/apk/androidTest/debug/transport-debug-androidTest.apk" \
  >"$OUT/install.txt"
"$ADB" -s "$SERIAL" logcat -c

run_mode() {
  local mode=$1
  local before
  local test_exit
  before=$(wc -l <"$OUT/mediamtx.log")
  set +e
  "$ADB" -s "$SERIAL" shell am instrument -w -r \
    -e class 'com.liveshield.transport.RtmpApi36IntegrationTest#diagnosesConfiguredRelaySequence' \
    -e liveshield.priority2.diagnostic true -e diagnosticMode "$mode" \
    -e piiSanitizedMp4 /data/local/tmp/t101-diagnostic.mp4 \
    com.liveshield.transport.test/androidx.test.runner.AndroidJUnitRunner \
    >"$OUT/$mode.txt"
  test_exit=$?
  set -e
  [[ "$test_exit" -eq 0 ]] || return "$test_exit"
  grep -q 'OK (1 test)' "$OUT/$mode.txt"
  for ignored in {1..100}; do
    tail -n "+$((before + 1))" "$OUT/mediamtx.log" | grep -q 'closed:' && break
    sleep .1
  done
  tail -n "+$((before + 1))" "$OUT/mediamtx.log" | grep -q 'closed:' \
    || { echo "$mode-path-release-timeout" >&2; return 1; }
}

run_mode UNIQUE
run_mode PRIMING
"$ADB" -s "$SERIAL" logcat -d -v threadtime \
  -s LiveShield-T101-Diagnostic:I '*:S' >"$OUT/diagnostic.log"
grep -q 'mode=UNIQUE' "$OUT/diagnostic.log"
grep -q 'mode=PRIMING' "$OUT/diagnostic.log"
echo "evidence=$OUT"
