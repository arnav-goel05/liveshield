#!/bin/sh
set -eu

VERSION="v1.15.5"
# Official MIT-licensed release asset produced by MediaMTX's GitHub release workflow.
ARCHIVE_NAME="mediamtx_v1.15.5_darwin_arm64.tar.gz"
ARCHIVE_URL="https://github.com/bluenviron/mediamtx/releases/download/v1.15.5/${ARCHIVE_NAME}"
ARCHIVE_BYTES="23200334"
ARCHIVE_SHA256="116150e6900ed2ae845cf5113fab8007ad639a36d80c1a09c56e091ddcc5b907"
BINARY_BYTES="46805506"
BINARY_SHA256="77e8f24ce5fea5f0b8e69727cc5f5ded5cd09645096ec8c28532ae96c6be6e4a"

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
WORKSPACE=$(CDPATH= cd -- "${SCRIPT_DIR}/../.." && pwd)
CACHE_DIR="${WORKSPACE}/test-fixtures/cache/mediamtx/${VERSION}/darwin-arm64"
ARCHIVE_PATH="${CACHE_DIR}/${ARCHIVE_NAME}"
BINARY_PATH="${CACHE_DIR}/mediamtx"

if [ "$(uname -s)" != "Darwin" ] || [ "$(uname -m)" != "arm64" ]; then
    echo "This pinned helper supports macOS arm64 only." >&2
    exit 1
fi

verify_archive() {
    actual_bytes=$(wc -c < "$ARCHIVE_PATH" | tr -d ' ')
    [ "$actual_bytes" = "$ARCHIVE_BYTES" ] || {
        echo "MediaMTX archive size mismatch: expected ${ARCHIVE_BYTES}, got ${actual_bytes}" >&2
        return 1
    }
    actual_sha=$(shasum -a 256 "$ARCHIVE_PATH" | awk '{print $1}')
    [ "$actual_sha" = "$ARCHIVE_SHA256" ] || {
        echo "MediaMTX archive SHA-256 mismatch." >&2
        return 1
    }
}

validate_members() {
    members=$(tar -tzf "$ARCHIVE_PATH" | LC_ALL=C sort)
    expected=$(printf '%s\n' LICENSE mediamtx mediamtx.yml | LC_ALL=C sort)
    [ "$members" = "$expected" ] || {
        echo "MediaMTX archive contains unexpected members:" >&2
        printf '%s\n' "$members" >&2
        return 1
    }
    non_regular=$(tar -tvzf "$ARCHIVE_PATH" | awk 'substr($1, 1, 1) != "-" { count++ } END { print count + 0 }')
    [ "$non_regular" = "0" ] || {
        echo "MediaMTX archive contains a non-regular member." >&2
        return 1
    }
}

mkdir -p "$CACHE_DIR"
if [ ! -f "$ARCHIVE_PATH" ]; then
    download_dir=$(mktemp -d "${CACHE_DIR}/download.XXXXXX")
    trap 'rm -rf "$download_dir"' EXIT HUP INT TERM
    curl --fail --location --proto '=https' --tlsv1.2 \
        --output "${download_dir}/${ARCHIVE_NAME}" "$ARCHIVE_URL"
    mv "${download_dir}/${ARCHIVE_NAME}" "$ARCHIVE_PATH"
fi

verify_archive
validate_members

extract_dir=$(mktemp -d "${CACHE_DIR}/extract.XXXXXX")
trap 'rm -rf "$extract_dir" ${download_dir:+"$download_dir"}' EXIT HUP INT TERM
tar -xzf "$ARCHIVE_PATH" -C "$extract_dir" -- LICENSE mediamtx mediamtx.yml
chmod 755 "${extract_dir}/mediamtx"
binary_bytes=$(wc -c < "${extract_dir}/mediamtx" | tr -d ' ')
[ "$binary_bytes" = "$BINARY_BYTES" ] || {
    echo "MediaMTX executable size mismatch." >&2
    exit 1
}
binary_sha=$(shasum -a 256 "${extract_dir}/mediamtx" | awk '{print $1}')
[ "$binary_sha" = "$BINARY_SHA256" ] || {
    echo "MediaMTX executable SHA-256 mismatch." >&2
    exit 1
}
version_output=$("${extract_dir}/mediamtx" --version)
[ "$version_output" = "$VERSION" ] || {
    echo "MediaMTX executable version mismatch: expected ${VERSION}, got ${version_output}" >&2
    exit 1
}

mv -f "${extract_dir}/mediamtx" "$BINARY_PATH"
mv -f "${extract_dir}/LICENSE" "${CACHE_DIR}/LICENSE"
mv -f "${extract_dir}/mediamtx.yml" "${CACHE_DIR}/upstream-mediamtx.yml"
printf '%s  %s\n' "$ARCHIVE_SHA256" "$ARCHIVE_NAME" > "${CACHE_DIR}/checksums.sha256"
printf '%s\n' "$BINARY_PATH"
