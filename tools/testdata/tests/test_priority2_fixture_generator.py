import importlib.util
import hashlib
import sys
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).resolve().parents[1] / "generate_priority2_fixtures.py"
SPEC = importlib.util.spec_from_file_location("generate_priority2_fixtures", MODULE_PATH)
generator = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = generator
SPEC.loader.exec_module(generator)


class PriorityTwoFixtureGeneratorTest(unittest.TestCase):
    def test_exact_categories_lanes_splits_and_disjoint_keys(self):
        fixtures = generator.fixtures()
        self.assertEqual(26, len(fixtures))
        for split in ("DEVELOPMENT", "HOLDOUT"):
            selected = [item for item in fixtures if item.split == split]
            self.assertEqual(13, len(selected))
            self.assertEqual({item[0] for item in generator.CATEGORIES},
                             {item.category_id for item in selected})
        self.assertEqual(10, sum(item.lane == "AUTOMATIC_PATTERN" for item in fixtures))
        self.assertEqual(8, sum(item.lane == "CONFIGURED_WATCHLIST" for item in fixtures))
        self.assertEqual(8, sum(item.lane == "CONFIGURED_ZONE" for item in fixtures))
        for attribute in ("seed", "payload_id", "source_group_id", "fixture_id"):
            self.assertEqual(26, len({getattr(item, attribute) for item in fixtures}))
        development = [item for item in fixtures if item.split == "DEVELOPMENT"]
        holdout = [item for item in fixtures if item.split == "HOLDOUT"]
        for attribute in ("seed", "payload_id", "source_group_id", "fixture_id"):
            self.assertFalse({getattr(item, attribute) for item in development}
                             & {getattr(item, attribute) for item in holdout})

    def test_pixels_truth_and_controls_are_deterministic_and_fictional(self):
        fixture = next(item for item in generator.fixtures()
                       if item.category_id == "email-address")
        self.assertEqual(generator.paint_frame(fixture, 3), generator.paint_frame(fixture, 3))
        self.assertNotEqual(generator.paint_frame(fixture, 2), generator.paint_frame(fixture, 3))
        truth = generator.truth(fixture, 0)
        self.assertTrue(truth["objects"][0]["protectable"])
        self.assertFalse(truth["objects"][-1]["protectable"])
        self.assertEqual("DECOY", truth["objects"][-1]["role"])
        zone = next(item for item in generator.fixtures()
                    if item.lane == "CONFIGURED_ZONE")
        self.assertEqual(3, len(generator.truth(zone, 0)["objects"]))
        all_rendered = " ".join(generator._text(item) for item in generator.fixtures())
        self.assertIn("EXAMPLE.TEST", all_rendered)
        self.assertIn("TEST CODE", all_rendered)

    def test_machine_readable_fixture_requires_offline_standard_symbol(self):
        fixture = next(item for item in generator.fixtures()
                       if item.category_id == "machine-readable-code")
        with self.assertRaises(RuntimeError):
            generator.paint_frame(fixture, 0)
        symbol = [[row == column for column in range(192)] for row in range(192)]
        rendered = generator.paint_frame(fixture, 0, symbol)
        self.assertEqual(rendered, generator.paint_frame(fixture, 0, symbol))
        self.assertIn("ZXing-core-3.5.4", generator.SYMBOL_GENERATOR_VERSION)

    def test_every_declared_legible_payload_fits_inside_the_frame(self):
        for fixture in generator.fixtures():
            for frame in range(generator.FRAME_COUNT):
                left, top, right, bottom, rotated, scale = generator._geometry(fixture, frame)
                self.assertGreaterEqual(left, 0)
                self.assertGreaterEqual(top, 0)
                self.assertLessEqual(right, generator.WIDTH)
                self.assertLessEqual(bottom, generator.HEIGHT)
                if fixture.category_id != "machine-readable-code":
                    text_width, text_height = generator._text_dimensions(
                        generator._text(fixture))
                    self.assertEqual(generator.FONT_SIZE, scale)
                    self.assertGreaterEqual(right - left, text_width + 20)
                    self.assertGreaterEqual(bottom - top, text_height + 20)

    def test_pinned_noto_sans_font_hash_license_and_natural_glyph_metrics(self):
        repo = MODULE_PATH.parents[2]
        font = repo / generator.FONT_RELATIVE_PATH
        license_file = repo / generator.FONT_LICENSE_RELATIVE_PATH
        self.assertEqual(generator.FONT_BYTES, font.stat().st_size)
        self.assertEqual(generator.FONT_SHA256, hashlib.sha256(font.read_bytes()).hexdigest())
        self.assertEqual(generator.FONT_LICENSE_SHA256,
                         hashlib.sha256(license_file.read_bytes()).hexdigest())
        self.assertIn("SIL OPEN FONT LICENSE Version 1.1",
                      license_file.read_text(encoding="utf-8"))
        loaded = generator._verified_font(repo)
        width, height = generator._text_dimensions("DEV1@EXAMPLE.TEST")
        self.assertEqual(generator.FONT_SIZE, loaded.size)
        self.assertGreaterEqual(width, 250)
        self.assertGreaterEqual(height, 25)
        self.assertEqual(2, generator.FONT_STROKE_WIDTH)
        self.assertEqual("11.3.0", generator.PILLOW_VERSION)
        self.assertEqual("2.13.3", generator.FREETYPE_VERSION)

    def test_manifest_provenance_is_exactly_pinned_to_natural_font_corpus_v2(self):
        source = generator._synthetic_source()
        self.assertEqual("2.0.0", source["generatorVersion"])
        self.assertEqual({
            "artifact": "NotoSans-wdth-wght-32px-stroke2",
            "sha256": generator.FONT_SHA256,
            "license": "OFL-1.1",
            "upstreamCommit": generator.FONT_UPSTREAM_COMMIT,
            "rasterizer": "Pillow-11.3.0-FreeType-2.13.3",
        }, source["font"])
        self.assertTrue(source["fictionalPayloadVerified"])

    def test_actual_transformed_text_ink_is_contained_by_truth_region(self):
        for fixture in generator.fixtures():
            if fixture.category_id == "machine-readable-code":
                continue
            for frame in range(generator.FRAME_COUNT):
                left, top, right, bottom, rotated, _ = generator._geometry(fixture, frame)
                ink = generator._text_ink_bounds(
                    generator._text(fixture), left + 8, top + 8, rotated)
                self.assertGreaterEqual(ink[0], left, fixture.fixture_id)
                self.assertGreaterEqual(ink[1], top, fixture.fixture_id)
                self.assertLessEqual(ink[2], right, fixture.fixture_id)
                self.assertLessEqual(ink[3], bottom, fixture.fixture_id)


if __name__ == "__main__":
    unittest.main()
