#!/usr/bin/env python3
"""Fail closed unless the T119 APK contains the frozen PP-OCRv3 baseline."""

from __future__ import annotations

import argparse
import hashlib
import json
import zipfile
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Artifact:
    path: str
    size: int
    sha256: str


REQUIRED = (
    Artifact(
        "assets/models/paddleocr/en_PP-OCRv3_det_slim_infer.nb",
        925_070,
        "9d3c629313d47d203385216a756610eb00ee2496a06ff724cc34904deda70f22",
    ),
    Artifact(
        "assets/models/paddleocr/en_PP-OCRv3_rec_slim_infer.nb",
        3_313_574,
        "053b3a99fc88233c5ea5fda10141cf2f9c81e93ca2b74ce3dcf8208d3e80185d",
    ),
    Artifact(
        "assets/models/paddleocr/en_dict.txt",
        190,
        "5662df9d2d03f0e8ca0d3b0649d6acbab904b6a14b3d3521463c71c37c668ce3",
    ),
    Artifact(
        "lib/arm64-v8a/libpaddle_lite_jni.so",
        3_176_664,
        "43ad4f58221570575e58d6af77653f476f7af485ee970ea924f20c0579cc2e01",
    ),
)

# Neither failed recognition candidate may survive under its old name or a renamed entry.
FORBIDDEN_PATHS = frozenset(
    {
        "assets/models/opencv-crnn/text_recognition_CRNN_CH_2023feb_fp16.onnx",
        "assets/models/opencv-crnn/PROVENANCE.properties",
        "assets/models/paddleocr/PP-OCRv5_mobile_rec.nb",
        "assets/models/paddleocr/ppocr_keys_ocrv5.txt",
    }
)
FORBIDDEN_HASHES = frozenset(
    {
        # General PP-OCRv5 recognizer.
        "9d073b3ee01deee358bf929dd8952d4d355c9545f4a93d8070605581b4c21c0c",
        # General PP-OCRv5 dictionary.
        "17665d27ed39f0deb82007859992d626d3105d0ee4578c120b7c72138dc04d05",
        # Paddle Lite 2.14-rc ARM64 JNI.
        "9427c18464fad232af5a14430d065d2ddc8ad868d3ce1fce3338fa4ae9a5edfd",
        # OpenCV Zoo CRNN-CH FP16 candidate.
        "cfef028889b3a21771e687d501ac38ccab6d37d199e94f244d60cc21f743526b",
    }
)


def digest(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def verify(apk: Path) -> dict[str, object]:
    if not apk.is_file():
        raise ValueError(f"APK does not exist: {apk}")
    try:
        with zipfile.ZipFile(apk) as archive:
            names = archive.namelist()
            if len(names) != len(set(names)):
                raise ValueError("APK contains duplicate ZIP entry names")
            forbidden_paths = sorted(FORBIDDEN_PATHS.intersection(names))
            if forbidden_paths:
                raise ValueError(
                    "failed OCR candidate path is packaged: " + ", ".join(forbidden_paths)
                )

            verified = []
            for artifact in REQUIRED:
                try:
                    payload = archive.read(artifact.path)
                except KeyError as error:
                    raise ValueError(f"required artifact is missing: {artifact.path}") from error
                actual_hash = digest(payload)
                if len(payload) != artifact.size or actual_hash != artifact.sha256:
                    raise ValueError(
                        f"required artifact mismatch: {artifact.path} "
                        f"size={len(payload)} sha256={actual_hash}"
                    )
                verified.append(
                    {"path": artifact.path, "size": len(payload), "sha256": actual_hash}
                )

            for name in names:
                if name.endswith("/"):
                    continue
                actual_hash = digest(archive.read(name))
                if actual_hash in FORBIDDEN_HASHES:
                    raise ValueError(
                        f"failed OCR candidate hash is packaged: {name} sha256={actual_hash}"
                    )
    except zipfile.BadZipFile as error:
        raise ValueError(f"not a readable APK ZIP: {apk}") from error

    return {"apk": str(apk.resolve()), "artifacts": verified}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True, type=Path)
    arguments = parser.parse_args()
    try:
        result = verify(arguments.apk)
    except ValueError as error:
        parser.error(str(error))
    print(json.dumps(result, sort_keys=True, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
