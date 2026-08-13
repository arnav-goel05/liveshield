#!/usr/bin/env bash
set -euo pipefail

SCRIPT=$(cd "$(dirname "$0")/.." && pwd)/check-boundaries.sh
TEMP_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/liveshield-boundary-test.XXXXXX")
trap 'rm -rf "$TEMP_ROOT"' EXIT

new_fixture() {
  local name=$1
  local root="$TEMP_ROOT/$name"
  mkdir -p "$root/transport/src/main/java/example" \
    "$root/privacy-domain/src/main/java/example/telemetry" \
    "$root/app/src/main" \
    "$root/vendor/src/main/java/thirdparty"
  printf '%s\n' '<manifest package="example" />' > "$root/app/src/main/AndroidManifest.xml"
  printf '%s\n' 'package example; public interface Publisher { byte[] publish(byte[] sanitizedPayload); }' \
    > "$root/transport/src/main/java/example/Publisher.java"
  # Third-party internals are deliberately outside first-party module source roots.
  printf '%s\n' 'package thirdparty; class AudioInternals { void work(Client client) { client.sendAudio(new byte[0]); } interface Client { void sendAudio(byte[] data); } }' \
    > "$root/vendor/src/main/java/thirdparty/AudioInternals.java"
  printf '%s\n' "$root"
}

expect_pass() {
  local root=$1
  "$SCRIPT" "$root" >/dev/null
}

expect_failure() {
  local root=$1
  local expected=$2
  local output
  if output=$("$SCRIPT" "$root" 2>&1); then
    echo "expected $expected violation, but check passed" >&2
    exit 1
  fi
  if [[ "$output" != *"$expected"* ]]; then
    echo "expected $expected, got: $output" >&2
    exit 1
  fi
}

clean=$(new_fixture clean)
expect_pass "$clean"

permission=$(new_fixture permission)
printf '%s\n' '<manifest xmlns:android="http://schemas.android.com/apk/res/android"><uses-permission android:name="android.permission.RECORD_AUDIO" /></manifest>' \
  > "$permission/app/src/main/AndroidManifest.xml"
expect_failure "$permission" NO_RECORD_AUDIO

generated_permission=$(new_fixture generated-permission)
mkdir -p "$generated_permission/app/build/intermediates/merged_manifest/release"
printf '%s\n' '<manifest xmlns:android="http://schemas.android.com/apk/res/android"><uses-permission android:name="android.permission.RECORD_AUDIO" /></manifest>' \
  > "$generated_permission/app/build/intermediates/merged_manifest/release/AndroidManifest.xml"
expect_failure "$generated_permission" NO_RECORD_AUDIO

raw_api=$(new_fixture raw-api)
printf '%s\n' 'package example; public interface Publisher { void publish(android.view.Surface rawSurface); }' \
  > "$raw_api/transport/src/main/java/example/Publisher.java"
expect_failure "$raw_api" NO_RAW_PUBLIC_API

secret_api=$(new_fixture secret-api)
printf '%s\n' 'package example.telemetry; public interface Telemetry { String streamKey(); }' \
  > "$secret_api/privacy-domain/src/main/java/example/telemetry/Telemetry.java"
expect_failure "$secret_api" NO_SECRET_PUBLIC_API

secret_owner_leak=$(new_fixture secret-owner-leak)
mkdir -p "$secret_owner_leak/transport/src/main/java/com/liveshield/transport/destination"
printf '%s\n' 'package com.liveshield.transport.destination; public final class StreamDestination { public char[] secret() { return null; } }' \
  > "$secret_owner_leak/transport/src/main/java/com/liveshield/transport/destination/StreamDestination.java"
expect_failure "$secret_owner_leak" NO_SECRET_PUBLIC_API

microphone=$(new_fixture microphone)
printf '%s\n' 'package example; import android.media.AudioRecord; public final class Capture { private AudioRecord recorder; }' \
  > "$microphone/transport/src/main/java/example/Capture.java"
expect_failure "$microphone" NO_MIC_CAPTURE

audio_publish=$(new_fixture audio-publish)
printf '%s\n' 'package example; final class PublisherImpl { void go(Client client) { client.sendAudio(new byte[0]); } interface Client { void sendAudio(byte[] value); } }' \
  > "$audio_publish/transport/src/main/java/example/PublisherImpl.java"
expect_failure "$audio_publish" NO_AUDIO_PUBLISH

audio_encoder=$(new_fixture audio-encoder)
printf '%s\n' 'package example; final class Encoder { Object create() { return android.media.MediaFormat.createAudioFormat("audio/aac", 48000, 1); } }' \
  > "$audio_encoder/transport/src/main/java/example/Encoder.java"
expect_failure "$audio_encoder" NO_AUDIO_ENCODER

echo "privacy-boundary fixtures: PASS"
