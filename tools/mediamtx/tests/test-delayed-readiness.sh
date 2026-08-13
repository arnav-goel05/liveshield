#!/bin/bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck source=../orchestrator-functions.sh
source "${SCRIPT_DIR}/../orchestrator-functions.sh"
TMP_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/liveshield readiness test.XXXXXX")
publisher_pid=""
cleanup() {
    if [[ -n "$publisher_pid" ]] && kill -0 "$publisher_pid" 2>/dev/null; then
        kill -TERM "$publisher_pid" 2>/dev/null || true
        wait "$publisher_pid" 2>/dev/null || true
    fi
    rm -rf "$TMP_ROOT"
}
trap cleanup EXIT HUP INT TERM

relay_log="${TMP_ROOT}/relay.log"
: > "$relay_log"
(
    sleep 0.15
    printf "is publishing to path 'liveshield', 1 track (H264)\n" >> "$relay_log"
    sleep 1
) &
publisher_pid=$!
wait_for_relay_publication "$relay_log" "$publisher_pid" 20 0.05
kill -TERM "$publisher_pid" 2>/dev/null || true
wait "$publisher_pid" 2>/dev/null || true
publisher_pid=""

: > "$relay_log"
sleep 2 &
publisher_pid=$!
if wait_for_relay_publication "$relay_log" "$publisher_pid" 2 0.01; then
    echo "Readiness unexpectedly succeeded without a milestone." >&2
    exit 1
else
    status=$?
fi
[[ "$status" == "1" ]] || { echo "Expected bounded timeout status 1." >&2; exit 1; }
kill -TERM "$publisher_pid" 2>/dev/null || true
wait "$publisher_pid" 2>/dev/null || true
publisher_pid=""

: > "$relay_log"
(exit 0) &
publisher_pid=$!
wait "$publisher_pid"
if wait_for_relay_publication "$relay_log" "$publisher_pid" 2 0.01; then
    echo "Readiness unexpectedly succeeded after publisher exit." >&2
    exit 1
else
    status=$?
fi
[[ "$status" == "2" ]] || { echo "Expected publisher-ended status 2." >&2; exit 1; }
publisher_pid=""
