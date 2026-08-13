import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
SCHEMA_DIR = ROOT / "test-fixtures" / "schema"


class SchemaContractTest(unittest.TestCase):
    def test_schemas_are_draft_2020_12_json_documents(self):
        for name in ("fixture-manifest.schema.json", "frame-truth.schema.json"):
            schema = json.loads((SCHEMA_DIR / name).read_text(encoding="utf-8"))
            self.assertEqual("https://json-schema.org/draft/2020-12/schema", schema["$schema"])
            self.assertFalse(schema["additionalProperties"])

    def test_manifest_requires_integrity_provenance_truth_and_silent_stream_fields(self):
        schema = json.loads((SCHEMA_DIR / "fixture-manifest.schema.json").read_text(encoding="utf-8"))
        required = set(schema["required"])
        self.assertTrue({"sourceDigest", "provenanceRef", "truthPath", "mediaStreams", "leakageKeys"} <= required)
        self.assertEqual(["VIDEO"], schema["properties"]["mediaStreams"]["const"])

    def test_consented_capture_conditional_requires_governance_controls(self):
        schema = json.loads((SCHEMA_DIR / "fixture-manifest.schema.json").read_text(encoding="utf-8"))
        capture = schema["properties"]["captureAuthorization"]
        required = set(capture["required"])
        self.assertTrue({
            "authorizationRef", "consentRecorded", "fictionalPayloadVerified",
            "encryptedStorageRef", "authorizedAccessRef", "deletionDeadline",
            "deletionAuditStatus", "capturePhase",
        } <= required)
        self.assertEqual(True, capture["properties"]["consentRecorded"]["const"])
        self.assertEqual(True, capture["properties"]["fictionalPayloadVerified"]["const"])
        self.assertEqual("DEVICE_VALIDATION", capture["properties"]["capturePhase"]["const"])
        consent_rule = schema["allOf"][2]
        self.assertEqual("CONSENTED_CAPTURE", consent_rule["if"]["properties"]["sourceKind"]["const"])
        self.assertTrue({"captureAuthorization", "deviceContext"} <= set(consent_rule["then"]["required"]))

    def test_truth_schema_requires_explicit_output_outcome(self):
        schema = json.loads((SCHEMA_DIR / "frame-truth.schema.json").read_text(encoding="utf-8"))
        self.assertTrue({"expectedState", "requiredAction"} <= set(schema["required"]))
        self.assertEqual(
            {"REGIONAL_PROTECTION", "FULL_SHIELD", "STOPPED"},
            set(schema["properties"]["expectedState"]["enum"]),
        )


if __name__ == "__main__":
    unittest.main()
