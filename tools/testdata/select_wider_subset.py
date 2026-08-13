#!/usr/bin/env python3
"""Select LiveShield's deterministic five-slice WIDER FACE validation subset."""

from __future__ import annotations

import argparse
import hashlib
import json
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Iterable, Iterator, Sequence


SELECTION_SEED = "liveshield-wider-v1"
SLICE_ORDER = (
    "small",
    "heavy_blur",
    "heavy_occlusion",
    "difficult_capture",
    "baseline",
)


@dataclass(frozen=True)
class FaceBox:
    x: int
    y: int
    width: int
    height: int
    blur: int
    illumination: int
    invalid: int
    occlusion: int
    pose: int


@dataclass(frozen=True)
class ImageAnnotation:
    filename: str
    boxes: tuple[FaceBox, ...]


def is_valid_box(box: FaceBox) -> bool:
    """Reject invalid flags and misleading all-zero/non-positive official records."""
    return box.invalid == 0 and box.width > 0 and box.height > 0


def _safe_relative_name(value: str) -> str:
    normalized = value.replace("\\", "/")
    path = PurePosixPath(normalized)
    if path.is_absolute() or ".." in path.parts or not path.parts:
        raise ValueError(f"unsafe WIDER filename: {value!r}")
    return str(path)


def parse_annotations(path: Path) -> list[ImageAnnotation]:
    """Parse the official wider_face_val_bbx_gt.txt representation."""
    lines = path.read_text(encoding="utf-8-sig").splitlines()
    result: list[ImageAnnotation] = []
    index = 0
    seen: set[str] = set()
    while index < len(lines):
        filename_line = lines[index].strip()
        index += 1
        if not filename_line:
            continue
        filename = _safe_relative_name(filename_line)
        if filename in seen:
            raise ValueError(f"duplicate WIDER annotation entry: {filename}")
        if index >= len(lines):
            raise ValueError(f"missing face count after {filename}")
        try:
            face_count = int(lines[index].strip())
        except ValueError as exc:
            raise ValueError(f"invalid face count after {filename}") from exc
        index += 1
        if face_count < 0:
            raise ValueError(f"negative face count after {filename}")

        boxes: list[FaceBox] = []
        # The official WIDER format retains one all-zero placeholder line for
        # images whose declared face count is zero.
        if face_count == 0:
            if index >= len(lines):
                raise ValueError(f"missing zero-face placeholder after {filename}")
            placeholder = lines[index].split()
            index += 1
            if len(placeholder) != 10 or any(value != "0" for value in placeholder):
                raise ValueError(f"invalid zero-face placeholder after {filename}")
        for _ in range(face_count):
            if index >= len(lines):
                raise ValueError(f"truncated face boxes for {filename}")
            fields = lines[index].split()
            index += 1
            if len(fields) < 10:
                raise ValueError(f"face box for {filename} has fewer than 10 fields")
            try:
                values = [int(value) for value in fields[:10]]
            except ValueError as exc:
                raise ValueError(f"non-integer face box for {filename}") from exc
            boxes.append(
                FaceBox(
                    x=values[0],
                    y=values[1],
                    width=values[2],
                    height=values[3],
                    blur=values[4],
                    illumination=values[6],
                    invalid=values[7],
                    occlusion=values[8],
                    pose=values[9],
                )
            )
        result.append(ImageAnnotation(filename=filename, boxes=tuple(boxes)))
        seen.add(filename)
    return result


def applicable_slices(annotation: ImageAnnotation) -> tuple[str, ...]:
    valid = tuple(box for box in annotation.boxes if is_valid_box(box))
    labels: list[str] = []
    if any(box.width <= 20 or box.height <= 20 for box in valid):
        labels.append("small")
    if any(box.blur == 2 for box in valid):
        labels.append("heavy_blur")
    if any(box.occlusion == 2 for box in valid):
        labels.append("heavy_occlusion")
    if any(box.illumination > 0 or box.pose > 0 for box in valid):
        labels.append("difficult_capture")
    if not labels:
        labels.append("baseline")
    return tuple(labels)


def selection_hash(filename: str, seed: str = SELECTION_SEED) -> str:
    return hashlib.sha256((seed + filename).encode("utf-8")).hexdigest()


def select_annotations(
    annotations: Iterable[ImageAnnotation], per_slice: int = 40, seed: str = SELECTION_SEED
) -> list[tuple[ImageAnnotation, str, tuple[str, ...]]]:
    if per_slice <= 0:
        raise ValueError("per-slice count must be positive")
    indexed = [
        (item, applicable_slices(item))
        for item in annotations
        if any(is_valid_box(box) for box in item.boxes)
    ]
    selected_names: set[str] = set()
    selected: list[tuple[ImageAnnotation, str, tuple[str, ...]]] = []
    for primary_slice in SLICE_ORDER:
        candidates = [
            (item, labels)
            for item, labels in indexed
            if primary_slice in labels and item.filename not in selected_names
        ]
        candidates.sort(key=lambda pair: (selection_hash(pair[0].filename, seed), pair[0].filename))
        if len(candidates) < per_slice:
            raise ValueError(
                f"slice {primary_slice!r} has {len(candidates)} unselected candidates; "
                f"{per_slice} required"
            )
        for item, labels in candidates[:per_slice]:
            selected_names.add(item.filename)
            selected.append((item, primary_slice, labels))
    return selected


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def build_records(
    selections: Sequence[tuple[ImageAnnotation, str, tuple[str, ...]]],
    images_root: Path,
    seed: str,
) -> Iterator[dict[str, object]]:
    resolved_root = images_root.resolve()
    for annotation, primary_slice, labels in selections:
        image_path = images_root.joinpath(*PurePosixPath(annotation.filename).parts)
        resolved_image = image_path.resolve()
        if resolved_root != resolved_image and resolved_root not in resolved_image.parents:
            raise ValueError(f"image escaped root: {annotation.filename}")
        if not resolved_image.is_file():
            raise FileNotFoundError(f"WIDER image not found: {image_path}")
        yield {
            "applicable_slices": list(labels),
            "fixture_id": f"wider-face:{annotation.filename}",
            "image_path": annotation.filename,
            "image_sha256": file_sha256(resolved_image),
            "license": "CC BY-NC-ND 4.0",
            "primary_slice": primary_slice,
            "selection_hash": selection_hash(annotation.filename, seed),
            "selection_seed": seed,
            "source_kind": "LICENSED_PUBLIC",
            "split": "REGRESSION",
        }


def write_jsonl(records: Iterable[dict[str, object]], output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8", newline="\n") as destination:
        for record in records:
            destination.write(json.dumps(record, sort_keys=True, separators=(",", ":")))
            destination.write("\n")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--annotations", required=True, type=Path)
    parser.add_argument("--images-root", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--per-slice", default=40, type=int)
    parser.add_argument("--seed", default=SELECTION_SEED)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    annotations = parse_annotations(args.annotations)
    selections = select_annotations(annotations, args.per_slice, args.seed)
    write_jsonl(build_records(selections, args.images_root, args.seed), args.output)
    print(f"selected {len(selections)} unique WIDER images into {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
