from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
TOOLS_ROOT = REPOSITORY_ROOT / "tools" / "testdata"
sys.path.insert(0, str(TOOLS_ROOT))
MODULE_PATH = TOOLS_ROOT / "prepare_public_manifest.py"
SPEC = importlib.util.spec_from_file_location("prepare_public_manifest", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
PUBLIC = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = PUBLIC
SPEC.loader.exec_module(PUBLIC)


class PublicManifestPreparationTest(unittest.TestCase):
    def test_jpeg_dimensions_reads_header_without_decoding(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            image = Path(directory) / "header.jpg"
            # SOI, baseline SOF segment (precision 8, height 480, width 640), EOI.
            image.write_bytes(
                b"\xff\xd8\xff\xc0\x00\x08\x08\x01\xe0\x02\x80\x01\xff\xd9"
            )
            self.assertEqual((640, 480), PUBLIC.jpeg_dimensions(image))

    def test_normalized_box_clamps_to_image_bounds(self) -> None:
        polygon = PUBLIC.normalized_box(-10, 20, 80, 100, 100, 100)
        self.assertEqual([[0.0, 0.2], [0.7, 0.2], [0.7, 1.0], [0.0, 1.0]], polygon)

    def test_biv_uses_a_distinct_evaluation_only_truth_category(self) -> None:
        record = PUBLIC.truth_record(
            "biv-smoke-001",
            [{
                "objectId": "sensitive-001",
                "category": "BIV_PRIVATE_OBJECT",
                "role": "SENSITIVE",
                "polygon": [[0, 0], [1, 0], [1, 1], [0, 1]],
                "visibility": 1.0,
                "protectable": True,
                "legible": False,
            }],
        )
        self.assertEqual("BIV_PRIVATE_OBJECT", record["objects"][0]["category"])


if __name__ == "__main__":
    unittest.main()
