#!/usr/bin/env bash
set -euo pipefail

ANDROID_STUDIO_JBR=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home

if [[ -n ${JAVA_HOME:-} ]]; then
  SELECTED_JAVA_HOME=$JAVA_HOME
  ORIGIN=explicit
else
  SELECTED_JAVA_HOME=$ANDROID_STUDIO_JBR
  ORIGIN=Android-Studio
fi

if [[ ! -x "$SELECTED_JAVA_HOME/bin/java" ]]; then
  printf 'T119 Java preflight failed: %s JAVA_HOME has no executable bin/java: %s\n' \
    "$ORIGIN" "$SELECTED_JAVA_HOME" >&2
  exit 2
fi

printf '%s\n' "$SELECTED_JAVA_HOME"
