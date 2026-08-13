#!/bin/bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
RUNNER="${SCRIPT_DIR}/../run-api36-rtmp-integration.sh"
TMP_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/liveshield helper test.XXXXXX")
trap 'rm -rf "$TMP_ROOT"' EXIT HUP INT TERM

HELPER_DIR="${TMP_ROOT}/helper directory"
HELPER="${HELPER_DIR}/fetch helper.sh"
EXPECTED="${TMP_ROOT}/binary directory/mediamtx binary"
mkdir -p "$HELPER_DIR" "$(dirname "$EXPECTED")"
printf '#!/bin/sh\nprintf "%%s\\n" "%s"\n' "$EXPECTED" > "$HELPER"
chmod 755 "$HELPER"

FETCH_HELPER="$HELPER"
# Keep this assignment identical to the production orchestrator boundary.
binary=$("$FETCH_HELPER")

[[ "$binary" == "$EXPECTED" ]] || {
    echo "Quoted helper execution changed the returned binary path." >&2
    exit 1
}
grep -Fq 'binary=$("$FETCH_HELPER")' "$RUNNER" || {
    echo "Production orchestrator no longer uses the quoted helper boundary." >&2
    exit 1
}
