#!/usr/bin/env python3
"""Evaluate Priority 2 or BIV findings without exposing fixture payload text."""

from __future__ import annotations

import argparse
import json
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


GRID_SIZE = 256
LANES = {"AUTOMATIC_PATTERN", "CONFIGURED_WATCHLIST", "CONFIGURED_ZONE"}


@dataclass(frozen=True)
class Target:
    category: str
    polygon: tuple[tuple[float, float], ...]


@dataclass(frozen=True)
class Finding:
    category: str
    polygon: tuple[tuple[float, float], ...]


@dataclass
class Counts:
    frames: int = 0
    targets: int = 0
    recalled: int = 0
    truth_cells: int = 0
    covered_cells: int = 0
    predicted_cells: int = 0
    excessive_cells: int = 0
    findings: int = 0
    false_positives: int = 0

    def add(self, other: "Counts") -> None:
        for field in self.__dataclass_fields__:
            setattr(self, field, getattr(self, field) + getattr(other, field))

    def record(self) -> dict[str, object]:
        return {
            "frames": self.frames,
            "recall": ratio(self.recalled, self.targets),
            "localizationCoverage": ratio(self.covered_cells, self.truth_cells),
            "excessiveMask": ratio(self.excessive_cells, GRID_SIZE * GRID_SIZE * self.frames),
            "falsePositives": ratio(self.false_positives, self.findings),
        }


def ratio(numerator: int, denominator: int) -> dict[str, object]:
    return {"numerator": numerator, "denominator": denominator,
            "value": None if denominator == 0 else numerator / denominator}


def point_in_polygon(x: float, y: float, polygon: tuple[tuple[float, float], ...]) -> bool:
    inside = False
    previous = polygon[-1]
    for current in polygon:
        x1, y1 = previous
        x2, y2 = current
        if (y1 > y) != (y2 > y):
            crossing = (x2 - x1) * (y - y1) / (y2 - y1) + x1
            if x < crossing:
                inside = not inside
        previous = current
    return inside


def cells(polygon: tuple[tuple[float, float], ...]) -> set[int]:
    if len(polygon) < 3:
        raise ValueError("polygon requires at least three points")
    for point in polygon:
        if len(point) != 2 or any(value < 0 or value > 1 for value in point):
            raise ValueError("polygon coordinates must be normalized")
    left = max(0, int(min(point[0] for point in polygon) * GRID_SIZE))
    right = min(GRID_SIZE, int(max(point[0] for point in polygon) * GRID_SIZE) + 1)
    top = max(0, int(min(point[1] for point in polygon) * GRID_SIZE))
    bottom = min(GRID_SIZE, int(max(point[1] for point in polygon) * GRID_SIZE) + 1)
    result = set()
    for row in range(top, bottom):
        y = (row + 0.5) / GRID_SIZE
        for column in range(left, right):
            x = (column + 0.5) / GRID_SIZE
            if point_in_polygon(x, y, polygon):
                result.add(row * GRID_SIZE + column)
    return result


def _polygon(raw: object) -> tuple[tuple[float, float], ...]:
    if not isinstance(raw, list):
        raise ValueError("polygon must be an array")
    return tuple(tuple(float(value) for value in point) for point in raw)


def lane(record: dict[str, object]) -> str:
    selected = LANES & set(record["scenarioIds"])
    if record["group"] == "BIV_PRIV_SEG":
        return "BIV_SMOKE"
    if len(selected) != 1:
        raise ValueError(f"fixture must have exactly one Priority 2 lane: {record['fixtureId']}")
    return next(iter(selected))


def targets(truth: dict[str, object], selected_lane: str) -> list[Target]:
    protected = [obj for obj in truth["objects"] if obj["protectable"]]
    if selected_lane == "CONFIGURED_ZONE" and protected:
        protected = [max(protected, key=lambda obj: len(cells(_polygon(obj["polygon"]))))]
    return [Target(obj["category"], _polygon(obj["polygon"])) for obj in protected]


def evaluate_frame(expected: list[Target], findings: list[Finding]) -> Counts:
    result = Counts(frames=1, targets=len(expected), findings=len(findings))
    truth_masks = [cells(target.polygon) for target in expected]
    finding_masks = [cells(finding.polygon) for finding in findings]
    result.truth_cells = len(set().union(*truth_masks)) if truth_masks else 0
    result.predicted_cells = len(set().union(*finding_masks)) if finding_masks else 0
    matched_predictions: set[int] = set()
    covered_union: set[int] = set()
    for target_index, target in enumerate(expected):
        candidates = []
        for finding_index, finding in enumerate(findings):
            if finding.category == target.category and finding_index not in matched_predictions:
                overlap = truth_masks[target_index] & finding_masks[finding_index]
                candidates.append((len(overlap), finding_index, overlap))
        overlap_size, selected, overlap = max(candidates, default=(0, -1, set()))
        if overlap_size > 0:
            result.recalled += 1
            matched_predictions.add(selected)
            covered_union.update(overlap)
    result.covered_cells = len(covered_union)
    truth_union = set().union(*truth_masks) if truth_masks else set()
    prediction_union = set().union(*finding_masks) if finding_masks else set()
    result.excessive_cells = len(prediction_union - truth_union)
    result.false_positives = len(findings) - len(matched_predictions)
    return result


def _load_jsonl(path: Path) -> list[dict[str, object]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()
            if line.strip()]


def evaluate_suite(name: str, manifest: Path, findings_path: Path,
                   media_root: Path, truth_root: Path) -> dict[str, object]:
    del media_root  # Media integrity belongs to validate_manifest.py before this evaluator.
    records = _load_jsonl(manifest)
    observations = _load_jsonl(findings_path)
    by_key = {}
    for observation in observations:
        key = (observation.get("fixtureId"), observation.get("frameIndex"))
        if key in by_key:
            raise ValueError(f"duplicate finding observation for {key}")
        raw_findings = observation.get("findings")
        if not isinstance(raw_findings, list):
            raise ValueError(f"findings must be an array for {key}")
        by_key[key] = [Finding(str(item["category"]), _polygon(item["polygon"]))
                       for item in raw_findings]

    grouped: dict[tuple[str, str, str], Counts] = defaultdict(Counts)
    expected_keys = set()
    for record in records:
        selected_lane = lane(record)
        truth_file = truth_root / record["truthPath"]
        for frame in _load_jsonl(truth_file):
            key = (record["fixtureId"], frame["frameIndex"])
            expected_keys.add(key)
            if key not in by_key:
                raise ValueError(f"missing finding observation for {key}")
            expected = targets(frame, selected_lane)
            categories = sorted({item.category for item in expected})
            if not categories:
                raise ValueError(f"truth frame has no evaluation target: {key}")
            if len(categories) != 1:
                raise ValueError(f"truth frame mixes categories: {key}")
            result = evaluate_frame(expected, by_key[key])
            grouped[(str(record["split"]), selected_lane, categories[0])].add(result)
    extras = set(by_key) - expected_keys
    if extras:
        raise ValueError(f"finding observations not present in manifest truth: {len(extras)}")

    rows = []
    for (split, selected_lane, category), counts in sorted(grouped.items()):
        rows.append({"split": split, "lane": selected_lane, "category": category,
                     **counts.record()})
    expected_categories = {row["category"] for row in rows}
    for split in sorted({str(record["split"]) for record in records}):
        actual = {row["category"] for row in rows if row["split"] == split}
        if actual != expected_categories:
            raise ValueError(f"category rows differ across splits: {split}")
    return {"schemaVersion": "1.0.0", "suite": name, "gridSize": GRID_SIZE,
            "rows": rows, "overallAverageIntentionallyOmitted": True}


def markdown(report: dict[str, object]) -> str:
    lines = [f"## {report['suite']}", "",
             "No overall average is emitted; every category remains visible.", "",
             "| Split | Lane | Category | Recall | Localization coverage | Excessive mask | False positives |",
             "|---|---|---|---:|---:|---:|---:|"]
    for row in report["rows"]:
        metric = lambda name: (f"{row[name]['numerator']}/{row[name]['denominator']}"
                               if row[name]["denominator"] else "0/0")
        lines.append(f"| {row['split']} | {row['lane']} | {row['category']} | "
                     f"{metric('recall')} | {metric('localizationCoverage')} | "
                     f"{metric('excessiveMask')} | {metric('falsePositives')} |")
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--suite", required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--findings", type=Path, required=True)
    parser.add_argument("--media-root", type=Path, default=Path("test-fixtures/media"))
    parser.add_argument("--truth-root", type=Path, default=Path("test-fixtures/annotations"))
    parser.add_argument("--json", type=Path, required=True)
    parser.add_argument("--markdown", type=Path, required=True)
    args = parser.parse_args()
    report = evaluate_suite(args.suite, args.manifest, args.findings,
                            args.media_root, args.truth_root)
    args.json.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    args.markdown.write_text(markdown(report), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
