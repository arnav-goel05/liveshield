from __future__ import annotations

import hashlib
import subprocess
import tempfile
import unittest
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
FETCH_SCRIPT = REPOSITORY_ROOT / "tools" / "testdata" / "fetch-public-data.sh"


class PublicFetchTest(unittest.TestCase):
    def test_verify_file_accepts_exact_bytes_and_digest(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = Path(directory) / "archive.zip"
            fixture.write_bytes(b"synthetic-public-archive")
            digest = hashlib.sha256(fixture.read_bytes()).hexdigest()

            result = subprocess.run(
                [
                    str(FETCH_SCRIPT),
                    "--verify-file",
                    str(fixture),
                    "--bytes",
                    str(fixture.stat().st_size),
                    "--sha256",
                    digest,
                ],
                check=False,
                capture_output=True,
                text=True,
            )

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn("verified:", result.stdout)

    def test_verify_file_rejects_digest_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = Path(directory) / "archive.zip"
            fixture.write_bytes(b"synthetic-public-archive")

            result = subprocess.run(
                [
                    str(FETCH_SCRIPT),
                    "--verify-file",
                    str(fixture),
                    "--bytes",
                    str(fixture.stat().st_size),
                    "--sha256",
                    "0" * 64,
                ],
                check=False,
                capture_output=True,
                text=True,
            )

            self.assertNotEqual(0, result.returncode)
            self.assertIn("SHA-256 mismatch", result.stderr)

    def test_list_exposes_supported_public_inputs(self) -> None:
        result = subprocess.run(
            [str(FETCH_SCRIPT), "--list"],
            check=True,
            capture_output=True,
            text=True,
        )
        self.assertEqual(
            ["wider-val", "wider-annotations", "biv-support-images", "biv-support-json"],
            result.stdout.splitlines(),
        )


if __name__ == "__main__":
    unittest.main()
