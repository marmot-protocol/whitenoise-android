"""Behavioral tests for explicitly dispatched Android instrumented suites."""
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


class InstrumentedDispatchTest(unittest.TestCase):
    """The dispatcher keeps bounded PR smoke, full post-merge and opt-in suites distinct."""

    def run_dispatch(self, *args, fail_app=False, fail_required=False):
        """Run the dispatcher against stub Gradle, opt-in suites and required-case checker."""
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            (root / "scripts").mkdir()
            (root / "config").mkdir()
            (root / "config/instrumented-required-cases.txt").write_text(
                (ROOT / "config/instrumented-required-cases.txt").read_text()
            )
            gradle = root / "gradlew"
            failure = 'case "$*" in *:app:connected*) exit 23;; esac\n' if fail_app else ''
            gradle.write_text('#!/bin/sh\n' + failure + 'printf "%s\\n" "$*"\n')
            gradle.chmod(0o755)
            for name in ("run-review-demo-e2e.sh", "run-document-provider-matrix.sh"):
                script = root / "scripts" / name
                script.write_text(f"#!/bin/sh\nprintf '{name}'\n")
                script.chmod(0o755)
            checker = root / "scripts" / "check_instrumented_required_cases.py"
            checker.write_text(
                "import sys\n"
                "print('required-cases ' + ' '.join(sys.argv[1:]))\n"
                f"sys.exit({29 if fail_required else 0})\n"
            )
            result = subprocess.run(
                ["bash", str(ROOT / "scripts/run-android-instrumented-tests.sh"), *args],
                cwd=root, text=True, capture_output=True, check=True,
            )
            return result.stdout

    def test_review_demo_dispatch_remains_available(self):
        """An explicit review-demo dispatch hands off to its isolated runner."""
        self.assertEqual(self.run_dispatch("workflow_dispatch", "true", "false"), "run-review-demo-e2e.sh")

    def test_document_provider_dispatch_remains_available(self):
        """An explicit document-provider dispatch hands off to its matrix runner."""
        self.assertEqual(self.run_dispatch("workflow_dispatch", "false", "true"), "run-document-provider-matrix.sh")

    def test_master_push_checks_native_correctness_without_timing_benchmark(self):
        """Master pushes run the full app suite and native verifier, never the timing benchmark."""
        output = self.run_dispatch("push")
        self.assertIn(":app:connectedDevZapstoreDebugAndroidTest", output)
        self.assertIn(":cryptoBenchmark:connectedReleaseAndroidTest", output)
        self.assertIn("NostrEventVerifierInstrumentedTest", output)
        self.assertNotIn("Bip340PhysicalBenchmark", output)

    def test_pull_request_checks_native_correctness_without_timing_benchmark(self):
        """Pull requests run the smoke annotation and native verifier, never the timing benchmark."""
        output = self.run_dispatch("pull_request")
        self.assertIn("PullRequestDeviceSmoke", output)
        self.assertIn(":cryptoBenchmark:connectedReleaseAndroidTest", output)
        self.assertIn("NostrEventVerifierInstrumentedTest", output)
        self.assertNotIn("Bip340PhysicalBenchmark", output)

    def test_both_opt_in_suites_are_rejected(self):
        """Selecting both opt-in suites in one dispatch is refused."""
        with self.assertRaises(subprocess.CalledProcessError) as caught:
            self.run_dispatch("workflow_dispatch", "true", "true")
        self.assertIn("Select one", caught.exception.stderr)

    def test_app_failure_cannot_be_hidden_by_passing_native_suite(self):
        """A failing app suite stops the run before the native suite can report success."""
        for event in ("push", "pull_request"):
            with self.subTest(event=event):
                with self.assertRaises(subprocess.CalledProcessError) as caught:
                    self.run_dispatch(event, fail_app=True)
                self.assertEqual(caught.exception.returncode, 23)

    def test_full_suite_checks_required_cases_after_the_app_suite(self):
        """Post-merge and plain dispatch runs verify required cases between the app and native suites."""
        for event in ("push", "workflow_dispatch"):
            with self.subTest(event=event):
                lines = self.run_dispatch(event, "false", "false").splitlines()
                check = "required-cases app/build/outputs/androidTest-results/connected"
                self.assertIn(check, lines)
                app = next(i for i, line in enumerate(lines) if ":app:connectedDevZapstoreDebugAndroidTest" in line)
                native = next(i for i, line in enumerate(lines) if ":cryptoBenchmark:connectedReleaseAndroidTest" in line)
                self.assertLess(app, lines.index(check))
                self.assertLess(lines.index(check), native)
                self.assertNotIn("PullRequestDeviceSmoke", lines[app])

    def test_pull_request_smoke_does_not_require_full_suite_cases(self):
        """Bounded PR smoke runs stay annotation-selected and skip the full-suite case list."""
        output = self.run_dispatch("pull_request")
        self.assertIn("PullRequestDeviceSmoke", output)
        self.assertNotIn("required-cases", output)

    def test_missing_required_case_fails_the_full_suite(self):
        """A required case that skipped or disappeared fails the run before the native suite."""
        with self.assertRaises(subprocess.CalledProcessError) as caught:
            self.run_dispatch("push", fail_required=True)
        self.assertEqual(caught.exception.returncode, 29)
        self.assertNotIn(":cryptoBenchmark:connectedReleaseAndroidTest", caught.exception.stdout)

    def test_responsiveness_dispatch_is_scoped_and_checks_required_cases(self):
        """Focused device acceptance excludes native benchmarks but cannot omit required cases."""
        output = self.run_dispatch("workflow_dispatch", "false", "false", "true")
        self.assertIn("ResponsivenessDeviceAcceptance", output)
        self.assertIn("required-cases", output)
        self.assertNotIn(":cryptoBenchmark:", output)
        with self.assertRaises(subprocess.CalledProcessError):
            self.run_dispatch("workflow_dispatch", "false", "false", "true", fail_required=True)
        with self.assertRaises(subprocess.CalledProcessError):
            self.run_dispatch("workflow_dispatch", "true", "false", "true")
