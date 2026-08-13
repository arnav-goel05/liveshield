import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).resolve().parents[1] / "evaluate_priority2.py"
SPEC = importlib.util.spec_from_file_location("evaluate_priority2", MODULE_PATH)
evaluator = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = evaluator
SPEC.loader.exec_module(evaluator)


RECT = ((0.1, 0.1), (0.4, 0.1), (0.4, 0.4), (0.1, 0.4))


class PriorityTwoEvaluatorTest(unittest.TestCase):
    def test_exact_metrics_and_false_positive_denominators(self):
        targets = [evaluator.Target("EMAIL", RECT)]
        findings = [evaluator.Finding("EMAIL", RECT),
                    evaluator.Finding("PHONE", ((0.6, 0.6), (0.8, 0.6),
                                                 (0.8, 0.8), (0.6, 0.8)))]
        result = evaluator.evaluate_frame(targets, findings).record()
        self.assertEqual({"numerator": 1, "denominator": 1, "value": 1.0},
                         result["recall"])
        self.assertEqual(1.0, result["localizationCoverage"]["value"])
        self.assertEqual(1, result["falsePositives"]["numerator"])
        self.assertEqual(2, result["falsePositives"]["denominator"])
        self.assertGreater(result["excessiveMask"]["numerator"], 0)

    def test_missing_frame_and_missing_category_rows_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "truth").mkdir()
            manifest = root / "pii-v1.jsonl"
            records = [self._manifest("dev", "DEVELOPMENT", "EMAIL"),
                       self._manifest("hold", "HOLDOUT", "PHONE")]
            manifest.write_text("\n".join(json.dumps(item) for item in records) + "\n")
            for record in records:
                (root / "truth" / record["truthPath"]).write_text(
                    json.dumps(self._truth(record["fixtureId"], record["category"])) + "\n")
            findings = root / "findings.jsonl"
            findings.write_text(json.dumps({"fixtureId": "dev", "frameIndex": 0,
                                            "findings": []}) + "\n")
            with self.assertRaisesRegex(ValueError, "missing finding observation"):
                evaluator.evaluate_suite("mock", manifest, findings, root, root / "truth")

            findings.write_text("\n".join(json.dumps({"fixtureId": fixture_id,
                                                       "frameIndex": 0, "findings": []})
                                                   for fixture_id in ("dev", "hold")) + "\n")
            with self.assertRaisesRegex(ValueError, "category rows differ across splits"):
                evaluator.evaluate_suite("mock", manifest, findings, root, root / "truth")

    def test_markdown_has_no_overall_average(self):
        report = {"suite": "mock", "rows": [{
            "split": "DEVELOPMENT", "lane": "AUTOMATIC_PATTERN", "category": "EMAIL",
            "recall": evaluator.ratio(1, 2), "localizationCoverage": evaluator.ratio(3, 4),
            "excessiveMask": evaluator.ratio(5, 6), "falsePositives": evaluator.ratio(0, 1),
        }]}
        output = evaluator.markdown(report)
        self.assertIn("every category remains visible", output)
        self.assertNotIn("Overall |", output)

    def test_zone_evaluates_largest_containing_region(self):
        truth = {"objects": [
            {"category": "DOCUMENT", "protectable": True,
             "polygon": [[0.1, 0.1], [0.9, 0.1], [0.9, 0.9], [0.1, 0.9]]},
            {"category": "DOCUMENT", "protectable": True,
             "polygon": [[0.2, 0.2], [0.4, 0.2], [0.4, 0.3], [0.2, 0.3]]},
            {"category": "DOCUMENT", "protectable": False,
             "polygon": [[0.0, 0.0], [0.1, 0.0], [0.1, 0.1], [0.0, 0.1]]},
        ]}
        selected = evaluator.targets(truth, "CONFIGURED_ZONE")
        self.assertEqual(1, len(selected))
        self.assertGreater(len(evaluator.cells(selected[0].polygon)), 40_000)

    @staticmethod
    def _manifest(fixture_id, split, category):
        return {"fixtureId": fixture_id, "split": split, "group": "PRIORITY_2",
                "scenarioIds": ["AUTOMATIC_PATTERN"], "truthPath": f"{fixture_id}.jsonl",
                "category": category}

    @staticmethod
    def _truth(fixture_id, category):
        return {"fixtureId": fixture_id, "frameIndex": 0,
                "objects": [{"category": category, "polygon": RECT, "protectable": True}]}


if __name__ == "__main__":
    unittest.main()
