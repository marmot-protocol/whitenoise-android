"""Behavioral tests for explicitly dispatched Android instrumented suites."""
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


class InstrumentedDispatchTest(unittest.TestCase):
    def run_dispatch(self, *args, fail_app=False):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            (root / "scripts").mkdir()
            gradle = root / "gradlew"
            failure = 'case "$*" in *:app:connected*) exit 23;; esac\n' if fail_app else ''
            gradle.write_text('#!/bin/sh\n' + failure + 'printf "%s\\n" "$*"\n')
            gradle.chmod(0o755)
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

    def test_master_push_checks_native_correctness_without_timing_benchmark(self):
        output = self.run_dispatch("push")
        self.assertIn(":app:connectedDevZapstoreDebugAndroidTest", output)
        self.assertIn(":cryptoBenchmark:connectedReleaseAndroidTest", output)
        self.assertIn("NostrEventVerifierInstrumentedTest", output)
        self.assertNotIn("Bip340PhysicalBenchmark", output)

    def test_pull_request_runs_all_isolated_native_tests(self):
        output = self.run_dispatch("pull_request")
        self.assertIn("PullRequestDeviceSmoke", output)
        self.assertIn(":cryptoBenchmark:connectedReleaseAndroidTest", output)
        self.assertNotIn("testInstrumentationRunnerArguments.class=", output)

    def test_both_opt_in_suites_are_rejected(self):
        with self.assertRaises(subprocess.CalledProcessError) as caught:
            self.run_dispatch("workflow_dispatch", "true", "true")
        self.assertIn("Select one", caught.exception.stderr)

    def test_app_failure_cannot_be_hidden_by_passing_native_suite(self):
        for event in ("push", "pull_request"):
            with self.subTest(event=event):
                with self.assertRaises(subprocess.CalledProcessError) as caught:
                    self.run_dispatch(event, fail_app=True)
                self.assertEqual(caught.exception.returncode, 23)
