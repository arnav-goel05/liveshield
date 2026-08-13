#!/usr/bin/env bash
set -euo pipefail

ROOT="${1:-$(cd "$(dirname "$0")/../.." && pwd)}"

if [[ ! -d "$ROOT" ]]; then
  echo "privacy-boundary: repository root does not exist: $ROOT" >&2
  exit 2
fi

python3 - "$ROOT" <<'PY'
import pathlib
import re
import sys

root = pathlib.Path(sys.argv[1]).resolve()
violations = []
first_party_modules = {
    "app", "benchmark", "privacy-domain", "test-fixtures", "transport", "video-pipeline", "vision"
}


def relative(path):
    try:
        return str(path.relative_to(root))
    except ValueError:
        return str(path)


def report(path, line, rule, detail):
    violations.append((relative(path), line, rule, detail))


def source_line(text, offset):
    return text.count("\n", 0, offset) + 1


def first_party_production(path):
    value = path.as_posix()
    try:
        module = path.relative_to(root).parts[0]
    except (ValueError, IndexError):
        return False
    return (module in first_party_modules
            and ("/src/main/" in value or "/src/debug/" in value or "/src/release/" in value
            or "/build/generated/" in value)
            )


def java_sources():
    for path in root.rglob("*"):
        if not path.is_file() or path.suffix not in (".java", ".kt"):
            continue
        value = path.as_posix()
        if any(part in value for part in ("/.git/", "/.gradle/", "/test-fixtures/")):
            continue
        if first_party_production(path):
            yield path


def strip_comments(text):
    text = re.sub(r"/\*.*?\*/",
                  lambda match: re.sub(r"[^\n]", " ", match.group(0)), text, flags=re.S)
    return re.sub(r"//[^\n]*", " ", text)


# Effective permissions are checked in both authored and already-generated manifests. This catches
# a permission contributed transitively to an application manifest without scanning dependency
# implementation classes.
for manifest in root.rglob("AndroidManifest.xml"):
    value = manifest.as_posix()
    if any(part in value for part in ("/.git/", "/.gradle/", "/test-fixtures/")):
        continue
    text = manifest.read_text(encoding="utf-8", errors="replace")
    for match in re.finditer(r"(?:android\.permission\.)?RECORD_AUDIO", text):
        report(manifest, source_line(text, match.start()), "NO_RECORD_AUDIO",
               "effective manifest requests microphone capture")


audio_type_patterns = (
    (r"\bandroid\.media\.AudioRecord\b|\bimport\s+android\.media\.AudioRecord\b",
     "NO_MIC_CAPTURE", "first-party AudioRecord path"),
    (r"\bandroid\.media\.MediaRecorder\b|\bimport\s+android\.media\.MediaRecorder\b",
     "NO_MIC_CAPTURE", "first-party MediaRecorder path"),
    (r"\bMediaRecorder\s*\.\s*AudioSource\b|\bAudioRecord\s*\.\s*Builder\b",
     "NO_MIC_CAPTURE", "first-party microphone source"),
    (r"\bMediaFormat\s*\.\s*createAudioFormat\s*\(",
     "NO_AUDIO_ENCODER", "first-party audio format/encoder path"),
    (r"\bMediaCodec\s*\.\s*createEncoderByType\s*\(\s*\"audio/",
     "NO_AUDIO_ENCODER", "first-party audio codec path"),
    (r"\.(?:sendAudio|setOnlyAudio|prepareAudio|startAudio|inputAudio|recordAudio)\s*\(",
     "NO_AUDIO_PUBLISH", "first-party audio publisher/capture call"),
)

sources = list(java_sources())
for path in sources:
    text = strip_comments(path.read_text(encoding="utf-8", errors="replace"))
    for pattern, rule, detail in audio_type_patterns:
        for match in re.finditer(pattern, text):
            report(path, source_line(text, match.start()), rule, detail)


# Only the explicit secret owner may accept erasable secret buffers. No publisher, controller,
# health record, or telemetry API may expose secret-bearing data. StreamDestination is deliberately
# named rather than broadly excluding a directory, so a second secret owner cannot appear unnoticed.
secret_owner = "transport/src/main/java/com/liveshield/transport/destination/StreamDestination.java"
raw_types = re.compile(
    r"\b(?:android\.media\.)?Image\b|\bImageProxy\b|\bBitmap\b|\bSurface\b|"
    r"\bSurfaceTexture\b|\bHardwareBuffer\b|\b(?:Byte|Int|Short|Float)Buffer\b")
secret_data = re.compile(
    r"(?i)(?:String|CharSequence|(?:char|byte)\s*\[\])\s*"
    r"(?:secret|password|credential|streamKey|authToken)\b|"
    r"(?:(?:secret|password|credential|streamKey|authToken)\w*)\s*[,)]")
raw_array = re.compile(
    r"(?i)(?:byte|int|short|float)\s*\[\]\s*(?:raw\w*|pixels?\w*|image\w*|frame\w*)\b")


def public_declarations(text):
    cleaned = strip_comments(text)
    # Java/Kotlin public declarations end at a body opener or semicolon. Limiting the span prevents
    # private method bodies from being mistaken for part of the signature.
    pattern = re.compile(r"\bpublic\b[^;{}]*(?:[;{])", re.S)
    for match in pattern.finditer(cleaned):
        yield match.start(), re.sub(r"\s+", " ", match.group(0)).strip()
    # Interface methods are public even when Java omits the modifier. Include declaration-only
    # members while excluding package/import statements and field initializers.
    interface_start = re.compile(r"\bpublic\s+(?:sealed\s+)?interface\b[^{}]*\{")
    members = re.compile(
        r"(?:^|[;{}])\s*(?!private\b|protected\b)(?:default\s+|static\s+)?"
        r"[\w<>,.?\[\]@ ]+\s+\w+\s*\([^;{}]*\)\s*;", re.M)
    for interface in interface_start.finditer(cleaned):
        depth = 1
        cursor = interface.end()
        while cursor < len(cleaned) and depth:
            if cleaned[cursor] == "{":
                depth += 1
            elif cleaned[cursor] == "}":
                depth -= 1
            cursor += 1
        body_start = interface.end()
        body = cleaned[body_start:cursor - 1]
        for match in members.finditer(body):
            yield body_start + match.start(), re.sub(r"\s+", " ", match.group(0)).strip()


for path in sources:
    rel = relative(path)
    is_transport = "/transport/src/main/" in "/" + rel
    path_lower = rel.lower()
    text = path.read_text(encoding="utf-8", errors="replace")
    is_telemetry = ("/telemetry/" in "/" + path_lower
                    or "telemetry" in path.name.lower()
                    or re.search(r"\bpublic\s+interface\s+Telemetry\b", strip_comments(text)))
    if not (is_transport or is_telemetry):
        continue
    for offset, declaration in public_declarations(text):
        line = source_line(strip_comments(text), offset)
        if raw_types.search(declaration) or raw_array.search(declaration):
            report(path, line, "NO_RAW_PUBLIC_API",
                   "transport/telemetry public signature exposes raw image, pixel, or surface data")
        if secret_data.search(declaration):
            allowed_secret_input = (rel == secret_owner and (
                re.search(r"\bsessionScoped(?:Utf8)?\s*\(", declaration)
                or re.search(r"\bT\s+apply\s*\(\s*char\s*\[\]\s*secret\s*\)", declaration)))
            if not allowed_secret_input:
                report(path, line, "NO_SECRET_PUBLIC_API",
                       "transport/telemetry public signature exposes destination secret data")


if violations:
    for path, line, rule, detail in sorted(set(violations)):
        print(f"{path}:{line}: {rule}: {detail}", file=sys.stderr)
    print(f"privacy-boundary: FAILED ({len(set(violations))} violation(s))", file=sys.stderr)
    sys.exit(1)

print("privacy-boundary: PASS (first-party APIs, audio paths, and effective manifests)")
PY
