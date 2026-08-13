#!/bin/bash
set -euo pipefail
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
ADB=${ADB:-"${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}/platform-tools/adb"}
SERIAL=${ANDROID_SERIAL:?ANDROID_SERIAL required}
[[ "${LIVESHIELD_PRIORITY2_MEDIAMTX:-}" == true ]] || { echo opt-in-required >&2; exit 2; }
OUT=$(mktemp -d "$ROOT/transport/build/reports/priority2-mediamtx.XXXXXX")
relay=""; capture=""; readiness=""
cleanup(){ set +e; [[ -z "$capture" ]]||kill "$capture" 2>/dev/null; [[ -z "$readiness" ]]||kill "$readiness" 2>/dev/null; [[ -z "$relay" ]]||kill "$relay" 2>/dev/null; "$ADB" -s "$SERIAL" uninstall com.liveshield.video.test >/dev/null 2>&1; "$ADB" -s "$SERIAL" uninstall com.liveshield.transport.test >/dev/null 2>&1; "$ADB" -s "$SERIAL" shell rm -f /data/local/tmp/t101-{findings.jsonl,sanitized.mp4}; }
trap cleanup EXIT
export JAVA_HOME=${JAVA_HOME:-"/Applications/Android Studio.app/Contents/jbr/Contents/Home"}
"$ROOT/gradlew" -p "$ROOT" :video-pipeline:assembleDebugAndroidTest :transport:assembleDebugAndroidTest
"$ADB" -s "$SERIAL" push "$ROOT/build/t100/priority2-findings.jsonl" /data/local/tmp/t101-findings.jsonl
"$ADB" -s "$SERIAL" install -r "$ROOT/video-pipeline/build/outputs/apk/androidTest/debug/video-pipeline-debug-androidTest.apk"
"$ADB" -s "$SERIAL" shell am instrument -w -r -e class 'com.liveshield.video.EncodedPrivacyVerifierTest#exportsDevelopmentPriorityTwoSanitizedH264ForRelay' -e piiFindings /data/local/tmp/t101-findings.jsonl -e piiRelayOutput t101-sanitized.mp4 com.liveshield.video.test/androidx.test.runner.AndroidJUnitRunner >"$OUT/export.txt"
grep -q 'OK (1 test)' "$OUT/export.txt"
"$ADB" -s "$SERIAL" exec-out run-as com.liveshield.video.test cat cache/t101-sanitized.mp4 >"$OUT/sanitized.mp4"
"$ADB" -s "$SERIAL" push "$OUT/sanitized.mp4" /data/local/tmp/t101-sanitized.mp4
bin=$("$ROOT/tools/mediamtx/fetch-pinned-mediamtx.sh"); "$bin" "$ROOT/dev/mediamtx/mediamtx.yml" >"$OUT/mediamtx.log" 2>&1 & relay=$!
python3 "$ROOT/tools/mediamtx/readiness_server.py" --marker "$OUT/reader.ready" >"$OUT/readiness.log" 2>&1 & readiness=$!
for i in {1..100}; do nc -z 127.0.0.1 1935 && break; sleep .1; done
"$ADB" -s "$SERIAL" install -r "$ROOT/transport/build/outputs/apk/androidTest/debug/transport-debug-androidTest.apk"
"$ADB" -s "$SERIAL" shell am instrument -w -r -e class 'com.liveshield.transport.RtmpApi36IntegrationTest#republishesExactPriorityTwoSanitizedH264ToHostRelay' -e liveshield.priority2.mediamtx true -e piiSanitizedMp4 /data/local/tmp/t101-sanitized.mp4 com.liveshield.transport.test/androidx.test.runner.AndroidJUnitRunner >"$OUT/publish.txt" & pub=$!
for i in {1..300}; do grep -q "is publishing to path 'liveshield'" "$OUT/mediamtx.log" && break; sleep .1; done
grep -q "is publishing to path 'liveshield'" "$OUT/mediamtx.log" || { echo relay-publication-timeout >&2; exit 1; }
ffmpeg -nostdin -v error -rw_timeout 120000000 -i rtmp://127.0.0.1:1935/liveshield -map 0:v:0 -c copy -an -progress "$OUT/capture.progress" -y "$OUT/capture.mkv" & capture=$!
for i in {1..120}; do
  grep -q "is reading from path 'liveshield'" "$OUT/mediamtx.log" && grep -Eq '^frame=[1-9][0-9]*$' "$OUT/capture.progress" 2>/dev/null && { touch "$OUT/reader.ready"; break; }
  kill -0 "$pub" 2>/dev/null || { echo publisher-exited-before-reader >&2; exit 1; }
  sleep .1
done
test -f "$OUT/reader.ready" || { echo reader-readiness-timeout >&2; exit 1; }
wait "$pub"; grep -q 'OK (1 test)' "$OUT/publish.txt"; wait "$capture"; capture=""
ffprobe -v error -show_entries stream=index,codec_name,codec_type,width,height -of json "$OUT/capture.mkv" >"$OUT/probe.json"
decoded_frames=$(ffprobe -v error -select_streams v:0 -count_frames -show_entries stream=nb_read_frames -of default=nw=1:nk=1 "$OUT/capture.mkv")
test "$decoded_frames" -gt 104 && test "$decoded_frames" -le 264 || { echo "expected 1..160 priming plus 104 evaluation frames, got $decoded_frames" >&2; exit 1; }
python3 "$ROOT/tools/testdata/evaluate_encoded_priority2.py" --manifest "$ROOT/test-fixtures/manifests/pii-v1.jsonl" --truth-root "$ROOT/test-fixtures/annotations" --media-root "$ROOT/test-fixtures/media" --findings "$ROOT/build/t100/priority2-findings.jsonl" --capture "$OUT/capture.mkv" --sanitized-reference "$OUT/sanitized.mp4" --json "$OUT/metrics.json"
echo "evidence=$OUT"
