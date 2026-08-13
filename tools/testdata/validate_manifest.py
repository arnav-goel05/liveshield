#!/usr/bin/env python3
"""Validate LiveShield fixture JSONL without third-party Python packages."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Iterable


SHA256_RE = re.compile(r"^[a-f0-9]{64}$")
SEMVER_RE = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")
ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{2,127}$")
OPAQUE_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$")

FORBIDDEN_KEYS = {
    "address",
    "authorizationdocument",
    "consentdocument",
    "consentrecord",
    "credential",
    "credentials",
    "email",
    "extractedfacecrop",
    "facecrop",
    "facecrops",
    "faceembedding",
    "faceembeddings",
    "name",
    "participantname",
    "password",
    "phone",
    "rawtext",
    "recognizedtext",
    "secret",
    "streamkey",
    "text",
    "token",
}

REQUIRED_FIXTURE_KEYS = {
    "schemaVersion", "corpusVersion", "fixtureId", "group", "sourceKind", "split",
    "scenarioIds", "sourcePath", "sourceDigest", "provenanceRef", "truthPath",
    "mediaStreams", "leakageKeys",
}
ALLOWED_FIXTURE_KEYS = REQUIRED_FIXTURE_KEYS | {
    "deviceContext", "publicDataset", "syntheticSource", "captureAuthorization"
}
REQUIRED_TRUTH_KEYS = {
    "schemaVersion", "fixtureId", "frameIndex", "sourceTimestampNs", "transform", "objects",
    "expectedState", "requiredAction",
}
ALLOWED_TRUTH_KEYS = REQUIRED_TRUTH_KEYS | {"generatorSeedOrPayloadId"}

GROUP_RULES = {
    "WIDER_FACE": ("LICENSED_PUBLIC", {"REGRESSION"}),
    "BIV_PRIV_SEG": ("LICENSED_PUBLIC", {"SMOKE"}),
    "RENDERER": ("SYNTHETIC", {"DEVELOPMENT", "HOLDOUT"}),
    "CONSENTED_FACE": ("CONSENTED_CAPTURE", {"DEVELOPMENT", "HOLDOUT"}),
    "PRIORITY_2": ("SYNTHETIC", {"DEVELOPMENT", "HOLDOUT"}),
    "FAULT_INJECTION": ("SYNTHETIC", {"DEVELOPMENT", "HOLDOUT"}),
}

PROFILE_COUNTS = {
    "public-v1": {"WIDER_FACE": 200, "BIV_PRIV_SEG": 16},
    "system-v1": {"RENDERER": 12, "FAULT_INJECTION": 20},
    "face-v1": {"CONSENTED_FACE": 12},
    "pii-v1": {"PRIORITY_2": 26},
    "priority2-v1": {"PRIORITY_2": 26},
    "full-v1": {
        "WIDER_FACE": 200, "BIV_PRIV_SEG": 16, "RENDERER": 12,
        "CONSENTED_FACE": 12, "PRIORITY_2": 26, "FAULT_INJECTION": 20,
    },
}


class ManifestValidationError(ValueError):
    """Raised when one or more safety or integrity checks fail."""

    def __init__(self, errors: Iterable[str]):
        self.errors = list(errors)
        super().__init__("\n".join(self.errors))


def _normalized_key(key: str) -> str:
    return re.sub(r"[^a-z0-9]", "", key.lower())


def _find_forbidden_keys(value: Any, location: str) -> list[str]:
    errors: list[str] = []
    if isinstance(value, dict):
        for key, child in value.items():
            child_location = f"{location}.{key}"
            if _normalized_key(key) in FORBIDDEN_KEYS:
                errors.append(f"{child_location}: forbidden sensitive field")
            errors.extend(_find_forbidden_keys(child, child_location))
    elif isinstance(value, list):
        for index, child in enumerate(value):
            errors.extend(_find_forbidden_keys(child, f"{location}[{index}]"))
    return errors


def _safe_path(value: Any, location: str, errors: list[str]) -> Path | None:
    if not isinstance(value, str) or not value or "\x00" in value:
        errors.append(f"{location}: must be a non-empty path")
        return None
    path = Path(value)
    if path.is_absolute() or ".." in path.parts:
        errors.append(f"{location}: must be a safe relative path")
        return None
    return path


def _require_keys(record: dict[str, Any], required: set[str], allowed: set[str], location: str) -> list[str]:
    errors = [f"{location}: missing required field {key}" for key in sorted(required - record.keys())]
    errors.extend(f"{location}: unknown field {key}" for key in sorted(record.keys() - allowed))
    return errors


def _id_list(value: Any, location: str, errors: list[str], *, allow_empty: bool = True) -> list[str]:
    if not isinstance(value, list) or (not allow_empty and not value):
        errors.append(f"{location}: must be {'a non-empty' if not allow_empty else 'an'} array")
        return []
    if len(value) != len({json.dumps(item, sort_keys=True) for item in value}):
        errors.append(f"{location}: values must be unique")
    result: list[str] = []
    for index, item in enumerate(value):
        normalized = str(item)
        if not ID_RE.fullmatch(normalized):
            errors.append(f"{location}[{index}]: must be a stable non-sensitive identifier")
        else:
            result.append(normalized)
    return result


def _parse_date(value: Any, location: str, errors: list[str]) -> dt.date | None:
    if not isinstance(value, str):
        errors.append(f"{location}: must be an ISO-8601 date")
        return None
    try:
        return dt.date.fromisoformat(value)
    except ValueError:
        errors.append(f"{location}: must be a valid ISO-8601 date")
        return None


def _load_jsonl(path: Path) -> list[tuple[int, dict[str, Any]]]:
    records: list[tuple[int, dict[str, Any]]] = []
    with path.open("r", encoding="utf-8") as stream:
        for line_number, raw_line in enumerate(stream, 1):
            if not raw_line.strip():
                continue
            try:
                value = json.loads(raw_line)
            except json.JSONDecodeError as exc:
                raise ManifestValidationError([f"{path}:{line_number}: invalid JSON: {exc.msg}"]) from exc
            if not isinstance(value, dict):
                raise ManifestValidationError([f"{path}:{line_number}: each JSONL record must be an object"])
            records.append((line_number, value))
    if not records:
        raise ManifestValidationError([f"{path}: manifest/truth file must not be empty"])
    return records


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _resolve_under(root: Path, relative: Path, location: str, errors: list[str]) -> Path | None:
    resolved_root = root.resolve()
    resolved = (resolved_root / relative).resolve()
    if not resolved.is_relative_to(resolved_root):
        errors.append(f"{location}: resolved path escapes configured root")
        return None
    return resolved


def _validate_public(record: dict[str, Any], location: str, errors: list[str]) -> None:
    public = record.get("publicDataset")
    required = {"sourceUrl", "datasetVersion", "license", "allowedUsage", "attribution", "retrievedAt", "byteLength", "selectionSeed", "sourceItemId"}
    if not isinstance(public, dict):
        errors.append(f"{location}.publicDataset: required for LICENSED_PUBLIC")
        return
    errors.extend(_require_keys(public, required, required, f"{location}.publicDataset"))
    source_url = public.get("sourceUrl")
    if not isinstance(source_url, str) or not re.match(r"^https?://", source_url):
        errors.append(f"{location}.publicDataset.sourceUrl: must be an HTTP(S) URL")
    for key in ("datasetVersion", "license", "allowedUsage", "attribution", "sourceItemId"):
        if not isinstance(public.get(key), str) or not public[key].strip():
            errors.append(f"{location}.publicDataset.{key}: must be non-empty")
    if not isinstance(public.get("byteLength"), int) or public["byteLength"] <= 0:
        errors.append(f"{location}.publicDataset.byteLength: must be positive")
    if not ID_RE.fullmatch(str(public.get("selectionSeed", ""))):
        errors.append(f"{location}.publicDataset.selectionSeed: invalid stable identifier")
    _parse_date(public.get("retrievedAt"), f"{location}.publicDataset.retrievedAt", errors)


def _validate_synthetic(record: dict[str, Any], location: str, errors: list[str]) -> None:
    source = record.get("syntheticSource")
    required = {"generatorId", "generatorVersion", "fictionalPayloadVerified"}
    if not isinstance(source, dict):
        errors.append(f"{location}.syntheticSource: required for SYNTHETIC")
        return
    allowed = required | {"symbolGenerator", "font"}
    errors.extend(_require_keys(source, required, allowed, f"{location}.syntheticSource"))
    if not ID_RE.fullmatch(str(source.get("generatorId", ""))):
        errors.append(f"{location}.syntheticSource.generatorId: invalid stable identifier")
    if not SEMVER_RE.fullmatch(str(source.get("generatorVersion", ""))):
        errors.append(f"{location}.syntheticSource.generatorVersion: must be semantic version")
    if source.get("fictionalPayloadVerified") is not True:
        errors.append(f"{location}.syntheticSource.fictionalPayloadVerified: must be true")
    symbol_generator = source.get("symbolGenerator")
    if symbol_generator is not None and not ID_RE.fullmatch(str(symbol_generator)):
        errors.append(f"{location}.syntheticSource.symbolGenerator: invalid stable identifier")
    font = source.get("font")
    if font is not None:
        font_required = {"artifact", "sha256", "license", "upstreamCommit", "rasterizer"}
        if not isinstance(font, dict):
            errors.append(f"{location}.syntheticSource.font: must be an object")
        else:
            errors.extend(_require_keys(
                font, font_required, font_required, f"{location}.syntheticSource.font"))
            if not SHA256_RE.fullmatch(str(font.get("sha256", ""))):
                errors.append(f"{location}.syntheticSource.font.sha256: invalid SHA-256")
            if not re.fullmatch(r"[a-f0-9]{40}", str(font.get("upstreamCommit", ""))):
                errors.append(
                    f"{location}.syntheticSource.font.upstreamCommit: invalid commit")
            for key in ("artifact", "license", "rasterizer"):
                if not ID_RE.fullmatch(str(font.get(key, ""))):
                    errors.append(
                        f"{location}.syntheticSource.font.{key}: invalid identifier")


def _validate_capture(record: dict[str, Any], location: str, errors: list[str]) -> None:
    auth = record.get("captureAuthorization")
    required = {"authorizationRef", "consentRecorded", "fictionalPayloadVerified", "encryptedStorageRef", "authorizedAccessRef", "deletionDeadline", "deletionAuditStatus", "capturePhase"}
    if not isinstance(auth, dict):
        errors.append(f"{location}.captureAuthorization: required for CONSENTED_CAPTURE")
        return
    errors.extend(_require_keys(auth, required, required, f"{location}.captureAuthorization"))
    for key in ("authorizationRef", "encryptedStorageRef", "authorizedAccessRef"):
        if not OPAQUE_RE.fullmatch(str(auth.get(key, ""))):
            errors.append(f"{location}.captureAuthorization.{key}: must be an opaque reference")
    if auth.get("consentRecorded") is not True:
        errors.append(f"{location}.captureAuthorization.consentRecorded: must be true")
    if auth.get("fictionalPayloadVerified") is not True:
        errors.append(f"{location}.captureAuthorization.fictionalPayloadVerified: must be true")
    deadline = _parse_date(auth.get("deletionDeadline"), f"{location}.captureAuthorization.deletionDeadline", errors)
    audit = auth.get("deletionAuditStatus")
    if audit not in {"PENDING", "DELETED", "WITHDRAWN"}:
        errors.append(f"{location}.captureAuthorization.deletionAuditStatus: invalid status")
    if audit == "PENDING" and deadline is not None and deadline < dt.date.today():
        errors.append(f"{location}.captureAuthorization: pending deletion deadline has passed")
    if auth.get("capturePhase") != "DEVICE_VALIDATION":
        errors.append(f"{location}.captureAuthorization.capturePhase: must be DEVICE_VALIDATION")
    if record.get("provenanceRef") != auth.get("authorizationRef"):
        errors.append(f"{location}.provenanceRef: must equal the opaque authorizationRef")
    device = record.get("deviceContext")
    required_device = {"deviceClass", "lens", "width", "height", "frameRate"}
    if not isinstance(device, dict):
        errors.append(f"{location}.deviceContext: required for CONSENTED_CAPTURE")
    else:
        errors.extend(_require_keys(device, required_device, required_device, f"{location}.deviceContext"))
        if not ID_RE.fullmatch(str(device.get("deviceClass", ""))):
            errors.append(f"{location}.deviceContext.deviceClass: invalid stable identifier")
        if device.get("lens") not in {"FRONT", "REAR", "EXTERNAL"}:
            errors.append(f"{location}.deviceContext.lens: invalid lens")
        for key in ("width", "height"):
            if not isinstance(device.get(key), int) or device[key] <= 0:
                errors.append(f"{location}.deviceContext.{key}: must be positive integer")
        if not isinstance(device.get("frameRate"), (int, float)) or device["frameRate"] <= 0:
            errors.append(f"{location}.deviceContext.frameRate: must be positive")


def validate_fixture_record(record: dict[str, Any], location: str = "fixture") -> list[str]:
    errors = _require_keys(record, REQUIRED_FIXTURE_KEYS, ALLOWED_FIXTURE_KEYS, location)
    errors.extend(_find_forbidden_keys(record, location))
    if record.get("schemaVersion") != "1.0.0":
        errors.append(f"{location}.schemaVersion: unsupported schema version")
    if not SEMVER_RE.fullmatch(str(record.get("corpusVersion", ""))):
        errors.append(f"{location}.corpusVersion: must be semantic version")
    if not ID_RE.fullmatch(str(record.get("fixtureId", ""))):
        errors.append(f"{location}.fixtureId: invalid stable identifier")
    scenarios = _id_list(record.get("scenarioIds"), f"{location}.scenarioIds", errors, allow_empty=False)
    if not scenarios:
        pass
    _safe_path(record.get("sourcePath"), f"{location}.sourcePath", errors)
    _safe_path(record.get("truthPath"), f"{location}.truthPath", errors)
    if not SHA256_RE.fullmatch(str(record.get("sourceDigest", ""))):
        errors.append(f"{location}.sourceDigest: must be lowercase SHA-256")
    if not isinstance(record.get("provenanceRef"), str) or not record["provenanceRef"].strip():
        errors.append(f"{location}.provenanceRef: must be non-empty")
    if record.get("mediaStreams") != ["VIDEO"]:
        errors.append(f"{location}.mediaStreams: silent created/evaluation fixtures must contain VIDEO only")

    group = record.get("group")
    source_kind = record.get("sourceKind")
    split = record.get("split")
    if group not in GROUP_RULES:
        errors.append(f"{location}.group: unsupported group")
    else:
        expected_source, allowed_splits = GROUP_RULES[group]
        if source_kind != expected_source:
            errors.append(f"{location}.sourceKind: {group} requires {expected_source}")
        if split not in allowed_splits:
            errors.append(f"{location}.split: {group} requires one of {sorted(allowed_splits)}")

    leakage = record.get("leakageKeys")
    leakage_fields = {"sourceGroupId", "actorIds", "payloadIds", "generatorSeeds", "roomMotionIds"}
    if not isinstance(leakage, dict):
        errors.append(f"{location}.leakageKeys: must be an object")
    else:
        errors.extend(_require_keys(leakage, leakage_fields, leakage_fields, f"{location}.leakageKeys"))
        if not ID_RE.fullmatch(str(leakage.get("sourceGroupId", ""))):
            errors.append(f"{location}.leakageKeys.sourceGroupId: invalid stable identifier")
        for key in ("actorIds", "payloadIds", "generatorSeeds", "roomMotionIds"):
            _id_list(leakage.get(key), f"{location}.leakageKeys.{key}", errors)

    conditional = {"LICENSED_PUBLIC": "publicDataset", "SYNTHETIC": "syntheticSource", "CONSENTED_CAPTURE": "captureAuthorization"}
    allowed_conditional = conditional.get(source_kind)
    for key in ("publicDataset", "syntheticSource", "captureAuthorization"):
        if key != allowed_conditional and key in record:
            errors.append(f"{location}.{key}: not permitted for {source_kind}")
    if source_kind == "LICENSED_PUBLIC":
        _validate_public(record, location, errors)
    elif source_kind == "SYNTHETIC":
        _validate_synthetic(record, location, errors)
    elif source_kind == "CONSENTED_CAPTURE":
        _validate_capture(record, location, errors)
    else:
        errors.append(f"{location}.sourceKind: unsupported source kind")
    return errors


def _number(value: Any) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def validate_truth_record(
    record: dict[str, Any],
    fixture_id: str,
    location: str,
    *,
    fixture_group: str | None = None,
    source_kind: str | None = None,
) -> list[str]:
    errors = _require_keys(record, REQUIRED_TRUTH_KEYS, ALLOWED_TRUTH_KEYS, location)
    errors.extend(_find_forbidden_keys(record, location))
    if record.get("schemaVersion") != "1.0.0":
        errors.append(f"{location}.schemaVersion: unsupported schema version")
    if record.get("fixtureId") != fixture_id:
        errors.append(f"{location}.fixtureId: must match manifest fixtureId")
    if not isinstance(record.get("frameIndex"), int) or record["frameIndex"] < 0:
        errors.append(f"{location}.frameIndex: must be a non-negative integer")
    if not isinstance(record.get("sourceTimestampNs"), int) or record["sourceTimestampNs"] < 0:
        errors.append(f"{location}.sourceTimestampNs: must be a non-negative integer")

    transform = record.get("transform")
    transform_keys = {"rotationDegrees", "mirrored", "crop", "sensorToBuffer"}
    if not isinstance(transform, dict):
        errors.append(f"{location}.transform: must be an object")
    else:
        errors.extend(_require_keys(transform, transform_keys, transform_keys, f"{location}.transform"))
        if transform.get("rotationDegrees") not in {0, 90, 180, 270}:
            errors.append(f"{location}.transform.rotationDegrees: invalid rotation")
        if not isinstance(transform.get("mirrored"), bool):
            errors.append(f"{location}.transform.mirrored: must be boolean")
        crop = transform.get("crop")
        if not isinstance(crop, list) or len(crop) != 4 or any(not _number(v) or not 0 <= v <= 1 for v in crop):
            errors.append(f"{location}.transform.crop: must contain four normalized numbers")
        matrix = transform.get("sensorToBuffer")
        if not isinstance(matrix, list) or len(matrix) != 9 or any(not _number(v) for v in matrix):
            errors.append(f"{location}.transform.sensorToBuffer: must contain nine numbers")

    objects = record.get("objects")
    if not isinstance(objects, list):
        errors.append(f"{location}.objects: must be an array")
        objects = []
    object_ids: set[str] = set()
    for index, obj in enumerate(objects):
        obj_location = f"{location}.objects[{index}]"
        object_keys = {"objectId", "category", "role", "polygon", "visibility", "protectable", "legible"}
        if not isinstance(obj, dict):
            errors.append(f"{obj_location}: must be an object")
            continue
        errors.extend(_require_keys(obj, object_keys, object_keys, obj_location))
        object_id = obj.get("objectId")
        if not ID_RE.fullmatch(str(object_id or "")):
            errors.append(f"{obj_location}.objectId: invalid stable identifier")
        elif object_id in object_ids:
            errors.append(f"{obj_location}.objectId: duplicate within frame")
        else:
            object_ids.add(object_id)
        category = obj.get("category")
        if category not in {"FACE", "MACHINE_READABLE_CODE", "EMAIL", "PHONE", "PAYMENT_CARD", "VERIFICATION_CODE", "PERSON_NAME", "ADDRESS", "EMPLOYER", "SCHOOL", "DOCUMENT", "BADGE", "PARCEL_LABEL", "DEVICE_SCREEN", "BIV_PRIVATE_OBJECT"}:
            errors.append(f"{obj_location}.category: unsupported category")
        elif category == "BIV_PRIVATE_OBJECT" and not (
            fixture_group == "BIV_PRIV_SEG" and source_kind == "LICENSED_PUBLIC"
        ):
            errors.append(
                f"{obj_location}.category: BIV_PRIVATE_OBJECT is restricted to licensed-public "
                "BIV_PRIV_SEG fixtures"
            )
        if obj.get("role") not in {"HOST", "UNKNOWN", "SENSITIVE", "DECOY", "NOT_APPLICABLE"}:
            errors.append(f"{obj_location}.role: unsupported role")
        polygon = obj.get("polygon")
        if not isinstance(polygon, list) or len(polygon) < 3 or any(not isinstance(point, list) or len(point) != 2 or any(not _number(v) or not 0 <= v <= 1 for v in point) for point in polygon):
            errors.append(f"{obj_location}.polygon: requires at least three normalized points")
        if not _number(obj.get("visibility")) or not 0 <= obj["visibility"] <= 1:
            errors.append(f"{obj_location}.visibility: must be between 0 and 1")
        for key in ("protectable", "legible"):
            if not isinstance(obj.get(key), bool):
                errors.append(f"{obj_location}.{key}: must be boolean")

    state = record.get("expectedState")
    action = record.get("requiredAction")
    expected_action = {"REGIONAL_PROTECTION": "PROTECT_REGIONS", "FULL_SHIELD": "FULL_SHIELD", "STOPPED": "STOP_OUTPUT"}
    if state not in expected_action:
        errors.append(f"{location}.expectedState: unsupported expected outcome")
    elif action != expected_action[state]:
        errors.append(f"{location}.requiredAction: {state} requires {expected_action[state]}")
    if state == "REGIONAL_PROTECTION" and not any(obj.get("protectable") and obj.get("role") not in {"HOST", "DECOY"} for obj in objects if isinstance(obj, dict)):
        errors.append(f"{location}: regional protection requires a protectable non-host object")
    payload_id = record.get("generatorSeedOrPayloadId")
    if payload_id is not None and not ID_RE.fullmatch(str(payload_id)):
        errors.append(f"{location}.generatorSeedOrPayloadId: invalid non-sensitive identifier")
    return errors


def _validate_truth_file(
    path: Path,
    fixture_id: str,
    *,
    fixture_group: str | None = None,
    source_kind: str | None = None,
) -> list[str]:
    try:
        records = _load_jsonl(path)
    except (OSError, ManifestValidationError) as exc:
        return [f"{path}: cannot load truth: {exc}"]
    errors: list[str] = []
    frame_indexes: set[int] = set()
    previous_timestamp = -1
    for line_number, record in records:
        location = f"{path}:{line_number}"
        errors.extend(
            validate_truth_record(
                record,
                fixture_id,
                location,
                fixture_group=fixture_group,
                source_kind=source_kind,
            )
        )
        frame_index = record.get("frameIndex")
        if isinstance(frame_index, int):
            if frame_index in frame_indexes:
                errors.append(f"{location}.frameIndex: duplicate frame index")
            frame_indexes.add(frame_index)
        timestamp = record.get("sourceTimestampNs")
        if isinstance(timestamp, int):
            if timestamp <= previous_timestamp:
                errors.append(f"{location}.sourceTimestampNs: timestamps must increase strictly")
            previous_timestamp = timestamp
    return errors


def _profile_for(path: Path, requested: str | None) -> str | None:
    if requested and requested != "auto":
        return requested
    stem = path.stem.lower()
    return next((name for name in PROFILE_COUNTS if stem == name), None)


def validate_manifest(
    manifest_path: Path,
    media_root: Path,
    truth_root: Path,
    *,
    profile: str | None = "auto",
    expected_count: int | None = None,
) -> list[dict[str, Any]]:
    records_with_lines = _load_jsonl(manifest_path)
    errors: list[str] = []
    seen_ids: set[str] = set()
    split_keys: dict[str, dict[str, set[str]]] = defaultdict(lambda: defaultdict(set))
    group_counts: Counter[str] = Counter()
    records: list[dict[str, Any]] = []

    for line_number, record in records_with_lines:
        location = f"{manifest_path}:{line_number}"
        errors.extend(validate_fixture_record(record, location))
        fixture_id = record.get("fixtureId")
        if isinstance(fixture_id, str):
            if fixture_id in seen_ids:
                errors.append(f"{location}.fixtureId: duplicate fixture ID")
            seen_ids.add(fixture_id)
        group_counts[str(record.get("group"))] += 1

        source_path = _safe_path(record.get("sourcePath"), f"{location}.sourcePath", errors)
        if source_path is not None:
            resolved = _resolve_under(media_root, source_path, f"{location}.sourcePath", errors)
            if resolved is None:
                pass
            elif not resolved.is_file():
                errors.append(f"{location}.sourcePath: source file does not exist under media root")
            else:
                digest = _sha256_file(resolved)
                if digest != record.get("sourceDigest"):
                    errors.append(f"{location}.sourceDigest: does not match source file")
                public = record.get("publicDataset")
                if isinstance(public, dict) and public.get("byteLength") != resolved.stat().st_size:
                    errors.append(f"{location}.publicDataset.byteLength: does not match source file")

        truth_path = _safe_path(record.get("truthPath"), f"{location}.truthPath", errors)
        if truth_path is not None and isinstance(fixture_id, str):
            resolved_truth = _resolve_under(truth_root, truth_path, f"{location}.truthPath", errors)
            if resolved_truth is not None:
                errors.extend(
                    _validate_truth_file(
                        resolved_truth,
                        fixture_id,
                        fixture_group=record.get("group"),
                        source_kind=record.get("sourceKind"),
                    )
                )

        split = record.get("split")
        leakage = record.get("leakageKeys")
        if split in {"DEVELOPMENT", "HOLDOUT"} and isinstance(leakage, dict):
            leak_values = dict(leakage)
            leak_values["sourceDigests"] = [record.get("sourceDigest")]
            for key in ("sourceGroupId", "sourceDigests", "actorIds", "payloadIds", "generatorSeeds", "roomMotionIds"):
                raw_values = leak_values.get(key, [])
                values = [raw_values] if key == "sourceGroupId" else raw_values
                if isinstance(values, list):
                    split_keys[key][split].update(str(value) for value in values)
        records.append(record)

    if expected_count is not None and len(records) != expected_count:
        errors.append(f"{manifest_path}: expected {expected_count} fixtures, found {len(records)}")
    active_profile = _profile_for(manifest_path, profile)
    if active_profile:
        expected_groups = PROFILE_COUNTS.get(active_profile)
        if expected_groups is None:
            errors.append(f"{manifest_path}: unknown count profile {active_profile}")
        elif dict(group_counts) != expected_groups:
            errors.append(f"{manifest_path}: profile {active_profile} expected group counts {expected_groups}, found {dict(group_counts)}")

    for key, by_split in split_keys.items():
        overlap = by_split["DEVELOPMENT"] & by_split["HOLDOUT"]
        if overlap:
            errors.append(f"{manifest_path}: development/holdout {key} leakage: {sorted(overlap)}")

    if errors:
        raise ManifestValidationError(errors)
    return records


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path, help="Fixture manifest JSONL")
    parser.add_argument("--media-root", type=Path, default=Path.cwd(), help="Root for sourcePath entries")
    parser.add_argument("--truth-root", type=Path, default=Path.cwd(), help="Root for truthPath entries")
    parser.add_argument("--profile", choices=["auto", *PROFILE_COUNTS], default="auto", help="Exact initial-corpus count profile")
    parser.add_argument("--expected-count", type=int, help="Optional exact fixture count")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _build_parser().parse_args(argv)
    try:
        records = validate_manifest(
            args.manifest,
            args.media_root,
            args.truth_root,
            profile=args.profile,
            expected_count=args.expected_count,
        )
    except (OSError, ManifestValidationError) as exc:
        print(f"manifest validation failed:\n{exc}", file=sys.stderr)
        return 1
    print(f"validated {len(records)} fixture(s): {args.manifest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
