import importlib.util
import os
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


MODULE_PATH = Path(__file__).resolve().parents[1] / "verify_priority2_development_apk.py"
SPEC = importlib.util.spec_from_file_location("verify_priority2_development_apk", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class PriorityTwoDevelopmentApkTest(unittest.TestCase):
    def write_apk(self, entries):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        apk = Path(directory.name) / "vision-debug-androidTest.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            for name, payload in entries.items():
                archive.writestr(name, payload)
        return apk

    def configure_small_required(self):
        original = MODULE.REQUIRED
        self.addCleanup(setattr, MODULE, "REQUIRED", original)
        entries = {
            artifact.path: f"test-artifact-{index}".encode("ascii")
            for index, artifact in enumerate(original)
        }
        MODULE.REQUIRED = tuple(
            MODULE.Artifact(path, len(payload), MODULE.digest(payload))
            for path, payload in entries.items()
        )
        return entries

    def test_accepts_only_exact_required_artifacts(self):
        result = MODULE.verify(self.write_apk(self.configure_small_required()))
        self.assertEqual(5, len(result["artifacts"]))

    def test_rejects_stale_runtime_at_required_path(self):
        entries = self.configure_small_required()
        runtime = next(
            artifact for artifact in MODULE.REQUIRED
            if artifact.path.endswith("libpaddle_lite_jni.so")
        )
        entries[runtime.path] = b"stale-paddle-runtime"
        with self.assertRaisesRegex(ValueError, "required artifact mismatch"):
            MODULE.verify(self.write_apk(entries))

    def test_rejects_candidate_one_path(self):
        entries = self.configure_small_required()
        entries[next(iter(MODULE.FORBIDDEN_PATHS))] = b"old candidate"
        with self.assertRaisesRegex(ValueError, "failed OCR candidate path"):
            MODULE.verify(self.write_apk(entries))

    def test_rejects_renamed_candidate_one_hash(self):
        entries = self.configure_small_required()
        original_hashes = MODULE.FORBIDDEN_HASHES
        self.addCleanup(setattr, MODULE, "FORBIDDEN_HASHES", original_hashes)
        stale = b"renamed old runtime"
        MODULE.FORBIDDEN_HASHES = frozenset({MODULE.digest(stale)})
        entries["lib/arm64-v8a/renamed-old-runtime.so"] = stale
        with self.assertRaisesRegex(ValueError, "failed OCR candidate hash"):
            MODULE.verify(self.write_apk(entries))

    def test_runner_assembles_and_verifies_before_any_adb_use(self):
        runner = (MODULE_PATH.parent / "run-priority2-development-api36.sh").read_text()
        java = runner.index("resolve_android_studio_java_home.sh")
        assemble = runner.index(":vision:assembleDebugAndroidTest")
        verify = runner.index("verify_priority2_development_apk.py")
        first_adb = runner.index('"$ADB"')
        self.assertLess(java, assemble)
        self.assertLess(assemble, verify)
        self.assertLess(verify, first_adb)
        self.assertIn("set -euo pipefail", runner)

    def test_unset_java_home_selects_exact_android_studio_jbr(self):
        resolver = MODULE_PATH.parent / "resolve_android_studio_java_home.sh"
        environment = os.environ.copy()
        environment.pop("JAVA_HOME", None)
        result = subprocess.run(
            ["bash", str(resolver)],
            check=True,
            capture_output=True,
            text=True,
            env=environment,
        )
        self.assertEqual(
            "/Applications/Android Studio.app/Contents/jbr/Contents/Home",
            result.stdout.strip(),
        )

    def test_invalid_explicit_java_home_is_rejected(self):
        resolver = MODULE_PATH.parent / "resolve_android_studio_java_home.sh"
        environment = os.environ.copy()
        environment["JAVA_HOME"] = "/definitely/not/a/jdk"
        result = subprocess.run(
            ["bash", str(resolver)],
            check=False,
            capture_output=True,
            text=True,
            env=environment,
        )
        self.assertEqual(2, result.returncode)
        self.assertIn("explicit JAVA_HOME has no executable bin/java", result.stderr)


if __name__ == "__main__":
    unittest.main()
