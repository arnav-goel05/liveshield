import importlib.util
import sys
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).resolve().parents[1] / "generate_system_fixtures.py"
SPEC = importlib.util.spec_from_file_location("generate_system_fixtures", MODULE_PATH)
generator = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = generator
SPEC.loader.exec_module(generator)


class SystemFixtureGeneratorTest(unittest.TestCase):
    def test_exact_group_and_split_counts(self):
        fixtures = generator._fixtures()
        self.assertEqual(32, len(fixtures))
        self.assertEqual(12, sum(item.group == "RENDERER" for item in fixtures))
        self.assertEqual(20, sum(item.group == "FAULT_INJECTION" for item in fixtures))
        self.assertEqual(16, sum(item.split == "DEVELOPMENT" for item in fixtures))
        self.assertEqual(16, sum(item.split == "HOLDOUT" for item in fixtures))

    def test_split_isolation_keys_and_pixels_are_deterministic(self):
        fixtures = generator._fixtures()
        self.assertEqual(len(fixtures), len({item.seed for item in fixtures}))
        self.assertEqual(len(fixtures), len({item.motion_id for item in fixtures}))
        fixture = fixtures[0]
        self.assertEqual(generator._paint_frame(fixture, 3),
                         generator._paint_frame(fixture, 3))
        self.assertNotEqual(generator._paint_frame(fixture, 2),
                            generator._paint_frame(fixture, 3))

    def test_every_required_scenario_exists_in_each_split(self):
        fixtures = generator._fixtures()
        renderer_expected = {scenario for group in generator.RENDERER_SCENARIOS for scenario in group}
        fault_expected = set(generator.FAULT_SCENARIOS)
        for split in ("DEVELOPMENT", "HOLDOUT"):
            renderer_actual = {scenario for item in fixtures
                               if item.split == split and item.group == "RENDERER"
                               for scenario in item.scenarios}
            fault_actual = {scenario for item in fixtures
                            if item.split == split and item.group == "FAULT_INJECTION"
                            for scenario in item.scenarios}
            self.assertEqual(renderer_expected, renderer_actual)
            self.assertEqual(fault_expected, fault_actual)


if __name__ == "__main__":
    unittest.main()
