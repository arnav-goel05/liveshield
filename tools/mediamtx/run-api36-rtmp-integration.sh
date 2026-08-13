#!/bin/bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
WORKSPACE=$(CDPATH= cd -- "${SCRIPT_DIR}/../.." && pwd)
FETCH_HELPER="${SCRIPT_DIR}/fetch-pinned-mediamtx.sh"
# shellcheck source=orchestrator-functions.sh
source "${SCRIPT_DIR}/orchestrator-functions.sh"
CONFIG="${WORKSPACE}/dev/mediamtx/mediamtx.yml"
TEST_APK="${WORKSPACE}/transport/build/outputs/apk/androidTest/debug/transport-debug-androidTest.apk"
TEST_PACKAGE="com.liveshield.transport.test"
TEST_RUNNER="${TEST_PACKAGE}/androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS="com.liveshield.transport.RtmpApi36IntegrationTest"

if [[ "${LIVESHIELD_RTMP_API36_INTEGRATION:-}" != "true" ]]; then
    echo "Set LIVESHIELD_RTMP_API36_INTEGRATION=true for this explicit local integration run." >&2
    exit 2
fi

ADB=${ADB:-"${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}/platform-tools/adb"}
if [[ ! -x "$ADB" ]]; then
    ADB=$(command -v adb || true)
fi
[[ -n "$ADB" && -x "$ADB" ]] || { echo "adb is unavailable." >&2; exit 1; }
[[ -n "${ANDROID_SERIAL:-}" ]] || { echo "ANDROID_SERIAL must name the API 36 emulator." >&2; exit 1; }
[[ "$ANDROID_SERIAL" == emulator-* ]] || { echo "10.0.2.2 requires an Android emulator." >&2; exit 1; }

mkdir -p "${WORKSPACE}/transport/build/reports/rtmp-api36"
EVIDENCE_DIR=$(mktemp -d \
    "${WORKSPACE}/transport/build/reports/rtmp-api36/run.XXXXXX")
relay_pid=""
probe_pid=""
watchdog_pid=""
instrument_pid=""
logcat_pid=""
viewer_pid=""
stop_bounded() {
    local pid=$1
    kill -TERM "$pid" 2>/dev/null || return 0
    for ((attempt = 0; attempt < 50; attempt++)); do
        kill -0 "$pid" 2>/dev/null || { wait "$pid" 2>/dev/null || true; return 0; }
        sleep 0.1
    done
    kill -KILL "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
}
cleanup() {
    set +e
    if [[ -n "$probe_pid" ]] && kill -0 "$probe_pid" 2>/dev/null; then
        stop_bounded "$probe_pid"
    fi
    if [[ -n "$instrument_pid" ]] && kill -0 "$instrument_pid" 2>/dev/null; then
        stop_bounded "$instrument_pid"
    fi
    if [[ -n "$logcat_pid" ]] && kill -0 "$logcat_pid" 2>/dev/null; then
        stop_bounded "$logcat_pid"
    fi
    if [[ -n "$viewer_pid" ]] && kill -0 "$viewer_pid" 2>/dev/null; then
        stop_bounded "$viewer_pid"
    fi
    "$ADB" -s "$ANDROID_SERIAL" shell am force-stop "$TEST_PACKAGE" >/dev/null 2>&1
    "$ADB" -s "$ANDROID_SERIAL" uninstall "$TEST_PACKAGE" >/dev/null 2>&1
    if [[ -n "$relay_pid" ]] && kill -0 "$relay_pid" 2>/dev/null; then
        stop_bounded "$relay_pid"
    fi
    if [[ -n "$watchdog_pid" ]] && kill -0 "$watchdog_pid" 2>/dev/null; then
        stop_bounded "$watchdog_pid"
    fi
}
trap cleanup EXIT HUP INT TERM

export JAVA_HOME=${JAVA_HOME:-"/Applications/Android Studio.app/Contents/jbr/Contents/Home"}
"${WORKSPACE}/gradlew" -p "$WORKSPACE" :transport:assembleDebugAndroidTest
[[ -f "$TEST_APK" ]] || { echo "Transport androidTest APK was not built." >&2; exit 1; }
[[ "$($ADB -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')" == "36" ]] || {
    echo "The selected emulator is not API 36." >&2
    exit 1
}

binary=$("$FETCH_HELPER")
"$binary" "$CONFIG" >"${EVIDENCE_DIR}/mediamtx.log" 2>&1 &
relay_pid=$!
for ((attempt = 0; attempt < 200; attempt++)); do
    if nc -z 127.0.0.1 1935 >/dev/null 2>&1; then
        break
    fi
    kill -0 "$relay_pid" 2>/dev/null || { cat "${EVIDENCE_DIR}/mediamtx.log"; exit 1; }
    sleep 0.1
done
nc -z 127.0.0.1 1935 >/dev/null 2>&1 || { echo "MediaMTX startup timed out." >&2; exit 1; }

"$ADB" -s "$ANDROID_SERIAL" install -r -t "$TEST_APK" >"${EVIDENCE_DIR}/install.txt"
"$ADB" -s "$ANDROID_SERIAL" logcat -c
"$ADB" -s "$ANDROID_SERIAL" logcat -v epoch -s LiveShieldT084:I '*:S' \
    >"${EVIDENCE_DIR}/delay-logcat.txt" 2>&1 &
logcat_pid=$!
"$ADB" -s "$ANDROID_SERIAL" shell am instrument -w -r \
    -e liveshield.rtmp.integration true -e class "$TEST_CLASS" "$TEST_RUNNER" \
    >"${EVIDENCE_DIR}/instrumentation.txt" 2>&1 &
instrument_pid=$!
(
    sleep 30
    echo timeout >"${EVIDENCE_DIR}/instrumentation.timeout"
    kill -TERM "$instrument_pid" 2>/dev/null || true
) &
watchdog_pid=$!
readiness_status=0
wait_for_relay_publication "${EVIDENCE_DIR}/mediamtx.log" "$instrument_pid" 120 0.1 \
    || readiness_status=$?
if [[ "$readiness_status" == "1" ]]; then
    echo "MediaMTX path readiness exceeded 12 seconds." >&2
    exit 1
fi
if [[ "$readiness_status" == "2" ]]; then
    cat "${EVIDENCE_DIR}/instrumentation.txt"
    echo "Instrumentation ended before MediaMTX published-path readiness." >&2
    exit 1
fi
# Readiness is now observed without opening a consuming connection. ffprobe overlaps the
# remainder of the unchanged frozen multi-GOP publication window.
ffprobe -v error -rw_timeout 20000000 -read_intervals '%+3' \
    -show_entries 'stream=index,codec_type,codec_name:packet=stream_index' \
    -of flat 'rtmp://127.0.0.1:1935/liveshield' >"${EVIDENCE_DIR}/ffprobe.txt" 2>&1 &
probe_pid=$!
playwright_package=$(find "$HOME/.npm/_npx" -path '*/node_modules/playwright/package.json' \
    -type f 2>/dev/null | head -1)
[[ -n "$playwright_package" ]] || {
    echo "A local Playwright package is required for the controlled browser viewer." >&2
    exit 1
}
playwright_node_modules=$(dirname "$(dirname "$playwright_package")")
NODE_PATH="$playwright_node_modules" node "${SCRIPT_DIR}/verify-webrtc-viewer.cjs" \
    'http://127.0.0.1:8889/liveshield' "${EVIDENCE_DIR}/viewer.json" \
    >"${EVIDENCE_DIR}/viewer.txt" 2>&1 &
viewer_pid=$!
instrument_status=0
wait "$instrument_pid" || instrument_status=$?
instrument_pid=""
kill -TERM "$watchdog_pid" 2>/dev/null || true
wait "$watchdog_pid" 2>/dev/null || true
watchdog_pid=""
stop_bounded "$logcat_pid"
logcat_pid=""
[[ ! -f "${EVIDENCE_DIR}/instrumentation.timeout" ]] || {
    cat "${EVIDENCE_DIR}/instrumentation.txt"
    echo "Instrumentation exceeded 30 seconds." >&2
    exit 1
}
[[ "$instrument_status" == "0" ]] || {
    cat "${EVIDENCE_DIR}/instrumentation.txt"
    exit "$instrument_status"
}
grep -q 'OK (1 test)' "${EVIDENCE_DIR}/instrumentation.txt" || {
    cat "${EVIDENCE_DIR}/instrumentation.txt"
    echo "Exact instrumentation class did not pass one test." >&2
    exit 1
}

probe_status=0
wait "$probe_pid" || probe_status=$?
probe_pid=""
[[ "$probe_status" == "0" ]] || { cat "${EVIDENCE_DIR}/ffprobe.txt"; exit "$probe_status"; }
viewer_status=0
wait "$viewer_pid" || viewer_status=$?
viewer_pid=""
[[ "$viewer_status" == "0" ]] || { cat "${EVIDENCE_DIR}/viewer.txt"; exit "$viewer_status"; }
grep -q 'delay configured_ns=2000000000' "${EVIDENCE_DIR}/delay-logcat.txt" || {
    cat "${EVIDENCE_DIR}/delay-logcat.txt"
    echo "Configured-versus-observed delay evidence is missing." >&2
    exit 1
}
probe_output=$(<"${EVIDENCE_DIR}/ffprobe.txt")
stream_count=$(grep -Ec '^streams\.stream\.[0-9]+\.index=' \
    "${EVIDENCE_DIR}/ffprobe.txt" || true)
packet_count=$(grep -Ec '^packets\.packet\.[0-9]+\.stream_index=' \
    "${EVIDENCE_DIR}/ffprobe.txt" || true)
[[ "$stream_count" == "1" ]] || { printf '%s\n' "$probe_output"; exit 1; }
grep -q 'codec_name="h264"' "${EVIDENCE_DIR}/ffprobe.txt"
grep -q 'codec_type="video"' "${EVIDENCE_DIR}/ffprobe.txt"
! grep -qi 'audio' "${EVIDENCE_DIR}/ffprobe.txt"
[[ "$packet_count" -gt 0 ]] || { printf '%s\n' "$probe_output"; exit 1; }
! grep 'stream_index=' "${EVIDENCE_DIR}/ffprobe.txt" | grep -Ev '=0$'

printf 'instrumentation=1/1 passed, video_tracks=%s, audio_tracks=0, video_packets=%s\n' \
    "$stream_count" "$packet_count"
printf 'viewer=%s\n' "$(<"${EVIDENCE_DIR}/viewer.json")"
grep -o 'delay configured_ns=.*' "${EVIDENCE_DIR}/delay-logcat.txt"
printf 'evidence=%s\n' "$EVIDENCE_DIR"
