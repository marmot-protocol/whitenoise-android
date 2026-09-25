"""Behavioral tests for explicitly dispatched Android instrumented suites."""
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


class InstrumentedDispatchTest(unittest.TestCase):
    def run_dispatch(self, *args):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            (root / "scripts").mkdir()
            for name in ("run-review-demo-e2e.sh", "run-document-provider-matrix.sh"):
                script = root / "scripts" / name
                script.write_text(f"#!/bin/sh\nprintf '{name}'\n")
                script.chmod(0o755)
            result = subprocess.run(
                ["bash", str(ROOT / "scripts/run-android-instrumented-tests.sh"), *args],
                cwd=root, text=True, capture_output=True, check=True,
            )
            return result.stdout

    def test_review_demo_dispatch_remains_available(self):
        self.assertEqual(self.run_dispatch("workflow_dispatch", "true", "false"), "run-review-demo-e2e.sh")

    def test_document_provider_dispatch_remains_available(self):
        self.assertEqual(self.run_dispatch("workflow_dispatch", "false", "true"), "run-document-provider-matrix.sh")
