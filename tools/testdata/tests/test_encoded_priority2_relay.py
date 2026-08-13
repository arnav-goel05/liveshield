import importlib.util
from pathlib import Path
import unittest
import numpy as np


MODULE_PATH = Path("tools/testdata/evaluate_encoded_priority2.py")
SPEC = importlib.util.spec_from_file_location("encoded_priority2", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class TwoPhaseRelayProtocolTest(unittest.TestCase):
    def test_excludes_exactly_one_priming_frame(self):
        reference = [np.array([index], dtype=np.uint8) for index in range(MODULE.FRAMES)]
        captured = [np.array([255], dtype=np.uint8)] + reference
        segment, count = MODULE.evaluation_segment(captured, reference)
        self.assertEqual(1, count)
        self.assertEqual(MODULE.FRAMES, len(segment))

    def test_excludes_dynamic_bounded_priming_gop_by_evaluation_signature(self):
        reference = [np.array([index], dtype=np.uint8) for index in range(MODULE.FRAMES)]
        priming = [reference[index % 8] for index in range(23)]
        segment, count = MODULE.evaluation_segment(priming + reference, reference)
        self.assertEqual(23, count)
        self.assertEqual(MODULE.FRAMES, len(segment))

    def test_rejects_capture_missing_priming_frame(self):
        with self.assertRaisesRegex(AssertionError, "priming frame count"):
            reference = [np.array([index], dtype=np.uint8) for index in range(MODULE.FRAMES)]
            MODULE.evaluation_segment(reference, reference)

    def test_rejects_extra_frame_in_capture(self):
        reference = [np.array([index], dtype=np.uint8) for index in range(MODULE.FRAMES)]
        captured = [np.array([255], dtype=np.uint8)] * (MODULE.MAX_PRIMING_FRAMES + 1)
        with self.assertRaisesRegex(AssertionError, "bounded priming"):
            MODULE.evaluation_segment(captured + reference, reference)

    def test_rejects_wrong_evaluation_signature(self):
        reference = [np.array([index], dtype=np.uint8) for index in range(MODULE.FRAMES)]
        captured = [np.array([255], dtype=np.uint8)] + list(reference)
        captured[-1] = np.array([77], dtype=np.uint8)
        with self.assertRaisesRegex(AssertionError, "sanitized signature"):
            MODULE.evaluation_segment(captured, reference)


if __name__ == "__main__":
    unittest.main()
