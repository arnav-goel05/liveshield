#!/usr/bin/env python3
"""Generate LiveShield's deterministic renderer and fault-injection fixture pack."""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path


FRAME_COUNT = 8
FRAME_RATE = 8
FRAME_DURATION_NS = 1_000_000_000 // FRAME_RATE
CORPUS_VERSION = "1.0.0"
GENERATOR_VERSION = "1.0.0"

RENDERER_SCENARIOS = (
    ("moving-protected-region",),
    ("overlapping-protected-regions",),
    ("region-crosses-frame-edges",),
    ("rotation-crop-transform",),
    ("front-camera-mirror", "decision-at-deadline"),
    ("late-decision-full-shield", "carried-expanded-gap",
     "host-uncertain-protected", "shield-recovery-old-queue"),
)

FAULT_SCENARIOS = (
    "missing-analysis-result",
    "late-analysis-result",
    "stale-out-of-order-timestamp",
    "detector-exception-cancellation",
    "raw-frame-queue-capacity",
    "renderer-failure-invalid-surface",
    "camera-rebind-lifecycle-interruption",
    "encoder-backpressure-reconfiguration",
    "network-disconnect-reconnect",
    "recovery-old-undecided-queue",
)


@dataclass(frozen=True)
class Fixture:
    fixture_id: str
    group: str
    split: str
    scenarios: tuple[str, ...]
    seed: int
    width: int
    height: int
    motion_id: str


def _fixtures() -> list[Fixture]:
    fixtures: list[Fixture] = []
    for split_index, split in enumerate(("DEVELOPMENT", "HOLDOUT")):
        prefix = "dev" if split == "DEVELOPMENT" else "holdout"
        for index, scenarios in enumerate(RENDERER_SCENARIOS, start=1):
            fixtures.append(Fixture(
                f"renderer-{prefix}-{index:02d}", "RENDERER", split, scenarios,
                11_000 + split_index * 10_000 + index * 137,
                160 + split_index * 16, 120 + split_index * 16,
                f"renderer-motion-{prefix}-{index:02d}",
            ))
        for index, scenario in enumerate(FAULT_SCENARIOS, start=1):
            fixtures.append(Fixture(
                f"fault-{prefix}-{index:02d}", "FAULT_INJECTION", split, (scenario,),
                31_000 + split_index * 10_000 + index * 149,
                160 + split_index * 16, 120 + split_index * 16,
                f"fault-motion-{prefix}-{index:02d}",
            ))
    return fixtures


def _region(fixture: Fixture, frame_index: int) -> tuple[float, float, float, float]:
    progress = frame_index / (FRAME_COUNT - 1)
    if "region-crosses-frame-edges" in fixture.scenarios:
        positions = ((0.0, 0.0), (0.75, 0.0), (0.75, 0.70), (0.0, 0.70))
        left, top = positions[(frame_index // 2) % len(positions)]
        return left, top, min(1.0, left + 0.25), min(1.0, top + 0.30)
    left = 0.08 + progress * 0.55
    top = 0.18 + ((fixture.seed + frame_index) % 4) * 0.08
    return left, top, left + 0.25, top + 0.30


def _paint_frame(fixture: Fixture, frame_index: int) -> bytes:
    width, height = fixture.width, fixture.height
    seed = fixture.seed
    data = bytearray(width * height * 3)
    for y in range(height):
        for x in range(width):
            checker = ((x // 12) + (y // 12) + frame_index) & 1
            base = (seed + frame_index * 43 + x * 3 + y * 5) & 0xFF
            offset = (y * width + x) * 3
            if checker:
                data[offset:offset + 3] = bytes((base, 255 - base, 32 + frame_index * 17))
            else:
                data[offset:offset + 3] = bytes((255 - base, 48 + frame_index * 19, base))

    # A binary, non-textual sentinel band identifies the exact seed and frame.
    identifier = seed ^ frame_index
    for bit in range(min(64, width)):
        value = 255 if (identifier >> bit) & 1 else 0
        for y in range(4):
            offset = (y * width + bit) * 3
            data[offset:offset + 3] = bytes((value, value, value))
    return b"P6\n%d %d\n255\n" % (width, height) + bytes(data)


def _truth(fixture: Fixture, frame_index: int) -> dict[str, object]:
    left, top, right, bottom = _region(fixture, frame_index)
    state = "REGIONAL_PROTECTION"
    action = "PROTECT_REGIONS"
    if fixture.group == "RENDERER":
        if "late-decision-full-shield" in fixture.scenarios and frame_index < 2:
            state, action = "FULL_SHIELD", "FULL_SHIELD"
        elif "host-uncertain-protected" in fixture.scenarios and frame_index in (4, 5):
            state, action = "FULL_SHIELD", "FULL_SHIELD"
    else:
        scenario = fixture.scenarios[0]
        if scenario in {
            "camera-rebind-lifecycle-interruption",
            "encoder-backpressure-reconfiguration",
        } and frame_index >= 4:
            state, action = "STOPPED", "STOP_OUTPUT"
        elif scenario in {
            "network-disconnect-reconnect",
            "recovery-old-undecided-queue",
        } and frame_index >= 6:
            state, action = "REGIONAL_PROTECTION", "PROTECT_REGIONS"
        else:
            state, action = "FULL_SHIELD", "FULL_SHIELD"

    rotation = 0
    crop = [0.0, 0.0, 1.0, 1.0]
    if "rotation-crop-transform" in fixture.scenarios:
        rotation = (0, 90, 180, 270)[frame_index % 4]
        crop = [0.05, 0.10, 0.95, 0.90]
    mirrored = "front-camera-mirror" in fixture.scenarios

    objects = [{
        "objectId": f"sentinel-region-{fixture.fixture_id}",
        "category": "FACE",
        "role": "UNKNOWN",
        "polygon": [[left, top], [right, top], [right, bottom], [left, bottom]],
        "visibility": 1.0,
        "protectable": True,
        "legible": False,
    }]
    if "overlapping-protected-regions" in fixture.scenarios:
        second_left = min(0.78, left + 0.12)
        second_top = min(0.68, top + 0.10)
        objects.append({
            "objectId": f"sentinel-overlap-{fixture.fixture_id}",
            "category": "DEVICE_SCREEN",
            "role": "SENSITIVE",
            "polygon": [[second_left, second_top], [second_left + 0.20, second_top],
                        [second_left + 0.20, second_top + 0.20],
                        [second_left, second_top + 0.20]],
            "visibility": 1.0,
            "protectable": True,
            "legible": False,
        })

    return {
        "schemaVersion": "1.0.0",
        "fixtureId": fixture.fixture_id,
        "frameIndex": frame_index,
        "sourceTimestampNs": frame_index * FRAME_DURATION_NS,
        "transform": {
            "rotationDegrees": rotation,
            "mirrored": mirrored,
            "crop": crop,
            "sensorToBuffer": [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0],
        },
        "objects": objects,
        "expectedState": state,
        "requiredAction": action,
        "generatorSeedOrPayloadId": f"seed-{fixture.seed}",
    }


def _write_video(fixture: Fixture, destination: Path, ffmpeg: str) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="liveshield-system-") as temporary:
        frame_dir = Path(temporary)
        for frame_index in range(FRAME_COUNT):
            (frame_dir / f"frame-{frame_index:03d}.ppm").write_bytes(
                _paint_frame(fixture, frame_index)
            )
        command = [
            ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
            "-framerate", str(FRAME_RATE), "-i", str(frame_dir / "frame-%03d.ppm"),
            "-map", "0:v:0", "-an", "-c:v", "libx264", "-preset", "veryslow",
            "-crf", "0", "-pix_fmt", "yuv420p", "-threads", "1",
            "-fflags", "+bitexact", "-flags:v", "+bitexact", "-map_metadata", "-1",
            "-metadata", "creation_time=1970-01-01T00:00:00Z", str(destination),
        ]
        subprocess.run(command, check=True)


def _verify_video(path: Path, ffprobe: str) -> None:
    result = subprocess.run([
        ffprobe, "-v", "error", "-show_entries", "stream=codec_type",
        "-of", "default=noprint_wrappers=1:nokey=1", str(path),
    ], check=True, capture_output=True, text=True)
    streams = [line.strip() for line in result.stdout.splitlines() if line.strip()]
    if streams != ["video"]:
        raise RuntimeError(f"{path} must contain exactly one video stream, found {streams}")


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def generate(repo: Path, ffmpeg: str, ffprobe: str) -> Path:
    media_root = repo / "test-fixtures" / "media"
    truth_root = repo / "test-fixtures" / "annotations" / "system-v1"
    manifest_path = repo / "test-fixtures" / "manifests" / "system-v1.jsonl"
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    records = []
    for fixture in _fixtures():
        subgroup = "renderer" if fixture.group == "RENDERER" else "fault"
        relative_media = Path("system-v1") / subgroup / f"{fixture.fixture_id}.mp4"
        media_path = media_root / relative_media
        _write_video(fixture, media_path, ffmpeg)
        _verify_video(media_path, ffprobe)

        relative_truth = Path("system-v1") / subgroup / f"{fixture.fixture_id}.jsonl"
        truth_path = repo / "test-fixtures" / "annotations" / relative_truth
        truth_path.parent.mkdir(parents=True, exist_ok=True)
        truth_lines = [json.dumps(_truth(fixture, index), sort_keys=True, separators=(",", ":"))
                       for index in range(FRAME_COUNT)]
        truth_path.write_text("\n".join(truth_lines) + "\n", encoding="utf-8")

        records.append({
            "schemaVersion": "1.0.0",
            "corpusVersion": CORPUS_VERSION,
            "fixtureId": fixture.fixture_id,
            "group": fixture.group,
            "sourceKind": "SYNTHETIC",
            "split": fixture.split,
            "scenarioIds": list(fixture.scenarios),
            "sourcePath": relative_media.as_posix(),
            "sourceDigest": _sha256(media_path),
            "provenanceRef": "generated:liveshield-system-fixtures-v1",
            "truthPath": relative_truth.as_posix(),
            "mediaStreams": ["VIDEO"],
            "leakageKeys": {
                "sourceGroupId": f"source-{fixture.fixture_id}",
                "actorIds": [],
                "payloadIds": [],
                "generatorSeeds": [f"seed-{fixture.seed}"],
                "roomMotionIds": [fixture.motion_id],
            },
            "deviceContext": {
                "deviceClass": "synthetic-renderer-source",
                "lens": "FRONT" if "front-camera-mirror" in fixture.scenarios else "REAR",
                "width": fixture.width,
                "height": fixture.height,
                "frameRate": FRAME_RATE,
            },
            "syntheticSource": {
                "generatorId": "liveshield-system-fixtures",
                "generatorVersion": GENERATOR_VERSION,
                "fictionalPayloadVerified": True,
            },
        })
    records.sort(key=lambda record: record["fixtureId"])
    manifest_path.write_text(
        "\n".join(json.dumps(record, sort_keys=True, separators=(",", ":"))
                  for record in records) + "\n",
        encoding="utf-8",
    )
    return manifest_path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--ffmpeg", default=shutil.which("ffmpeg"))
    parser.add_argument("--ffprobe", default=shutil.which("ffprobe"))
    args = parser.parse_args()
    if not args.ffmpeg or not args.ffprobe:
        parser.error("ffmpeg and ffprobe are required for explicit fixture generation")
    manifest = generate(args.repo.resolve(), args.ffmpeg, args.ffprobe)
    print(f"generated {len(_fixtures())} fixture(s): {manifest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
