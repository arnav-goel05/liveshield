from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
MODULE_PATH = REPOSITORY_ROOT / "tools" / "testdata" / "select_wider_subset.py"
SPEC = importlib.util.spec_from_file_location("select_wider_subset", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
WIDER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = WIDER
SPEC.loader.exec_module(WIDER)


def annotation_lines(filename: str, attributes: tuple[int, int, int, int, int]) -> list[str]:
    width, height, blur, illumination, occlusion = attributes
    # x y w h blur expression illumination invalid occlusion pose
    return [filename, "1", f"0 0 {width} {height} {blur} 0 {illumination} 0 {occlusion} 0"]


class PublicWiderSelectionTest(unittest.TestCase):
    def create_fixture(self, root: Path, reverse: bool = False) -> tuple[Path, Path]:
        annotations: list[list[str]] = []
        categories = {
            "small": (20, 100, 0, 0, 0),
            "heavy_blur": (100, 100, 2, 0, 0),
            "heavy_occlusion": (100, 100, 0, 0, 2),
            "difficult_capture": (100, 100, 0, 1, 0),
            "baseline": (100, 100, 0, 0, 0),
        }
        images_root = root / "images"
        for category, attributes in categories.items():
            for index in range(3):
                filename = f"event/{category}_{index}.jpg"
                image = images_root / filename
                image.parent.mkdir(parents=True, exist_ok=True)
                image.write_bytes(f"{category}-{index}".encode("ascii"))
                annotations.append(annotation_lines(filename, attributes))

        # This box looks difficult but is marked invalid and must therefore be baseline.
        invalid_filename = "event/invalid_attributes.jpg"
        invalid_image = images_root / invalid_filename
        invalid_image.write_bytes(b"invalid-attributes")
        annotations.append(
            [invalid_filename, "1", "0 0 5 5 2 0 1 1 2 1"]
        )
        if reverse:
            annotations.reverse()
        annotation_path = root / "wider_face_val_bbx_gt.txt"
        annotation_path.write_text(
            "\n".join(line for record in annotations for line in record) + "\n",
            encoding="utf-8",
        )
        return annotation_path, images_root

    def test_selection_is_deterministic_unique_and_records_all_labels(self) -> None:
        with tempfile.TemporaryDirectory() as first_directory, tempfile.TemporaryDirectory() as second_directory:
            first_annotations, first_images = self.create_fixture(Path(first_directory))
            second_annotations, second_images = self.create_fixture(Path(second_directory), reverse=True)

            first_selection = WIDER.select_annotations(
                WIDER.parse_annotations(first_annotations), per_slice=2
            )
            second_selection = WIDER.select_annotations(
                WIDER.parse_annotations(second_annotations), per_slice=2
            )
            first_names = [item.filename for item, _, _ in first_selection]
            second_names = [item.filename for item, _, _ in second_selection]

            self.assertEqual(first_names, second_names)
            self.assertEqual(10, len(first_names))
            self.assertEqual(10, len(set(first_names)))
            records = list(WIDER.build_records(first_selection, first_images, WIDER.SELECTION_SEED))
            self.assertEqual(10, len(records))
            self.assertTrue(all(len(record["image_sha256"]) == 64 for record in records))
            self.assertEqual("CC BY-NC-ND 4.0", records[0]["license"])

            # Reading and hashing must not alter source bytes.
            before = (second_images / second_names[0]).read_bytes()
            list(WIDER.build_records(second_selection, second_images, WIDER.SELECTION_SEED))
            self.assertEqual(before, (second_images / second_names[0]).read_bytes())

    def test_cli_output_is_stable_jsonl(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            annotations, images = self.create_fixture(root)
            output = root / "selected.jsonl"
            selections = WIDER.select_annotations(WIDER.parse_annotations(annotations), per_slice=2)
            WIDER.write_jsonl(
                WIDER.build_records(selections, images, WIDER.SELECTION_SEED), output
            )
            records = [json.loads(line) for line in output.read_text(encoding="utf-8").splitlines()]
            self.assertEqual(10, len(records))
            self.assertEqual(set(WIDER.SLICE_ORDER), {record["primary_slice"] for record in records})

    def test_official_zero_face_placeholder_is_consumed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            annotations = Path(directory) / "annotations.txt"
            annotations.write_text(
                "event/empty.jpg\n0\n0 0 0 0 0 0 0 0 0 0\n"
                "event/face.jpg\n1\n0 0 100 100 0 0 0 0 0 0\n",
                encoding="utf-8",
            )
            parsed = WIDER.parse_annotations(annotations)
            self.assertEqual(["event/empty.jpg", "event/face.jpg"], [item.filename for item in parsed])
            self.assertEqual((), parsed[0].boxes)

    def test_misleading_valid_zero_area_record_is_not_selectable(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            annotations = Path(directory) / "annotations.txt"
            annotations.write_text(
                "event/zero-width.jpg\n1\n0 0 0 10 0 0 0 0 0 0\n"
                "event/zero-height.jpg\n1\n0 0 10 0 0 0 0 0 0 0\n"
                "event/all-zero.jpg\n1\n0 0 0 0 0 0 0 0 0 0\n"
                "event/small.jpg\n1\n0 0 10 10 0 0 0 0 0 0\n"
                "event/blur.jpg\n1\n0 0 100 100 2 0 0 0 0 0\n"
                "event/occlusion.jpg\n1\n0 0 100 100 0 0 0 0 2 0\n"
                "event/difficult.jpg\n1\n0 0 100 100 0 0 1 0 0 0\n"
                "event/baseline.jpg\n1\n0 0 100 100 0 0 0 0 0 0\n",
                encoding="utf-8",
            )

            parsed = WIDER.parse_annotations(annotations)
            selected = WIDER.select_annotations(parsed, per_slice=1)

            selected_names = {item.filename for item, _, _ in selected}
            self.assertEqual(5, len(selected_names))
            self.assertFalse(any("zero" in name for name in selected_names))
            self.assertTrue(WIDER.is_valid_box(parsed[-1].boxes[0]))
            self.assertFalse(WIDER.is_valid_box(parsed[0].boxes[0]))
            self.assertFalse(WIDER.is_valid_box(parsed[1].boxes[0]))
            self.assertFalse(WIDER.is_valid_box(parsed[2].boxes[0]))


if __name__ == "__main__":
    unittest.main()
