import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).resolve().parents[1] / "priority2_development_stage.py"
SPEC = importlib.util.spec_from_file_location("priority2_development_stage", MODULE_PATH)
stage = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = stage
SPEC.loader.exec_module(stage)


class PriorityTwoDevelopmentStageTest(unittest.TestCase):
    def test_resolves_parent_and_already_nested_roots(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            nested = root / "pii-v1"
            nested.mkdir()
            fixture = nested / "fixture.mp4"
            fixture.write_bytes(b"fixture")
            self.assertEqual(fixture, stage.resolve_fixture_path(root, "pii-v1/fixture.mp4"))
            self.assertEqual(fixture,
                             stage.resolve_fixture_path(nested, "pii-v1/fixture.mp4"))

    def test_missing_file_fails_before_device_staging(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest = root / "manifest.jsonl"
            manifest.write_text(json.dumps({
                "fixtureId": "dev-1", "split": "DEVELOPMENT",
                "sourcePath": "pii-v1/missing.mp4",
                "truthPath": "pii-v1/missing.jsonl",
                "sourceDigest": "0" * 64,
            }) + "\n", encoding="utf-8")
            with self.assertRaises(FileNotFoundError):
                stage.development_plan(manifest, root, root)


if __name__ == "__main__":
    unittest.main()
