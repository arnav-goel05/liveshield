#!/usr/bin/env python3
"""Verify and convert the official BIV-Priv-Seg support set without extracting it."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import zipfile
from collections import Counter
from pathlib import Path, PurePosixPath
from typing import Any, Iterable


SUPPORT_IMAGES_URL = "https://vizwiz.cs.colorado.edu/biv-priv/images/support_images.zip"
SUPPORT_ANNOTATIONS_URL = "https://vizwiz.cs.colorado.edu/biv-priv/images/support_set.json"
DATASET_URL = "https://vizwiz.org/tasks-and-datasets/object-localization/"
LICENSE_URL = "https://creativecommons.org/licenses/by/4.0/"
SHA256_PATTERN = re.compile(r"^[0-9a-fA-F]{64}$")


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_file(path: Path, expected_bytes: int, expected_sha256: str) -> None:
    if expected_bytes < 0:
        raise ValueError("expected byte length cannot be negative")
    if not SHA256_PATTERN.fullmatch(expected_sha256):
        raise ValueError("expected SHA-256 must contain exactly 64 hexadecimal characters")
    actual_bytes = path.stat().st_size
    if actual_bytes != expected_bytes:
        raise ValueError(
            f"byte-length mismatch for {path}: expected {expected_bytes}, got {actual_bytes}"
        )
    actual_sha256 = file_sha256(path)
    if actual_sha256.lower() != expected_sha256.lower():
        raise ValueError(
            f"SHA-256 mismatch for {path}: expected {expected_sha256}, got {actual_sha256}"
        )


def safe_archive_members(archive: zipfile.ZipFile) -> dict[str, zipfile.ZipInfo]:
    members: dict[str, zipfile.ZipInfo] = {}
    basenames: Counter[str] = Counter()
    for info in archive.infolist():
        if info.is_dir():
            continue
        normalized = info.filename.replace("\\", "/")
        path = PurePosixPath(normalized)
        if path.is_absolute() or ".." in path.parts or not path.parts:
            raise ValueError(f"unsafe ZIP member: {info.filename!r}")
        key = str(path)
        if key in members:
            raise ValueError(f"duplicate ZIP member: {key}")
        members[key] = info
        basenames[path.name] += 1
    for basename, count in basenames.items():
        if count > 1:
            raise ValueError(f"ambiguous duplicate ZIP basename: {basename}")
    return members


def find_member(members: dict[str, zipfile.ZipInfo], filename: str) -> zipfile.ZipInfo:
    normalized = str(PurePosixPath(filename.replace("\\", "/")))
    direct = members.get(normalized)
    if direct is not None:
        return direct
    matches = [info for name, info in members.items() if PurePosixPath(name).name == PurePosixPath(normalized).name]
    if len(matches) != 1:
        raise ValueError(f"annotation image is absent or ambiguous in archive: {filename}")
    return matches[0]


def member_sha256(archive: zipfile.ZipFile, info: zipfile.ZipInfo) -> str:
    digest = hashlib.sha256()
    with archive.open(info, "r") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _required_list(document: dict[str, Any], key: str) -> list[dict[str, Any]]:
    value = document.get(key)
    if not isinstance(value, list) or not all(isinstance(item, dict) for item in value):
        raise ValueError(f"COCO annotations require an object list named {key!r}")
    return value


def convert_annotations(
    document: dict[str, Any], archive: zipfile.ZipFile, expected_count: int
) -> list[dict[str, Any]]:
    images = _required_list(document, "images")
    categories = _required_list(document, "categories")
    annotations = _required_list(document, "annotations")
    if len(images) != expected_count or len(categories) != expected_count:
        raise ValueError(
            f"expected {expected_count} images and categories; got {len(images)} images and "
            f"{len(categories)} categories"
        )

    image_by_id = {item.get("id"): item for item in images}
    category_by_id = {item.get("id"): item for item in categories}
    if len(image_by_id) != len(images) or None in image_by_id:
        raise ValueError("image IDs must be present and unique")
    if len(category_by_id) != len(categories) or None in category_by_id:
        raise ValueError("category IDs must be present and unique")

    category_counts: Counter[Any] = Counter()
    regions_by_image: dict[Any, list[dict[str, Any]]] = {image_id: [] for image_id in image_by_id}
    for annotation in annotations:
        image_id = annotation.get("image_id")
        category_id = annotation.get("category_id")
        if image_id not in image_by_id or category_id not in category_by_id:
            raise ValueError("annotation references an unknown image or category")
        bbox = annotation.get("bbox")
        if not isinstance(bbox, list) or len(bbox) != 4 or not all(
            isinstance(value, (int, float)) for value in bbox
        ):
            raise ValueError("every BIV support annotation requires a numeric [x,y,w,h] bbox")
        if bbox[2] <= 0 or bbox[3] <= 0:
            raise ValueError("BIV support bounding boxes must have positive width and height")
        category_counts[category_id] += 1
        category = category_by_id[category_id]
        regions_by_image[image_id].append(
            {
                "annotation_id": annotation.get("id"),
                "area": annotation.get("area"),
                "bbox_xywh": bbox,
                "category_id": category_id,
                "category_name": category.get("name"),
                "iscrowd": annotation.get("iscrowd", 0),
                "segmentation": annotation.get("segmentation", []),
            }
        )

    if set(category_counts) != set(category_by_id) or any(count != 1 for count in category_counts.values()):
        raise ValueError("the support set must contain exactly one annotation for each category")
    if any(not regions for regions in regions_by_image.values()):
        raise ValueError("every support image must contain an annotation")

    members = safe_archive_members(archive)
    records: list[dict[str, Any]] = []
    for image_id in sorted(image_by_id, key=lambda value: str(value)):
        image = image_by_id[image_id]
        filename = image.get("file_name")
        if not isinstance(filename, str) or not filename:
            raise ValueError("every BIV support image requires file_name")
        info = find_member(members, filename)
        records.append(
            {
                "attribution_ref": "BIV-Priv-Seg-CC-BY-4.0",
                "fixture_id": f"biv-priv-seg-support:{image_id}",
                "height": image.get("height"),
                "image_path": info.filename,
                "image_sha256": member_sha256(archive, info),
                "license": "CC BY 4.0",
                "regions": sorted(
                    regions_by_image[image_id], key=lambda region: str(region["category_id"])
                ),
                "source_kind": "LICENSED_PUBLIC",
                "split": "SMOKE",
                "width": image.get("width"),
            }
        )
    return records


def write_jsonl(records: Iterable[dict[str, Any]], output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8", newline="\n") as destination:
        for record in records:
            destination.write(json.dumps(record, sort_keys=True, separators=(",", ":")))
            destination.write("\n")


def write_attribution(
    output: Path,
    images_sha256: str,
    annotations_sha256: str,
) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    content = "\n".join(
            (
                "# BIV-Priv-Seg support-set attribution",
                "",
                "BIV-Priv-Seg: Locating Private Content in Images Taken by People With Visual Impairments",
                "by Yu-Yun Tseng, Tanusree Sharma, Lotus Zhang, Abigale Stangl, Leah Findlater,",
                "Yang Wang, and Danna Gurari (WACV 2025).",
                "",
                f"Dataset: {DATASET_URL}",
                f"Support images: {SUPPORT_IMAGES_URL}",
                f"Support annotations: {SUPPORT_ANNOTATIONS_URL}",
                f"Licence: Creative Commons Attribution 4.0 International ({LICENSE_URL})",
                f"Verified support-images SHA-256: {images_sha256.lower()}",
                f"Verified support-annotations SHA-256: {annotations_sha256.lower()}",
                "",
                "LiveShield uses these 16 unmodified support images only as individual local smoke",
                "tests. They are not packaged in the application and are not evidence of accuracy.",
                "",
            )
        )
    with output.open("w", encoding="utf-8", newline="\n") as destination:
        destination.write(content)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--images-archive", required=True, type=Path)
    parser.add_argument("--images-bytes", required=True, type=int)
    parser.add_argument("--images-sha256", required=True)
    parser.add_argument("--annotations", required=True, type=Path)
    parser.add_argument("--annotations-bytes", required=True, type=int)
    parser.add_argument("--annotations-sha256", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--attribution-output", required=True, type=Path)
    parser.add_argument("--expected-count", default=16, type=int)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.expected_count <= 0:
        raise ValueError("expected count must be positive")
    verify_file(args.images_archive, args.images_bytes, args.images_sha256)
    verify_file(args.annotations, args.annotations_bytes, args.annotations_sha256)
    document = json.loads(args.annotations.read_text(encoding="utf-8"))
    if not isinstance(document, dict):
        raise ValueError("BIV support annotations must be a JSON object")
    with zipfile.ZipFile(args.images_archive, "r") as archive:
        records = convert_annotations(document, archive, args.expected_count)
    write_jsonl(records, args.output)
    write_attribution(args.attribution_output, args.images_sha256, args.annotations_sha256)
    print(f"prepared {len(records)} BIV-Priv-Seg support fixtures into {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
