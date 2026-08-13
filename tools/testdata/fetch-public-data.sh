#!/usr/bin/env bash

set -euo pipefail

readonly WIDER_REVISION="833d07e7bf3860f294242312fe95eed0561eeb17"
readonly WIDER_BASE_URL="https://huggingface.co/datasets/CUHK-CSE/wider_face/resolve/${WIDER_REVISION}/data"
readonly BIV_SUPPORT_IMAGES_SHA256="3a37b93daad15905fb2ffc25d76cccaa9d88c57d5fc23e2f5ac66dac7d3b3e2f"
readonly BIV_SUPPORT_JSON_SHA256="3936b12169813da19659a8099484c13fd1692412659244444e0458425589476d"

usage() {
    cat <<'EOF'
Usage:
  tools/testdata/fetch-public-data.sh [--destination DIR] [--asset NAME ...]
  tools/testdata/fetch-public-data.sh --list
  tools/testdata/fetch-public-data.sh --verify-file FILE --bytes N --sha256 HEX

Assets:
  wider-val          WIDER FACE validation images (362752168 bytes)
  wider-annotations  WIDER FACE annotations (3591642 bytes)
  biv-support-images BIV-Priv-Seg support images (15732014 bytes)
  biv-support-json   BIV-Priv-Seg support annotations (11220 bytes)

Downloads are explicit: this script is never called by Gradle. Existing files are
accepted only after byte-length and SHA-256 verification. The BIV hashes are local
content locks established from the 2026-08-13 official-HTTPS retrieval because the
publisher does not publish cryptographic digests; see the public-data lock record.
EOF
}

sha256_file() {
    local path="$1"
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$path" | awk '{print $1}'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$path" | awk '{print $1}'
    else
        echo "error: sha256sum or shasum is required" >&2
        return 1
    fi
}

verify_file() {
    local path="$1"
    local expected_bytes="$2"
    local expected_sha256="$3"
    local actual_bytes
    local actual_sha256

    if [[ ! -f "$path" ]]; then
        echo "error: file not found: $path" >&2
        return 1
    fi
    if [[ ! "$expected_bytes" =~ ^[0-9]+$ ]]; then
        echo "error: expected byte length must be a non-negative integer" >&2
        return 1
    fi
    if [[ ! "$expected_sha256" =~ ^[0-9a-fA-F]{64}$ ]]; then
        echo "error: expected SHA-256 must contain exactly 64 hexadecimal characters" >&2
        return 1
    fi

    actual_bytes="$(wc -c < "$path" | tr -d '[:space:]')"
    if [[ "$actual_bytes" != "$expected_bytes" ]]; then
        echo "error: byte-length mismatch for $path: expected $expected_bytes, got $actual_bytes" >&2
        return 1
    fi

    actual_sha256="$(sha256_file "$path")"
    actual_sha256="$(printf '%s' "$actual_sha256" | tr '[:upper:]' '[:lower:]')"
    expected_sha256="$(printf '%s' "$expected_sha256" | tr '[:upper:]' '[:lower:]')"
    if [[ "$actual_sha256" != "$expected_sha256" ]]; then
        echo "error: SHA-256 mismatch for $path: expected $expected_sha256, got $actual_sha256" >&2
        return 1
    fi
}

asset_metadata() {
    case "$1" in
        wider-val)
            printf '%s\t%s\t%s\t%s\n' \
                "${WIDER_BASE_URL}/WIDER_val.zip" \
                "362752168" \
                "f9efbd09f28c5d2d884be8c0eaef3967158c866a593fc36ab0413e4b2a58a17a" \
                "WIDER_val.zip"
            ;;
        wider-annotations)
            printf '%s\t%s\t%s\t%s\n' \
                "${WIDER_BASE_URL}/wider_face_split.zip" \
                "3591642" \
                "c7561e4f5e7a118c249e0a5c5c902b0de90bbf120d7da9fa28d99041f68a8a5c" \
                "wider_face_split.zip"
            ;;
        biv-support-images)
            printf '%s\t%s\t%s\t%s\n' \
                "https://vizwiz.cs.colorado.edu/biv-priv/images/support_images.zip" \
                "15732014" \
                "${BIV_SUPPORT_IMAGES_SHA256}" \
                "support_images.zip"
            ;;
        biv-support-json)
            printf '%s\t%s\t%s\t%s\n' \
                "https://vizwiz.cs.colorado.edu/biv-priv/images/support_set.json" \
                "11220" \
                "${BIV_SUPPORT_JSON_SHA256}" \
                "support_set.json"
            ;;
        *)
            echo "error: unknown asset: $1" >&2
            return 1
            ;;
    esac
}

download_asset() {
    local asset="$1"
    local destination="$2"
    local metadata
    local url
    local expected_bytes
    local expected_sha256
    local filename
    local output
    local partial

    metadata="$(asset_metadata "$asset")"
    IFS=$'\t' read -r url expected_bytes expected_sha256 filename <<< "$metadata"
    output="${destination}/${filename}"
    partial="${output}.part"

    mkdir -p "$destination"
    if [[ -e "$output" ]]; then
        verify_file "$output" "$expected_bytes" "$expected_sha256"
        echo "verified existing $asset: $output"
        return
    fi

    rm -f "$partial"
    echo "downloading $asset from $url"
    if ! curl --fail --location --proto '=https' --tlsv1.2 \
        --retry 3 --retry-all-errors --output "$partial" "$url"; then
        rm -f "$partial"
        return 1
    fi
    if ! verify_file "$partial" "$expected_bytes" "$expected_sha256"; then
        rm -f "$partial"
        return 1
    fi
    mv "$partial" "$output"
    echo "downloaded and verified $asset: $output"
}

destination="evaluation-data/public/archives"
declare -a assets=()
verify_path=""
verify_bytes=""
verify_sha256=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --destination)
            [[ $# -ge 2 ]] || { usage >&2; exit 2; }
            destination="$2"
            shift 2
            ;;
        --asset)
            [[ $# -ge 2 ]] || { usage >&2; exit 2; }
            assets+=("$2")
            shift 2
            ;;
        --list)
            printf 'wider-val\nwider-annotations\nbiv-support-images\nbiv-support-json\n'
            exit 0
            ;;
        --verify-file)
            [[ $# -ge 2 ]] || { usage >&2; exit 2; }
            verify_path="$2"
            shift 2
            ;;
        --bytes)
            [[ $# -ge 2 ]] || { usage >&2; exit 2; }
            verify_bytes="$2"
            shift 2
            ;;
        --sha256)
            [[ $# -ge 2 ]] || { usage >&2; exit 2; }
            verify_sha256="$2"
            shift 2
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *)
            echo "error: unknown argument: $1" >&2
            usage >&2
            exit 2
            ;;
    esac
done

if [[ -n "$verify_path" ]]; then
    [[ -n "$verify_bytes" && -n "$verify_sha256" && ${#assets[@]} -eq 0 ]] || {
        echo "error: --verify-file requires --bytes and --sha256 and cannot be combined with --asset" >&2
        exit 2
    }
    verify_file "$verify_path" "$verify_bytes" "$verify_sha256"
    echo "verified: $verify_path"
    exit 0
fi

if [[ ${#assets[@]} -eq 0 ]]; then
    assets=(wider-val wider-annotations)
fi

for asset in "${assets[@]}"; do
    download_asset "$asset" "$destination"
done
