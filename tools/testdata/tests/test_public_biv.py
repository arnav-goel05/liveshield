from __future__ import annotations

import hashlib
import importlib.util
import json
import tempfile
import unittest
import zipfile
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
MODULE_PATH = REPOSITORY_ROOT / "tools" / "testdata" / "prepare_biv_support.py"
PUBLIC_MANIFEST = REPOSITORY_ROOT / "test-fixtures" / "manifests" / "public-v1.jsonl"
ANNOTATION_ROOT = REPOSITORY_ROOT / "test-fixtures" / "annotations"
SPEC = importlib.util.spec_from_file_location("prepare_biv_support", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
BIV = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BIV)


class PublicBivPreparationTest(unittest.TestCase):
    EXPECTED_ITEMS = (
        "biv-smoke-001:10.jpeg:38b27e24aa9efcc7b2020d42d9df9361e026edddcb6b6fc708bf9051cb33a781",
        "biv-smoke-002:1126.jpeg:858aa9e8e473d27488d4f810a8e1fef1d3874ddf352280ee2f8f5e2afaac4cc7",
        "biv-smoke-003:1166.jpeg:4c50aa5542316cb8a70cc2556502bae634c9e1bbb7473b47d3fc6df8bfb9cf39",
        "biv-smoke-004:130.jpeg:eb7b0f8fe4eacbdca78fefcb3e9fec6e5113c2db27ac17087a393769071fbc1e",
        "biv-smoke-005:136.jpeg:32f23358fb20361937b468be17353abf292b6aaadf90cd0e2df6750efc38524c",
        "biv-smoke-006:210.jpeg:d283aa9f0fe91ebca0ba45ed21948f307226a8a80904b08e840e09eebcd6163e",
        "biv-smoke-007:225.jpeg:6aea18e1cb1255e651c3c87d2849fde661625ac2e7418e56e25382753b48af5a",
        "biv-smoke-008:498.jpeg:f810f3b34b9f8bf97c8b945768c3c420b07c1fd1dfe54cb8714e2b9064328535",
        "biv-smoke-009:53.jpeg:2bec5d3d7563dc3b6c0e2f14227b45d4d5a042e9c238b8870ecf867e83f117d7",
        "biv-smoke-010:545.jpeg:b0839677bbbcd1c44356d158defc7f8bf4902767d6e654804164c8785706712f",
        "biv-smoke-011:662.jpeg:888c19bcf2fc4e96a26a1bee982ef25b258dc30b9815011cee6a8f3532ba485f",
        "biv-smoke-012:668.jpeg:1c50ce24b547a6f5f71eb83ab5c6354fe3215f0837f0562b32e4eb1aa014cb03",
        "biv-smoke-013:674.jpeg:35ec9a9e9a01082024fb6894b50961cb69e32ee68ae79087c988a61d833f1e06",
        "biv-smoke-014:730.jpeg:14b74d5f76b317013d221a41e591009d9c8c7e8f72f8c570deb5e599f7cdbeda",
        "biv-smoke-015:848.jpeg:25d26872381e907980e331fd827de3cc61b458534ca7f82b47b33905ff0db024",
        "biv-smoke-016:938.jpeg:b46dcabeceef3ad33e4c3cc5decd1d64d8f781fe7eec3bd8faab11b9a6ac4db7",
    )
    EXPECTED_CLASSES = {
        "biv-bills-or-receipt", "biv-local-newspaper", "biv-bank-statement",
        "biv-transcript", "biv-tattoo-sleeve", "biv-credit-or-debit-card",
        "biv-business-card", "biv-pregnancy-test",
        "biv-mortgage-or-investment-report", "biv-pregnancy-test-box",
        "biv-empty-pill-bottle", "biv-condom-with-plastic-bag",
        "biv-doctors-prescription", "biv-condom-box",
        "biv-medical-record-document", "biv-letters-with-address",
    }

    def create_fixture(self, root: Path) -> tuple[Path, Path]:
        images_archive = root / "support_images.zip"
        with zipfile.ZipFile(images_archive, "w", compression=zipfile.ZIP_STORED) as archive:
            archive.writestr("support/first.jpg", b"first-image")
            archive.writestr("support/second.jpg", b"second-image")

        annotations = {
            "images": [
                {"id": 10, "file_name": "first.jpg", "width": 100, "height": 80},
                {"id": 20, "file_name": "second.jpg", "width": 120, "height": 90},
            ],
            "categories": [
                {"id": 1, "name": "credit card"},
                {"id": 2, "name": "letter"},
            ],
            "annotations": [
                {
                    "id": 100,
                    "image_id": 10,
                    "category_id": 1,
                    "bbox": [1, 2, 30, 40],
                    "area": 1200,
                    "segmentation": [[1, 2, 31, 2, 31, 42, 1, 42]],
                },
                {
                    "id": 200,
                    "image_id": 20,
                    "category_id": 2,
                    "bbox": [5, 6, 20, 10],
                    "area": 200,
                    "segmentation": [[5, 6, 25, 6, 25, 16, 5, 16]],
                },
            ],
        }
        annotation_path = root / "support_set.json"
        annotation_path.write_text(json.dumps(annotations), encoding="utf-8")
        return images_archive, annotation_path

    def test_hashes_conversion_and_attribution(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            images_archive, annotations = self.create_fixture(root)
            images_digest = BIV.file_sha256(images_archive)
            annotations_digest = BIV.file_sha256(annotations)

            BIV.verify_file(images_archive, images_archive.stat().st_size, images_digest)
            BIV.verify_file(annotations, annotations.stat().st_size, annotations_digest)
            with zipfile.ZipFile(images_archive) as archive:
                records = BIV.convert_annotations(json.loads(annotations.read_text()), archive, 2)

            output = root / "biv.jsonl"
            attribution = root / "ATTRIBUTION.md"
            BIV.write_jsonl(records, output)
            BIV.write_attribution(attribution, images_digest, annotations_digest)

            self.assertEqual(2, len(records))
            self.assertEqual({"credit card", "letter"}, {
                record["regions"][0]["category_name"] for record in records
            })
            self.assertTrue(all(len(record["image_sha256"]) == 64 for record in records))
            text = attribution.read_text(encoding="utf-8")
            self.assertIn("Creative Commons Attribution 4.0", text)
            self.assertIn("Yu-Yun Tseng", text)
            self.assertIn(images_digest, text)

    def test_digest_mismatch_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            images_archive, _ = self.create_fixture(Path(directory))
            with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
                BIV.verify_file(images_archive, images_archive.stat().st_size, "0" * 64)

    def test_unsafe_zip_member_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            archive_path = Path(directory) / "unsafe.zip"
            with zipfile.ZipFile(archive_path, "w") as archive:
                archive.writestr("../escape.jpg", b"unsafe")
            with zipfile.ZipFile(archive_path) as archive:
                with self.assertRaisesRegex(ValueError, "unsafe ZIP member"):
                    BIV.safe_archive_members(archive)

    def test_committed_smoke_manifest_locks_all_sixteen_items_and_truth(self) -> None:
        records = [
            json.loads(line)
            for line in PUBLIC_MANIFEST.read_text(encoding="utf-8").splitlines()
            if line.strip()
        ]
        biv = [record for record in records if record["group"] == "BIV_PRIV_SEG"]
        actual_items = tuple(
            f'{record["fixtureId"]}:{record["publicDataset"]["sourceItemId"]}:'
            f'{record["sourceDigest"]}'
            for record in biv
        )
        self.assertEqual(self.EXPECTED_ITEMS, actual_items)
        self.assertEqual(self.EXPECTED_CLASSES, {
            record["scenarioIds"][0] for record in biv
        })
        for record in biv:
            dataset = record["publicDataset"]
            self.assertEqual("CC BY 4.0", dataset["license"])
            self.assertEqual(
                "local unmodified detector localization smoke evaluation",
                dataset["allowedUsage"],
            )
            self.assertIn("Yu-Yun Tseng", dataset["attribution"])
            truth_path = ANNOTATION_ROOT / record["truthPath"]
            truth_lines = truth_path.read_text(encoding="utf-8").splitlines()
            self.assertEqual(1, len(truth_lines))
            truth = json.loads(truth_lines[0])
            self.assertEqual(record["fixtureId"], truth["fixtureId"])
            self.assertEqual("BIV_PRIVATE_OBJECT", truth["objects"][0]["category"])
            self.assertNotIn("name", truth["objects"][0])


if __name__ == "__main__":
    unittest.main()
