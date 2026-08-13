import copy
import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).resolve().parents[1] / "validate_manifest.py"
SPEC = importlib.util.spec_from_file_location("validate_manifest", MODULE_PATH)
validate_manifest = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(validate_manifest)


def write_jsonl(path, records):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(json.dumps(record, sort_keys=True) + "\n" for record in records), encoding="utf-8")


def truth_record(fixture_id="fixture-dev-001"):
    return {
        "schemaVersion": "1.0.0",
        "fixtureId": fixture_id,
        "frameIndex": 0,
        "sourceTimestampNs": 1,
        "transform": {
            "rotationDegrees": 0,
            "mirrored": False,
            "crop": [0, 0, 1, 1],
            "sensorToBuffer": [1, 0, 0, 0, 1, 0, 0, 0, 1],
        },
        "objects": [{
            "objectId": "object-001",
            "category": "FACE",
            "role": "UNKNOWN",
            "polygon": [[0.1, 0.1], [0.4, 0.1], [0.4, 0.4], [0.1, 0.4]],
            "visibility": 1.0,
            "protectable": True,
            "legible": False,
        }],
        "expectedState": "REGIONAL_PROTECTION",
        "requiredAction": "PROTECT_REGIONS",
    }


def synthetic_fixture(media_bytes=b"silent fixture", fixture_id="fixture-dev-001", split="DEVELOPMENT"):
    return {
        "schemaVersion": "1.0.0",
        "corpusVersion": "1.0.0",
        "fixtureId": fixture_id,
        "group": "RENDERER",
        "sourceKind": "SYNTHETIC",
        "split": split,
        "scenarioIds": ["moving-mask"],
        "sourcePath": f"media/{fixture_id}.bin",
        "sourceDigest": hashlib.sha256(media_bytes).hexdigest(),
        "provenanceRef": "generator:liveshield-v1",
        "truthPath": f"truth/{fixture_id}.jsonl",
        "mediaStreams": ["VIDEO"],
        "leakageKeys": {
            "sourceGroupId": f"clip:{fixture_id}",
            "actorIds": [],
            "payloadIds": [f"payload:{fixture_id}"],
            "generatorSeeds": [f"seed:{fixture_id}"],
            "roomMotionIds": [f"room-motion:{fixture_id}"],
        },
        "syntheticSource": {
            "generatorId": "sentinel-generator",
            "generatorVersion": "1.0.0",
            "fictionalPayloadVerified": True,
        },
    }


class ManifestValidatorTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)

    def tearDown(self):
        self.temp_dir.cleanup()

    def write_fixture_files(self, fixture, media_bytes=b"silent fixture", truth=None):
        media = self.root / fixture["sourcePath"]
        media.parent.mkdir(parents=True, exist_ok=True)
        media.write_bytes(media_bytes)
        truth_path = self.root / fixture["truthPath"]
        write_jsonl(truth_path, [truth or truth_record(fixture["fixtureId"])])

    def validate(self, fixtures, **kwargs):
        manifest = self.root / "manifest.jsonl"
        write_jsonl(manifest, fixtures)
        return validate_manifest.validate_manifest(
            manifest, self.root, self.root, profile=None, **kwargs
        )

    def assert_rejected(self, fixtures, message, **kwargs):
        with self.assertRaises(validate_manifest.ManifestValidationError) as caught:
            self.validate(fixtures, **kwargs)
        self.assertIn(message, str(caught.exception))

    def test_valid_fixture_verifies_digest_truth_and_expected_count(self):
        fixture = synthetic_fixture()
        self.write_fixture_files(fixture)
        records = self.validate([fixture], expected_count=1)
        self.assertEqual("fixture-dev-001", records[0]["fixtureId"])

    def test_rejects_source_digest_mismatch(self):
        fixture = synthetic_fixture()
        self.write_fixture_files(fixture, media_bytes=b"tampered")
        self.assert_rejected([fixture], "sourceDigest: does not match source file")

    def test_rejects_forbidden_sensitive_field_at_any_depth(self):
        fixture = synthetic_fixture()
        fixture["syntheticSource"]["participantName"] = "must-not-be-recorded"
        self.write_fixture_files(fixture)
        self.assert_rejected([fixture], "participantName: forbidden sensitive field")

    def test_rejects_recognized_text_inside_frame_truth(self):
        fixture = synthetic_fixture()
        truth = truth_record()
        truth["objects"][0]["recognizedText"] = "never retain this"
        self.write_fixture_files(fixture, truth=truth)
        self.assert_rejected([fixture], "recognizedText: forbidden sensitive field")

    def test_rejects_audio_stream_in_created_fixture(self):
        fixture = synthetic_fixture()
        fixture["mediaStreams"] = ["VIDEO", "AUDIO"]
        self.write_fixture_files(fixture)
        self.assert_rejected([fixture], "must contain VIDEO only")

    def test_biv_private_object_category_is_rejected_outside_public_biv_fixture(self):
        fixture = synthetic_fixture()
        truth = truth_record()
        truth["objects"][0]["category"] = "BIV_PRIVATE_OBJECT"
        self.write_fixture_files(fixture, truth=truth)
        self.assert_rejected(
            [fixture],
            "BIV_PRIVATE_OBJECT is restricted to licensed-public BIV_PRIV_SEG fixtures",
        )

        consented_errors = validate_manifest.validate_truth_record(
            truth,
            truth["fixtureId"],
            "truth",
            fixture_group="CONSENTED_FACE",
            source_kind="CONSENTED_CAPTURE",
        )
        self.assertTrue(any("BIV_PRIVATE_OBJECT is restricted" in error for error in consented_errors))

        public_biv_errors = validate_manifest.validate_truth_record(
            truth,
            truth["fixtureId"],
            "truth",
            fixture_group="BIV_PRIV_SEG",
            source_kind="LICENSED_PUBLIC",
        )
        self.assertFalse(any("BIV_PRIVATE_OBJECT is restricted" in error for error in public_biv_errors))

    def test_rejects_inconsistent_or_missing_expected_outcome(self):
        fixture = synthetic_fixture()
        truth = truth_record()
        truth["requiredAction"] = "FULL_SHIELD"
        self.write_fixture_files(fixture, truth=truth)
        self.assert_rejected([fixture], "REGIONAL_PROTECTION requires PROTECT_REGIONS")

    def test_rejects_duplicate_fixture_ids_and_wrong_count(self):
        first = synthetic_fixture()
        second = copy.deepcopy(first)
        self.write_fixture_files(first)
        self.assert_rejected([first, second], "duplicate fixture ID", expected_count=3)

    def test_rejects_actor_payload_seed_source_and_room_motion_split_leakage(self):
        development = synthetic_fixture(fixture_id="fixture-dev-001", split="DEVELOPMENT")
        holdout = synthetic_fixture(fixture_id="fixture-holdout-001", split="HOLDOUT")
        shared = {
            "sourceGroupId": "clip:shared-001",
            "actorIds": ["actor:shared-001"],
            "payloadIds": ["payload:shared-001"],
            "generatorSeeds": ["seed:shared-001"],
            "roomMotionIds": ["room-motion:shared-001"],
        }
        development["leakageKeys"] = copy.deepcopy(shared)
        holdout["leakageKeys"] = copy.deepcopy(shared)
        self.write_fixture_files(development)
        self.write_fixture_files(holdout)
        with self.assertRaises(validate_manifest.ManifestValidationError) as caught:
            self.validate([development, holdout])
        error = str(caught.exception)
        for key in ("sourceGroupId", "sourceDigests", "actorIds", "payloadIds", "generatorSeeds", "roomMotionIds"):
            self.assertIn(f"{key} leakage", error)

    def test_profile_requires_exact_group_inventory(self):
        fixture = synthetic_fixture()
        self.write_fixture_files(fixture)
        manifest = self.root / "manifest.jsonl"
        write_jsonl(manifest, [fixture])
        with self.assertRaises(validate_manifest.ManifestValidationError) as caught:
            validate_manifest.validate_manifest(
                manifest, self.root, self.root, profile="system-v1"
            )
        self.assertIn("expected group counts", str(caught.exception))

    def test_public_fixture_requires_complete_provenance_and_byte_length(self):
        media_bytes = b"public image bytes"
        fixture = synthetic_fixture(media_bytes=media_bytes)
        fixture.update({
            "fixtureId": "wider-regression-001",
            "group": "WIDER_FACE",
            "sourceKind": "LICENSED_PUBLIC",
            "split": "REGRESSION",
            "sourcePath": "media/wider-regression-001.bin",
            "truthPath": "truth/wider-regression-001.jsonl",
            "provenanceRef": "dataset:wider-face-validation",
            "leakageKeys": {
                "sourceGroupId": "wider-image-001", "actorIds": [], "payloadIds": [],
                "generatorSeeds": [], "roomMotionIds": [],
            },
        })
        fixture.pop("syntheticSource")
        fixture["publicDataset"] = {
            "sourceUrl": "https://example.invalid/wider.zip",
            "datasetVersion": "validation-v1",
            "license": "CC-BY-NC-ND",
            "allowedUsage": "local non-commercial evaluation",
            "attribution": "WIDER FACE project",
            "retrievedAt": "2026-08-13",
            "byteLength": len(media_bytes) + 1,
            "selectionSeed": "liveshield-wider-v1",
            "sourceItemId": "0--Parade/example.jpg",
        }
        self.write_fixture_files(
            fixture, media_bytes=media_bytes, truth=truth_record(fixture["fixtureId"])
        )
        self.assert_rejected([fixture], "byteLength: does not match source file")

    def test_consented_capture_requires_all_controls_and_opaque_refs(self):
        fixture = synthetic_fixture(fixture_id="consented-face-001")
        fixture.update({
            "group": "CONSENTED_FACE",
            "sourceKind": "CONSENTED_CAPTURE",
            "provenanceRef": "auth:opaque-0001",
            "deviceContext": {
                "deviceClass": "physical-mid-tier",
                "lens": "FRONT",
                "width": 1280,
                "height": 720,
                "frameRate": 30,
            },
            "captureAuthorization": {
                "authorizationRef": "auth:opaque-0001",
                "consentRecorded": True,
                "fictionalPayloadVerified": True,
                "encryptedStorageRef": "store:encrypted-0001",
                "authorizedAccessRef": "acl:evaluators-0001",
                "deletionDeadline": "2099-12-31",
                "deletionAuditStatus": "PENDING",
                "capturePhase": "DEVICE_VALIDATION",
            },
        })
        fixture.pop("syntheticSource")
        fixture["leakageKeys"]["actorIds"] = ["actor:opaque-0001"]
        self.write_fixture_files(fixture, truth=truth_record(fixture["fixtureId"]))
        self.assertEqual(1, len(self.validate([fixture])))

        unsafe = copy.deepcopy(fixture)
        unsafe["captureAuthorization"].pop("authorizedAccessRef")
        self.assert_rejected([unsafe], "missing required field authorizedAccessRef")

    def test_consented_capture_rejects_unapproved_phase_and_unverified_payload(self):
        fixture = synthetic_fixture(fixture_id="consented-face-002")
        fixture.update({
            "group": "CONSENTED_FACE",
            "sourceKind": "CONSENTED_CAPTURE",
            "provenanceRef": "auth:opaque-0002",
            "deviceContext": {"deviceClass": "physical-mid-tier", "lens": "FRONT", "width": 1280, "height": 720, "frameRate": 30},
            "captureAuthorization": {
                "authorizationRef": "auth:opaque-0002",
                "consentRecorded": True,
                "fictionalPayloadVerified": False,
                "encryptedStorageRef": "store:encrypted-0002",
                "authorizedAccessRef": "acl:evaluators-0002",
                "deletionDeadline": "2099-12-31",
                "deletionAuditStatus": "PENDING",
                "capturePhase": "DEVELOPMENT",
            },
        })
        fixture.pop("syntheticSource")
        self.write_fixture_files(fixture, truth=truth_record(fixture["fixtureId"]))
        with self.assertRaises(validate_manifest.ManifestValidationError) as caught:
            self.validate([fixture])
        self.assertIn("fictionalPayloadVerified: must be true", str(caught.exception))
        self.assertIn("capturePhase: must be DEVICE_VALIDATION", str(caught.exception))

    def test_consented_capture_rejects_nonopaque_reference(self):
        fixture = synthetic_fixture(fixture_id="consented-face-003")
        fixture.update({
            "group": "CONSENTED_FACE",
            "sourceKind": "CONSENTED_CAPTURE",
            "provenanceRef": "Jane Doe signed consent",
            "deviceContext": {"deviceClass": "physical-mid-tier", "lens": "FRONT", "width": 1280, "height": 720, "frameRate": 30},
            "captureAuthorization": {
                "authorizationRef": "Jane Doe signed consent",
                "consentRecorded": True,
                "fictionalPayloadVerified": True,
                "encryptedStorageRef": "store:encrypted-0003",
                "authorizedAccessRef": "acl:evaluators-0003",
                "deletionDeadline": "2099-12-31",
                "deletionAuditStatus": "PENDING",
                "capturePhase": "DEVICE_VALIDATION",
            },
        })
        fixture.pop("syntheticSource")
        self.write_fixture_files(fixture, truth=truth_record(fixture["fixtureId"]))
        self.assert_rejected([fixture], "authorizationRef: must be an opaque reference")


if __name__ == "__main__":
    unittest.main()
