#!/usr/bin/env python3
"""Prepare LiveShield's 216-item public corpus without fully extracting source archives."""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import zipfile
from pathlib import Path, PurePosixPath
from typing import Any, Iterable

import prepare_biv_support as biv
import select_wider_subset as wider


CORPUS_VERSION = "1.0.0"
RETRIEVED_AT = "2026-08-13"
WIDER_ARCHIVE_URL = (
    "https://huggingface.co/datasets/CUHK-CSE/wider_face/resolve/"
    "833d07e7bf3860f294242312fe95eed0561eeb17/data/WIDER_val.zip"
)
WIDER_DATASET_VERSION = "validation@833d07e7bf3860f294242312fe95eed0561eeb17"
WIDER_ATTRIBUTION = "WIDER FACE by Shuo Yang, Ping Luo, Chen Change Loy, and Xiaoou Tang (CVPR 2016)"
BIV_DATASET_VERSION = "support-set-2024-03-19"
BIV_ATTRIBUTION = "BIV-Priv-Seg by Yu-Yun Tseng et al. (WACV 2025)"


def write_jsonl(path: Path, records: Iterable[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as destination:
        for record in records:
            destination.write(json.dumps(record, sort_keys=True, separators=(",", ":")))
            destination.write("\n")


def truth_record(fixture_id: str, objects: list[dict[str, Any]]) -> dict[str, Any]:
    return {
        "schemaVersion": "1.0.0",
        "fixtureId": fixture_id,
        "frameIndex": 0,
        "sourceTimestampNs": 0,
        "transform": {
            "rotationDegrees": 0,
            "mirrored": False,
            "crop": [0, 0, 1, 1],
            "sensorToBuffer": [1, 0, 0, 0, 1, 0, 0, 0, 1],
        },
        "objects": objects,
        "expectedState": "REGIONAL_PROTECTION",
        "requiredAction": "PROTECT_REGIONS",
    }


def normalized_box(x: float, y: float, width: float, height: float, image_width: int, image_height: int) -> list[list[float]]:
    left = max(0.0, min(1.0, x / image_width))
    top = max(0.0, min(1.0, y / image_height))
    right = max(left, min(1.0, (x + width) / image_width))
    bottom = max(top, min(1.0, (y + height) / image_height))
    return [[left, top], [right, top], [right, bottom], [left, bottom]]


def extract_zip_member(archive: zipfile.ZipFile, member: zipfile.ZipInfo, output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    with archive.open(member, "r") as source, output.open("wb") as destination:
        shutil.copyfileobj(source, destination, length=1024 * 1024)


def jpeg_dimensions(path: Path) -> tuple[int, int]:
    """Read JPEG dimensions without decoding or introducing an image dependency."""
    with path.open("rb") as source:
        if source.read(2) != b"\xff\xd8":
            raise ValueError(f"not a JPEG image: {path}")
        while True:
            marker_start = source.read(1)
            if not marker_start:
                break
            if marker_start != b"\xff":
                continue
            marker = source.read(1)
            while marker == b"\xff":
                marker = source.read(1)
            if not marker or marker in {b"\xd8", b"\xd9"}:
                continue
            length_bytes = source.read(2)
            if len(length_bytes) != 2:
                break
            segment_length = int.from_bytes(length_bytes, "big")
            if segment_length < 2:
                raise ValueError(f"invalid JPEG segment in {path}")
            if marker[0] in {0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF}:
                payload = source.read(5)
                if len(payload) != 5:
                    break
                height = int.from_bytes(payload[1:3], "big")
                width = int.from_bytes(payload[3:5], "big")
                if width <= 0 or height <= 0:
                    break
                return width, height
            source.seek(segment_length - 2, 1)
    raise ValueError(f"JPEG dimensions not found: {path}")


def wider_records(args: argparse.Namespace) -> list[dict[str, Any]]:
    annotations = wider.parse_annotations(args.wider_annotations)
    selections = wider.select_annotations(annotations)
    records: list[dict[str, Any]] = []
    with zipfile.ZipFile(args.wider_images_archive) as archive:
        members = {info.filename: info for info in archive.infolist() if not info.is_dir()}
        for index, (annotation, primary_slice, labels) in enumerate(selections, 1):
            archive_name = f"WIDER_val/images/{annotation.filename}"
            member = members.get(archive_name)
            if member is None:
                raise ValueError(f"selected WIDER image missing from archive: {archive_name}")
            source_relative = Path("wider") / Path(*PurePosixPath(annotation.filename).parts)
            source_path = args.media_root / source_relative
            extract_zip_member(archive, member, source_path)
            image_width, image_height = jpeg_dimensions(source_path)
            fixture_id = f"wider-regression-{index:03d}"
            truth_relative = Path("public-v1") / "wider" / f"{index:03d}.jsonl"
            valid_boxes = [box for box in annotation.boxes if wider.is_valid_box(box)]
            objects = [
                {
                    "objectId": f"face-{box_index:03d}",
                    "category": "FACE",
                    "role": "UNKNOWN",
                    "polygon": normalized_box(
                        box.x,
                        box.y,
                        box.width,
                        box.height,
                        image_width,
                        image_height,
                    ),
                    "visibility": 1.0,
                    "protectable": True,
                    "legible": False,
                }
                for box_index, box in enumerate(valid_boxes, 1)
            ]
            write_jsonl(args.truth_root / truth_relative, [truth_record(fixture_id, objects)])
            records.append(
                {
                    "schemaVersion": "1.0.0",
                    "corpusVersion": CORPUS_VERSION,
                    "fixtureId": fixture_id,
                    "group": "WIDER_FACE",
                    "sourceKind": "LICENSED_PUBLIC",
                    "split": "REGRESSION",
                    "scenarioIds": [
                        f"primary-{primary_slice}",
                        *(f"slice-{label}" for label in labels),
                    ],
                    "sourcePath": source_relative.as_posix(),
                    "sourceDigest": wider.file_sha256(source_path),
                    "provenanceRef": "dataset:wider-face-validation",
                    "truthPath": truth_relative.as_posix(),
                    "mediaStreams": ["VIDEO"],
                    "leakageKeys": {
                        "sourceGroupId": f"wider:{hashlib.sha256(annotation.filename.encode()).hexdigest()[:20]}",
                        "actorIds": [],
                        "payloadIds": [],
                        "generatorSeeds": [],
                        "roomMotionIds": [],
                    },
                    "publicDataset": {
                        "sourceUrl": WIDER_ARCHIVE_URL,
                        "datasetVersion": WIDER_DATASET_VERSION,
                        "license": "CC BY-NC-ND 4.0",
                        "allowedUsage": "local non-commercial unmodified detector evaluation only",
                        "attribution": WIDER_ATTRIBUTION,
                        "retrievedAt": RETRIEVED_AT,
                        "byteLength": source_path.stat().st_size,
                        "selectionSeed": wider.SELECTION_SEED,
                        "sourceItemId": annotation.filename,
                    },
                }
            )
    return records


def biv_records(args: argparse.Namespace) -> list[dict[str, Any]]:
    document = json.loads(args.biv_annotations.read_text(encoding="utf-8"))
    with zipfile.ZipFile(args.biv_images_archive) as archive:
        converted = biv.convert_annotations(document, archive, 16)
        members = biv.safe_archive_members(archive)
        records: list[dict[str, Any]] = []
        for index, item in enumerate(converted, 1):
            member = members[item["image_path"]]
            source_relative = Path("biv-support") / PurePosixPath(item["image_path"]).name
            source_path = args.media_root / source_relative
            extract_zip_member(archive, member, source_path)
            fixture_id = f"biv-smoke-{index:03d}"
            truth_relative = Path("public-v1") / "biv" / f"{index:03d}.jsonl"
            objects = []
            for region_index, region in enumerate(item["regions"], 1):
                objects.append(
                    {
                        "objectId": f"sensitive-{region_index:03d}",
                        "category": "BIV_PRIVATE_OBJECT",
                        "role": "SENSITIVE",
                        "polygon": normalized_box(*region["bbox_xywh"], item["width"], item["height"]),
                        "visibility": 1.0,
                        "protectable": True,
                        "legible": False,
                    }
                )
            write_jsonl(args.truth_root / truth_relative, [truth_record(fixture_id, objects)])
            records.append(
                {
                    "schemaVersion": "1.0.0",
                    "corpusVersion": CORPUS_VERSION,
                    "fixtureId": fixture_id,
                    "group": "BIV_PRIV_SEG",
                    "sourceKind": "LICENSED_PUBLIC",
                    "split": "SMOKE",
                    "scenarioIds": [f"biv-{item['regions'][0]['category_name'].replace('_', '-') }"],
                    "sourcePath": source_relative.as_posix(),
                    "sourceDigest": item["image_sha256"],
                    "provenanceRef": "dataset:biv-priv-seg-support",
                    "truthPath": truth_relative.as_posix(),
                    "mediaStreams": ["VIDEO"],
                    "leakageKeys": {
                        "sourceGroupId": f"biv:{str(item['fixture_id']).split(':')[-1]}",
                        "actorIds": [],
                        "payloadIds": [],
                        "generatorSeeds": [],
                        "roomMotionIds": [],
                    },
                    "publicDataset": {
                        "sourceUrl": biv.SUPPORT_IMAGES_URL,
                        "datasetVersion": BIV_DATASET_VERSION,
                        "license": "CC BY 4.0",
                        "allowedUsage": "local unmodified detector localization smoke evaluation",
                        "attribution": BIV_ATTRIBUTION,
                        "retrievedAt": RETRIEVED_AT,
                        "byteLength": source_path.stat().st_size,
                        "selectionSeed": "biv-support-complete-v1",
                        "sourceItemId": PurePosixPath(item["image_path"]).name,
                    },
                }
            )
    return records


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--wider-images-archive", required=True, type=Path)
    parser.add_argument("--wider-annotations", required=True, type=Path)
    parser.add_argument("--biv-images-archive", required=True, type=Path)
    parser.add_argument("--biv-annotations", required=True, type=Path)
    parser.add_argument("--media-root", required=True, type=Path)
    parser.add_argument("--truth-root", required=True, type=Path)
    parser.add_argument("--manifest-output", required=True, type=Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    args.media_root.mkdir(parents=True, exist_ok=True)
    records = wider_records(args) + biv_records(args)
    write_jsonl(args.manifest_output, records)
    print(f"prepared {len(records)} public fixtures into {args.manifest_output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
