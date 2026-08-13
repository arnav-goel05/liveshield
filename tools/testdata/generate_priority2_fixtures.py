#!/usr/bin/env python3
"""Generate 26 silent, fictional Priority 2 development/holdout video fixtures."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path
import PIL
from PIL import Image, ImageDraw, ImageFilter, ImageFont
from PIL import features as pillow_features


FRAME_COUNT = 8
FRAME_RATE = 8
FRAME_DURATION_NS = 125_000_000
WIDTH = 640
HEIGHT = 360
GENERATOR_VERSION = "2.0.0"
SYMBOL_GENERATOR_VERSION = "ZXing-core-3.5.4"
FONT_SIZE = 32
FONT_STROKE_WIDTH = 2
FONT_RELATIVE_PATH = Path(
    "test-fixtures/assets/fonts/noto-sans-2026-08-13/NotoSans[wdth,wght].ttf")
FONT_SHA256 = "bfb7bb691513f12e734dc346c03a03f784912432d7e3fa8e56efcf906fe86b3d"
FONT_BYTES = 2_049_096
FONT_LICENSE_RELATIVE_PATH = Path(
    "test-fixtures/assets/fonts/noto-sans-2026-08-13/OFL.txt")
FONT_LICENSE_SHA256 = "cee9892f9f0cc8fe882c9e9537ee6a89621d86ee7ceaf70b02e2b2b1c25c061a"
FONT_UPSTREAM_COMMIT = "038b637da7b3fd956a4ed93ffc607c3d5e4ce172"
PILLOW_VERSION = "11.3.0"
FREETYPE_VERSION = "2.13.3"

GLYPHS = {
    " ": (0, 0, 0, 0, 0, 0, 0), ".": (0, 0, 0, 0, 0, 6, 6),
    "-": (0, 0, 0, 31, 0, 0, 0), "/": (1, 2, 4, 8, 16, 0, 0),
    ":": (0, 6, 6, 0, 6, 6, 0), "@": (14, 17, 23, 21, 23, 16, 14),
    "0": (14, 17, 19, 21, 25, 17, 14), "1": (4, 12, 4, 4, 4, 4, 14),
    "2": (14, 17, 1, 2, 4, 8, 31), "3": (30, 1, 1, 14, 1, 1, 30),
    "4": (2, 6, 10, 18, 31, 2, 2), "5": (31, 16, 16, 30, 1, 1, 30),
    "6": (14, 16, 16, 30, 17, 17, 14), "7": (31, 1, 2, 4, 8, 8, 8),
    "8": (14, 17, 17, 14, 17, 17, 14), "9": (14, 17, 17, 15, 1, 1, 14),
    "A": (14, 17, 17, 31, 17, 17, 17), "B": (30, 17, 17, 30, 17, 17, 30),
    "C": (14, 17, 16, 16, 16, 17, 14), "D": (30, 17, 17, 17, 17, 17, 30),
    "E": (31, 16, 16, 30, 16, 16, 31), "F": (31, 16, 16, 30, 16, 16, 16),
    "G": (14, 17, 16, 23, 17, 17, 14), "H": (17, 17, 17, 31, 17, 17, 17),
    "I": (14, 4, 4, 4, 4, 4, 14), "J": (7, 2, 2, 2, 2, 18, 12),
    "K": (17, 18, 20, 24, 20, 18, 17), "L": (16, 16, 16, 16, 16, 16, 31),
    "M": (17, 27, 21, 21, 17, 17, 17), "N": (17, 25, 21, 19, 17, 17, 17),
    "O": (14, 17, 17, 17, 17, 17, 14), "P": (30, 17, 17, 30, 16, 16, 16),
    "Q": (14, 17, 17, 17, 21, 18, 13), "R": (30, 17, 17, 30, 20, 18, 17),
    "S": (15, 16, 16, 14, 1, 1, 30), "T": (31, 4, 4, 4, 4, 4, 4),
    "U": (17, 17, 17, 17, 17, 17, 14), "V": (17, 17, 17, 17, 17, 10, 4),
    "W": (17, 17, 17, 21, 21, 21, 10), "X": (17, 17, 10, 4, 10, 17, 17),
    "Y": (17, 17, 10, 4, 4, 4, 4), "Z": (31, 1, 2, 4, 8, 16, 31),
}

CATEGORIES = (
    ("machine-readable-code", "MACHINE_READABLE_CODE", "AUTOMATIC_PATTERN", "DEMO MATRIX"),
    ("email-address", "EMAIL", "AUTOMATIC_PATTERN", "DEV1@EXAMPLE.TEST"),
    ("phone-number", "PHONE", "AUTOMATIC_PATTERN", "2025550101"),
    ("payment-card-like", "PAYMENT_CARD", "AUTOMATIC_PATTERN", "4242424242424242"),
    ("verification-code", "VERIFICATION_CODE", "AUTOMATIC_PATTERN", "TEST CODE 042731"),
    ("person-name", "PERSON_NAME", "CONFIGURED_WATCHLIST", "AVERY EXAMPLE"),
    ("address", "ADDRESS", "CONFIGURED_WATCHLIST", "42 EXAMPLE WAY"),
    ("employer", "EMPLOYER", "CONFIGURED_WATCHLIST", "EXAMPLE LABS"),
    ("school", "SCHOOL", "CONFIGURED_WATCHLIST", "EXAMPLE ACADEMY"),
    ("document", "DOCUMENT", "CONFIGURED_ZONE", "FICTIONAL DOCUMENT"),
    ("badge", "BADGE", "CONFIGURED_ZONE", "DEMO BADGE"),
    ("parcel-label", "PARCEL_LABEL", "CONFIGURED_ZONE", "TEST PARCEL 204"),
    ("device-screen", "DEVICE_SCREEN", "CONFIGURED_ZONE", "DEMO SCREEN"),
)

HOLDOUT_TEXT = {
    "machine-readable-code": "HOLD MATRIX",
    "email-address": "HOLD1@EXAMPLE.TEST",
    "phone-number": "2025550198",
    "payment-card-like": "4000000000000002",
    "verification-code": "TEST CODE 805214",
    "person-name": "RILEY SAMPLE",
    "address": "77 SAMPLE ROAD",
    "employer": "SAMPLE WORKS",
    "school": "SAMPLE ACADEMY",
    "document": "SYNTHETIC DOCUMENT",
    "badge": "SAMPLE BADGE",
    "parcel-label": "TEST PARCEL 817",
    "device-screen": "SAMPLE SCREEN",
}


@dataclass(frozen=True)
class Fixture:
    fixture_id: str
    split: str
    category_id: str
    truth_category: str
    lane: str
    appearance: str
    seed: int
    payload_id: str
    source_group_id: str


def fixtures() -> list[Fixture]:
    result = []
    appearances = ("clean-stationary", "moving", "rotated-perspective",
                   "low-contrast-glare", "small-partial-edge")
    for split_index, split in enumerate(("DEVELOPMENT", "HOLDOUT")):
        prefix = "dev" if split == "DEVELOPMENT" else "holdout"
        for index, (category_id, truth_category, lane, _) in enumerate(CATEGORIES, 1):
            result.append(Fixture(
                f"pii-{prefix}-{index:02d}-{category_id}", split, category_id,
                truth_category, lane, appearances[(index - 1 + split_index * 2) % 5],
                71_000 + split_index * 20_000 + index * 173,
                f"payload-pii-{prefix}-{index:02d}",
                f"source-pii-{prefix}-{index:02d}",
            ))
    return result


def _text(fixture: Fixture) -> str:
    base = next(item[3] for item in CATEGORIES if item[0] == fixture.category_id)
    return HOLDOUT_TEXT[fixture.category_id] if fixture.split == "HOLDOUT" else base


def _put_pixel(data: bytearray, x: int, y: int, color: tuple[int, int, int]) -> None:
    if 0 <= x < WIDTH and 0 <= y < HEIGHT:
        offset = (y * WIDTH + x) * 3
        data[offset:offset + 3] = bytes(color)


def _verified_font(repo: Path | None = None) -> ImageFont.FreeTypeFont:
    if (PIL.__version__ != PILLOW_VERSION
            or pillow_features.version("freetype2") != FREETYPE_VERSION):
        raise RuntimeError("unsupported Pillow/FreeType fixture renderer")
    root = repo or Path(__file__).resolve().parents[2]
    font_path = root / FONT_RELATIVE_PATH
    license_path = root / FONT_LICENSE_RELATIVE_PATH
    if (not font_path.is_file() or font_path.stat().st_size != FONT_BYTES
            or _sha256(font_path) != FONT_SHA256
            or not license_path.is_file()
            or _sha256(license_path) != FONT_LICENSE_SHA256
            or "SIL OPEN FONT LICENSE Version 1.1" not in
            license_path.read_text(encoding="utf-8")):
        raise RuntimeError("pinned Noto Sans font or OFL license did not verify")
    return ImageFont.truetype(str(font_path), FONT_SIZE)


def _text_dimensions(text: str) -> tuple[int, int]:
    box = _verified_font().getbbox(text, stroke_width=FONT_STROKE_WIDTH)
    return box[2] - box[0], box[3] - box[1]


def _text_layer(text: str, color: tuple[int, int, int], rotated: bool) -> Image.Image:
    font = _verified_font()
    width, height = _text_dimensions(text)
    layer = Image.new("RGBA", (width + 12, height + 12), (0, 0, 0, 0))
    ImageDraw.Draw(layer).text(
        (6, 6), text, font=font, fill=(*color, 255),
        stroke_width=FONT_STROKE_WIDTH, stroke_fill=(*color, 255), anchor="lt")
    if rotated:
        layer = layer.transform(
            layer.size, Image.Transform.AFFINE, (1, -0.08, 0, 0.04, 1, 0),
            resample=Image.Resampling.BICUBIC)
        layer = layer.rotate(8, expand=True, resample=Image.Resampling.BICUBIC)
    return layer


def _text_ink_bounds(text: str, left: int, top: int, rotated: bool) -> tuple[int, int, int, int]:
    alpha_bounds = _text_layer(text, (1, 1, 1), rotated).getchannel("A").getbbox()
    if alpha_bounds is None:
        raise RuntimeError("natural-font renderer emitted no ink")
    return (left + alpha_bounds[0], top + alpha_bounds[1],
            left + alpha_bounds[2], top + alpha_bounds[3])


def _draw_text(data: bytearray, text: str, left: int, top: int, font_size: int,
               color: tuple[int, int, int], rotated: bool) -> None:
    if font_size != FONT_SIZE:
        raise ValueError("Priority 2 natural-font size must remain pinned")
    image = Image.frombytes("RGB", (WIDTH, HEIGHT), bytes(data))
    layer = _text_layer(text, color, rotated)
    image.paste(layer, (left, top), layer)
    data[:] = image.tobytes()


def _rect(data: bytearray, left: int, top: int, right: int, bottom: int,
          color: tuple[int, int, int]) -> None:
    for y in range(max(0, top), min(HEIGHT, bottom)):
        for x in range(max(0, left), min(WIDTH, right)):
            _put_pixel(data, x, y, color)


def _geometry(fixture: Fixture, frame: int) -> tuple[int, int, int, int, bool, int]:
    appearance = fixture.appearance
    text_width, text_height = _text_dimensions(_text(fixture))
    left, top, rotated = 40, 70, False
    right, bottom = left + text_width + 24, top + text_height + 28
    if fixture.category_id == "machine-readable-code":
        return 32, 32, 256, 256, appearance == "rotated-perspective", FONT_SIZE
    if appearance == "moving":
        left += frame * 6
        right += frame * 6
    elif appearance == "rotated-perspective":
        left, top, rotated = 145, 70, True
        ink = _text_ink_bounds(_text(fixture), left + 8, top + 8, True)
        right, bottom = ink[2] + 16, ink[3] + 16
    elif appearance == "low-contrast-glare":
        left, top = 42, 80
        right, bottom = left + text_width + 24, top + text_height + 28
    elif appearance == "small-partial-edge":
        right = WIDTH - 10
        left = right - text_width - 24
        top, bottom = 230, 230 + text_height + 28
    return left, top, right, bottom, rotated, FONT_SIZE


def _draw_symbol(data: bytearray, symbol: list[list[bool]], left: int, top: int,
                 rotated: bool) -> None:
    size = len(symbol)
    for row in range(size):
        for column in range(size):
            source_row = size - 1 - column if rotated else row
            source_column = row if rotated else column
            color = (15, 18, 22) if symbol[source_row][source_column] else (255, 255, 255)
            _put_pixel(data, left + column, top + row, color)


def paint_frame(fixture: Fixture, frame: int,
                symbol: list[list[bool]] | None = None) -> bytes:
    data = bytearray(WIDTH * HEIGHT * 3)
    for y in range(HEIGHT):
        for x in range(WIDTH):
            base = (fixture.seed + x * 3 + y * 5 + frame * 11) & 31
            _put_pixel(data, x, y, (28 + base, 45 + base, 64 + base))
    left, top, right, bottom, rotated, font_size = _geometry(fixture, frame)
    low_contrast = fixture.appearance == "low-contrast-glare"
    _rect(data, left, top, right, bottom, (104, 110, 118) if low_contrast else (235, 238, 230))
    color = (126, 130, 134) if low_contrast else (15, 18, 22)
    if fixture.category_id == "machine-readable-code":
        if symbol is None:
            raise RuntimeError("machine-readable fixture requires a standards-valid symbol")
        symbol_left = left + (right - left - len(symbol)) // 2
        symbol_top = top + (bottom - top - len(symbol)) // 2
        _draw_symbol(data, symbol, symbol_left, symbol_top, rotated)
    else:
        _draw_text(data, _text(fixture), left + 8, top + 8, font_size, color, rotated)
        if fixture.appearance == "low-contrast-glare":
            image = Image.frombytes("RGB", (WIDTH, HEIGHT), bytes(data))
            overlay = Image.new("RGBA", image.size, (0, 0, 0, 0))
            ImageDraw.Draw(overlay).polygon(
                [(left + 40, top), (left + 110, top),
                 (left + 175, bottom), (left + 105, bottom)],
                fill=(255, 255, 255, 72))
            image = Image.alpha_composite(image.convert("RGBA"), overlay).convert("RGB")
            data[:] = image.tobytes()
        elif fixture.appearance == "moving":
            image = Image.frombytes("RGB", (WIDTH, HEIGHT), bytes(data))
            crop = image.crop((left, top, right, bottom)).filter(
                ImageFilter.GaussianBlur(radius=0.65))
            image.paste(crop, (left, top))
            data[:] = image.tobytes()
    # Harmless/absent-watchlist control region is visually present but never protectable.
    _rect(data, 24, 286, 330, 346, (220, 226, 232))
    control = "OTHER SAMPLE" if fixture.lane == "CONFIGURED_WATCHLIST" else "PUBLIC DEMO"
    if fixture.lane == "CONFIGURED_ZONE":
        control = "SAFE AREA"
    _draw_text(data, control, 36, 298, FONT_SIZE, (30, 35, 40), False)
    return f"P6\n{WIDTH} {HEIGHT}\n255\n".encode() + bytes(data)


def _polygon(rect: tuple[int, int, int, int]) -> list[list[float]]:
    left, top, right, bottom = rect
    return [[max(0, left) / WIDTH, max(0, top) / HEIGHT],
            [min(WIDTH, right) / WIDTH, max(0, top) / HEIGHT],
            [min(WIDTH, right) / WIDTH, min(HEIGHT, bottom) / HEIGHT],
            [max(0, left) / WIDTH, min(HEIGHT, bottom) / HEIGHT]]


def truth(fixture: Fixture, frame: int) -> dict[str, object]:
    left, top, right, bottom, _, _ = _geometry(fixture, frame)
    role = "SENSITIVE" if fixture.lane != "CONFIGURED_WATCHLIST" else "UNKNOWN"
    objects = [{
        "objectId": f"protected-{fixture.fixture_id}", "category": fixture.truth_category,
        "role": role, "polygon": _polygon((left, top, right, bottom)),
        "visibility": 1.0, "protectable": True, "legible": True,
    }]
    if fixture.lane == "CONFIGURED_ZONE":
        inset = (left + 4, top + 6, max(left + 5, right - 4), min(bottom, top + 22))
        objects.append({
            "objectId": f"text-{fixture.fixture_id}", "category": fixture.truth_category,
            "role": "SENSITIVE", "polygon": _polygon(inset),
            "visibility": 1.0, "protectable": True, "legible": True,
        })
    objects.append({
        "objectId": f"control-{fixture.fixture_id}", "category": fixture.truth_category,
        "role": "DECOY", "polygon": _polygon((24, 286, 330, 346)),
        "visibility": 1.0, "protectable": False, "legible": True,
    })
    return {
        "schemaVersion": "1.0.0", "fixtureId": fixture.fixture_id,
        "frameIndex": frame, "sourceTimestampNs": frame * FRAME_DURATION_NS,
        "transform": {"rotationDegrees": 0,
                      "mirrored": False, "crop": [0.0, 0.0, 1.0, 1.0],
                      "sensorToBuffer": [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0]},
        "objects": objects, "expectedState": "REGIONAL_PROTECTION",
        "requiredAction": "PROTECT_REGIONS",
        "generatorSeedOrPayloadId": fixture.payload_id,
    }


def _read_pbm(path: Path) -> list[list[bool]]:
    tokens = path.read_text(encoding="ascii").split()
    if len(tokens) < 3 or tokens[0] != "P1":
        raise RuntimeError("symbol generator did not emit an ASCII PBM")
    width, height = int(tokens[1]), int(tokens[2])
    if width != height or width <= 0 or len(tokens) != 3 + width * height:
        raise RuntimeError("symbol generator emitted invalid dimensions")
    values = [value == "1" for value in tokens[3:]]
    return [values[row * width:(row + 1) * width] for row in range(height)]


def _symbol(fixture: Fixture, java: str, generator_jar: Path,
            temporary: Path) -> list[list[bool]] | None:
    if fixture.category_id != "machine-readable-code":
        return None
    format_name = "QR_CODE" if fixture.split == "DEVELOPMENT" else "DATA_MATRIX"
    output = temporary / "symbol.pbm"
    fictional_payload = f"FICTIONAL-{fixture.payload_id}"
    subprocess.run([
        java, "-jar", str(generator_jar), format_name, fictional_payload, str(output)
    ], check=True)
    return _read_pbm(output)


def _write_video(fixture: Fixture, path: Path, ffmpeg: str,
                 java: str, generator_jar: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="liveshield-pii-") as temporary:
        frame_dir = Path(temporary)
        symbol = _symbol(fixture, java, generator_jar, frame_dir)
        for frame in range(FRAME_COUNT):
            (frame_dir / f"frame-{frame:03d}.ppm").write_bytes(
                paint_frame(fixture, frame, symbol))
        subprocess.run([
            ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-framerate", str(FRAME_RATE),
            "-i", str(frame_dir / "frame-%03d.ppm"), "-map", "0:v:0", "-an", "-c:v",
            "libx264", "-preset", "veryslow", "-crf", "0", "-pix_fmt", "yuv420p",
            "-threads", "1", "-fflags", "+bitexact", "-flags:v", "+bitexact",
            "-map_metadata", "-1", str(path),
        ], check=True)


def _verify_silent(path: Path, ffprobe: str) -> None:
    result = subprocess.run([
        ffprobe, "-v", "error", "-show_entries", "stream=codec_type",
        "-of", "default=noprint_wrappers=1:nokey=1", str(path),
    ], check=True, capture_output=True, text=True)
    if result.stdout.split() != ["video"]:
        raise RuntimeError(f"{path} must contain exactly one video stream")


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _synthetic_source() -> dict[str, object]:
    return {
        "generatorId": "liveshield-priority2-fixtures",
        "generatorVersion": GENERATOR_VERSION,
        "symbolGenerator": SYMBOL_GENERATOR_VERSION,
        "font": {
            "artifact": "NotoSans-wdth-wght-32px-stroke2",
            "sha256": FONT_SHA256,
            "license": "OFL-1.1",
            "upstreamCommit": FONT_UPSTREAM_COMMIT,
            "rasterizer": f"Pillow-{PILLOW_VERSION}-FreeType-{FREETYPE_VERSION}",
        },
        "fictionalPayloadVerified": True,
    }


def generate(repo: Path, ffmpeg: str, ffprobe: str,
             java: str, symbol_generator_jar: Path) -> Path:
    _verified_font(repo)
    media_root = repo / "test-fixtures" / "media" / "pii-v1"
    truth_root = repo / "test-fixtures" / "annotations" / "pii-v1"
    manifest = repo / "test-fixtures" / "manifests" / "pii-v1.jsonl"
    records = []
    for fixture in fixtures():
        media = media_root / f"{fixture.fixture_id}.mp4"
        truth_path = truth_root / f"{fixture.fixture_id}.jsonl"
        _write_video(fixture, media, ffmpeg, java, symbol_generator_jar)
        _verify_silent(media, ffprobe)
        truth_path.parent.mkdir(parents=True, exist_ok=True)
        truth_path.write_text("\n".join(
            json.dumps(truth(fixture, frame), sort_keys=True, separators=(",", ":"))
            for frame in range(FRAME_COUNT)) + "\n", encoding="utf-8")
        records.append({
            "schemaVersion": "1.0.0", "corpusVersion": "1.0.0",
            "fixtureId": fixture.fixture_id, "group": "PRIORITY_2", "sourceKind": "SYNTHETIC",
            "split": fixture.split,
            "scenarioIds": [fixture.lane, fixture.category_id, fixture.appearance,
                            "harmless-control",
                            "absent-watchlist-control" if fixture.lane == "CONFIGURED_WATCHLIST"
                            else "non-sensitive-control"],
            "sourcePath": f"pii-v1/{fixture.fixture_id}.mp4", "sourceDigest": _sha256(media),
            "provenanceRef": "generated:liveshield-priority2-fixtures-v2",
            "truthPath": f"pii-v1/{fixture.fixture_id}.jsonl", "mediaStreams": ["VIDEO"],
            "leakageKeys": {"sourceGroupId": fixture.source_group_id, "actorIds": [],
                            "payloadIds": [fixture.payload_id],
                            "generatorSeeds": [f"seed-{fixture.seed}"],
                            "roomMotionIds": [f"motion-{fixture.fixture_id}"]},
            "deviceContext": {"deviceClass": "synthetic-priority2-source", "lens": "REAR",
                              "width": WIDTH, "height": HEIGHT, "frameRate": FRAME_RATE},
            "syntheticSource": _synthetic_source(),
        })
    records.sort(key=lambda item: item["fixtureId"])
    manifest.write_text("\n".join(
        json.dumps(item, sort_keys=True, separators=(",", ":")) for item in records) + "\n",
        encoding="utf-8")
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--ffmpeg", default=shutil.which("ffmpeg"))
    parser.add_argument("--ffprobe", default=shutil.which("ffprobe"))
    configured_java_home = os.environ.get("JAVA_HOME")
    parser.add_argument(
        "--java",
        default=(str(Path(configured_java_home) / "bin/java")
                 if configured_java_home else shutil.which("java")),
    )
    parser.add_argument(
        "--symbol-generator-jar",
        type=Path,
        default=Path(__file__).resolve().parents[2]
        / "test-fixtures/build/libs/test-fixtures-priority2-symbol-generator.jar",
    )
    args = parser.parse_args()
    if not args.ffmpeg or not args.ffprobe or not args.java:
        parser.error("ffmpeg, ffprobe, and Java are required")
    if not args.symbol_generator_jar.is_file():
        parser.error("build :test-fixtures:priorityTwoSymbolGeneratorJar first")
    manifest = generate(
        args.repo.resolve(), args.ffmpeg, args.ffprobe,
        args.java, args.symbol_generator_jar.resolve())
    print(f"generated {len(fixtures())} fictional Priority 2 fixtures: {manifest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
