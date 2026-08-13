#!/usr/bin/env python3
"""Build a validated, DEVELOPMENT-only Priority 2 device staging plan."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


def resolve_fixture_path(root: Path, relative: str) -> Path:
    candidate = root / relative
    if candidate.is_file():
        return candidate
    parts = Path(relative).parts
    if len(parts) == 2 and root.name == parts[0]:
        nested_root_candidate = root / parts[1]
        if nested_root_candidate.is_file():
            return nested_root_candidate
    raise FileNotFoundError(f"missing fixture path under {root}: {relative}")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def development_plan(manifest: Path, media_root: Path, truth_root: Path) -> list[dict]:
    records = []
    for line in manifest.read_text(encoding="utf-8").splitlines():
        record = json.loads(line)
        if record["split"] != "DEVELOPMENT":
            continue
        source = resolve_fixture_path(media_root, record["sourcePath"])
        truth = resolve_fixture_path(truth_root, record["truthPath"])
        if sha256(source) != record["sourceDigest"]:
            raise ValueError(f"source digest mismatch: {record['fixtureId']}")
        records.append({
            "fixtureId": record["fixtureId"],
            "manifestRecord": record,
            "source": str(source.resolve()),
            "truth": str(truth.resolve()),
            "deviceSourceName": Path(record["sourcePath"]).name,
            "deviceTruthName": Path(record["truthPath"]).name,
        })
    records.sort(key=lambda value: value["fixtureId"])
    if len(records) != 13:
        raise ValueError(f"expected 13 DEVELOPMENT fixtures, found {len(records)}")
    if len({value["deviceSourceName"] for value in records}) != 13:
        raise ValueError("duplicate DEVELOPMENT media basename")
    if len({value["deviceTruthName"] for value in records}) != 13:
        raise ValueError("duplicate DEVELOPMENT truth basename")
    return records


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--media-root", type=Path, required=True)
    parser.add_argument("--truth-root", type=Path, required=True)
    parser.add_argument("--json", type=Path, required=True)
    parser.add_argument("--development-manifest", type=Path, required=True)
    args = parser.parse_args()
    plan = development_plan(args.manifest, args.media_root, args.truth_root)
    args.json.parent.mkdir(parents=True, exist_ok=True)
    args.json.write_text(json.dumps(plan, sort_keys=True, separators=(",", ":")) + "\n",
                         encoding="utf-8")
    args.development_manifest.write_text("\n".join(
        json.dumps(value["manifestRecord"], sort_keys=True, separators=(",", ":"))
        for value in plan) + "\n", encoding="utf-8")
    print(f"validated DEVELOPMENT staging plan: fixtures={len(plan)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
