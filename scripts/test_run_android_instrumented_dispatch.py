"""Behavioral tests for explicitly dispatched Android instrumented suites."""
import pathlib
import subprocess
import sys
import xml.etree.ElementTree as ET
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
            (root / "config/mdk-adoption-required-cases.txt").write_text(
                (ROOT / "config/mdk-adoption-required-cases.txt").read_text()
            )
            (root / "config/range-selection-required-cases.txt").write_text(
                (ROOT / "config/range-selection-required-cases.txt").read_text()
            )
            gradle = root / "gradlew"
            failure = 'case "$*" in *:app:connected*) exit 23;; esac\n' if fail_app else ''
            gradle.write_text('#!/bin/sh\n' + failure + 'printf "%s\\n" "$*"\n')
            gradle.chmod(0o755)
            for name in ("run-review-demo-e2e.sh", "run-document-provider-matrix.sh", "run-outbound-share-contract.sh", "install-outbound-share-fixture.sh"):
                script = root / "scripts" / name
                script.write_text(f"#!/bin/sh\nprintf '{name}'\n")
                script.chmod(0o755)
            checker = root / "scripts" / "check_instrumented_required_cases.py"
            checker.write_text(
                "import sys\n"
                "print('required-cases ' + ' '.join(sys.argv[1:]))\n"
                "if '--required' in sys.argv: print(open(sys.argv[sys.argv.index('--required') + 1]).read())\n"
                f"sys.exit({29 if fail_required else 0})\n"
            )
            result = subprocess.run(
                ["bash", str(ROOT / "scripts/run-android-instrumented-tests.sh"), *args],
                cwd=root, text=True, capture_output=True, check=True,
            )
            return result.stdout

    def test_startup_trace_and_range_selection_cannot_mix(self):
        """Both selected lanes fail before either producer runs."""
        for api in ("30", "36"):
            with self.subTest(api=api), self.assertRaises(subprocess.CalledProcessError):
                self.run_dispatch("workflow_dispatch", *(["false"] * 3), "true", "false", "false", "true", api, "true")

    def test_range_selection_dispatch_uses_one_class_and_requires_its_execution(self):
        """The real-window regression runs alone and cannot turn green from a skip or a missing result."""
        flags = ["false"] * 6 + ["false", "30", "true"]
        output = self.run_dispatch("workflow_dispatch", *flags)
        self.assertIn("RunnerArguments.class=dev.ipf.whitenoise.android.ui.conversation.ConversationDragSelectionPopupAndroidTest", output)
        self.assertIn("--required config/range-selection-required-cases.txt", output)
        self.assertNotIn(":cryptoBenchmark:", output)
        self.assertNotIn("install-outbound-share-fixture", output)
        for options in ({"fail_app": True}, {"fail_required": True}):
            with self.subTest(options=options), self.assertRaises(subprocess.CalledProcessError):
                self.run_dispatch("workflow_dispatch", *flags, **options)
        for index in range(6):
            overlap = list(flags)
            overlap[index] = "true"
            with self.subTest(overlap=index), self.assertRaises(subprocess.CalledProcessError):
                self.run_dispatch("workflow_dispatch", *overlap)

    def test_mdk_adoption_explicitly_enables_diagnostics_and_requires_native_cases(self):
        output = self.run_dispatch("workflow_dispatch", "false", "false", "false", "false", "false", "true")
        self.assertIn("-PstartupDiagnostics=true", output)
        self.assertIn("MdkAdoptionDeviceAcceptance", output)
        self.assertIn("--required config/mdk-adoption-required-cases.txt", output)
        self.assertNotIn("notAnnotation", output)
        self.assertNotIn("install-outbound-share-fixture", output)
        self.assertNotIn(":cryptoBenchmark:", output)
        for options in ({"fail_app": True}, {"fail_required": True}):
            with self.subTest(options=options), self.assertRaises(subprocess.CalledProcessError):
                self.run_dispatch("workflow_dispatch", "false", "false", "false", "false", "false", "true", **options)

    def test_mdk_adoption_rejects_other_focused_suites(self):
        for index in range(5):
            flags = ["false"] * 5
            flags[index] = "true"
            with self.subTest(index=index), self.assertRaises(subprocess.CalledProcessError):
                self.run_dispatch("workflow_dispatch", *flags, "true")

    def test_mdk_diagnostics_never_enable_from_a_pull_request_flag(self):
        output = self.run_dispatch("pull_request", "false", "false", "false", "false", "false", "true")
        self.assertNotIn("startupDiagnostics", output)
        self.assertIn("PullRequestDeviceSmoke", output)

    def test_media_sandbox_dispatch_is_focused_and_requires_every_case(self):
        output = self.run_dispatch("workflow_dispatch", "false", "false", "false", "true")
        self.assertIn("media.sandbox.MediaContainmentAcceptance", output)
        self.assertNotIn("RunnerArguments.class=", output)
        self.assertIn("required-cases", output)
        self.assertNotIn(":cryptoBenchmark:", output)
        self.assertNotIn("PullRequestDeviceSmoke", output)
        with self.assertRaises(subprocess.CalledProcessError):
            self.run_dispatch("workflow_dispatch", "false", "false", "false", "true", fail_required=True)

    def test_native_sandbox_player_and_corpus_are_selected_and_required(self):
        directory = ROOT / "app/src/androidTest/java/dev/ipf/whitenoise/android/media/sandbox"
        methods = {
            "NativeMediaSandboxDeviceTest": (
                "installerCoversExistingAndNewJavaAndNativeThreads",
                "clone3IsDeniedOrThePlatformTerminatesItsDedicatedWorker",
                "blockedNativeReadEndsWithObservedOsProcessDisappearance"),
            "IsolatedAttachmentPlayerDeviceTest": (
                "pausedSeekingSpeedPauseAndRepeatUseRealRawPlayback",
                "pixelCopyBufferOutlivesTimedOutRequestUntilItsCallback"),
            "MediaAvNativeCorpusDeviceTest": (
                "commonVideoFormatsReturnRedPixelsFromFreshRestrictedWorkers",
                "commonAudioFormatsReturnFiniteWaveformsAndDurations"),
        }
        focused = self.run_dispatch("workflow_dispatch", "false", "false", "false", "true")
        for name, names in methods.items():
            source = (directory / (name + ".kt")).read_text()
            self.assertIn("@MediaContainmentAcceptance", source)
            self.assertIn("@PullRequestDeviceSmoke", source)
            for method in names:
                self.assertIn("fun " + method + "()", source)
                case = "dev.ipf.whitenoise.android.media.sandbox." + name + "#" + method
                self.assertIn(case, focused)
        with self.assertRaises(subprocess.CalledProcessError):
            self.run_dispatch("workflow_dispatch", "false", "false", "false", "true", fail_app=True)
        with self.assertRaises(subprocess.CalledProcessError):
            self.run_dispatch("workflow_dispatch", "false", "false", "false", "true", fail_required=True)

    def test_actual_checker_rejects_missing_or_skipped_selected_native_case(self):
        focused = self.run_dispatch("workflow_dispatch", "false", "false", "false", "true")
        cases = [line for line in focused.splitlines() if line.startswith("dev.ipf.") and "#" in line]
        native = [line for line in cases if any("." + name + "#" in line for name in (
            "NativeMediaSandboxDeviceTest", "IsolatedAttachmentPlayerDeviceTest", "MediaAvNativeCorpusDeviceTest"))]
        self.assertEqual(7, len(native))
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            required = root / "required.txt"
            required.write_text("\n".join(cases) + "\n")

            def run(missing=None, skipped=None):
                suite = ET.Element("testsuite")
                for case in cases:
                    if case == missing:
                        continue
                    classname, method = case.split("#")
                    entry = ET.SubElement(suite, "testcase", classname=classname, name=method)
                    if case == skipped:
                        ET.SubElement(entry, "skipped")
                ET.ElementTree(suite).write(root / "results.xml")
                return subprocess.run([sys.executable, str(ROOT / "scripts/check_instrumented_required_cases.py"),
                                       str(root), "--required", str(required)], capture_output=True, text=True)

            self.assertEqual(0, run().returncode)
            for case in native:
                for condition in ("missing", "skipped"):
                    with self.subTest(case=case, condition=condition):
                        result = run(**{condition: case})
                        self.assertNotEqual(0, result.returncode)
                        self.assertIn(case, result.stderr)

    def test_baseline_is_required_in_focused_containment_and_excluded_from_pr_smoke(self):
        """Check real class annotations plus the dispatcher's actual required-case selection."""
        directory = ROOT / "app/src/androidTest/java/dev/ipf/whitenoise/android/media/sandbox"
        baseline = (directory / "MediaWorkerBaselineDeviceTest.kt").read_text()
        original = (directory / "IsolatedMediaDecoderDeviceTest.kt").read_text()
        self.assertIn("@MediaContainmentAcceptance", baseline)
        self.assertNotIn("@PullRequestDeviceSmoke", baseline)
        self.assertIn("@PullRequestDeviceSmoke", original)
        self.assertIn("@MediaContainmentAcceptance", original)
        method = "characterizeDirectStillTransportWithFixedSyntheticCorpus"
        self.assertIn("fun " + method + "()", baseline)
        self.assertNotIn("fun " + method + "()", original)
        case = "dev.ipf.whitenoise.android.media.sandbox.MediaWorkerBaselineDeviceTest#" + method
        focused = self.run_dispatch("workflow_dispatch", "false", "false", "false", "true")
        self.assertIn(case, focused)
        required = (ROOT / "config/instrumented-required-cases.txt").read_text().splitlines()
        for line in required:
            if any("." + name + "#" in line for name in (
                "IsolatedMediaDecoderDeviceTest", "AnimationSourceAdmissionDeviceTest", "MediaCodecCorpusDeviceTest"
            )):
                self.assertIn(line, focused)
        smoke = self.run_dispatch("pull_request")
        self.assertIn("RunnerArguments.annotation=dev.ipf.whitenoise.android.PullRequestDeviceSmoke", smoke)
        self.assertNotIn("MediaContainmentAcceptance", smoke)
        self.assertNotIn(case, smoke)

    def test_healthy_timing_fixture_is_excluded_from_all_existing_suites(self):
        directory=ROOT/'app/src/androidTest/java/dev/ipf/whitenoise/android/media/sandbox'
        source=(directory/'MediaWorkerHealthyBaselineDeviceTest.kt').read_text()
        self.assertIn('@ManualDeviceFixture',source)
        for annotation in ('@PullRequestDeviceSmoke','@MediaContainmentAcceptance','@ResponsivenessDeviceAcceptance','@MdkAdoptionDeviceAcceptance'):
            self.assertNotIn(annotation,source)
        healthy='MediaWorkerHealthyBaselineDeviceTest'
        routes = [
            ('pull_request',),
            ('push',),
            ('workflow_dispatch',),
            ('workflow_dispatch','false','false','false','true'),
            ('workflow_dispatch','false','false','true'),
            ('workflow_dispatch','false','false','false','false','false','true'),
        ]
        for args in routes:
            output=self.run_dispatch(*args)
            self.assertNotIn(healthy,output)
            if len(args) == 1:
                self.assertIn('notAnnotation=dev.ipf.whitenoise.android.ManualDeviceFixture',output)
        required=(ROOT/'config/instrumented-required-cases.txt').read_text()
        self.assertNotIn(healthy,required)
        runner=(directory/'MediaWorkerBaseline.kt').read_text()
        self.assertIn('run(it, includeNegativeProbes = true)',runner)
        self.assertIn('run(it, includeNegativeProbes = false)',runner)
        self.assertIn('if (includeNegativeProbes)',runner)
        self.assertIn('probe("watchdog_stimulus", transport, binding, MediaDecodePolicy.DEBUG_HANG)',runner)
        self.assertIn('"application_debuggable"',runner)

    def test_av_capability_capture_is_required_only_by_containment_selection(self):
        directory = ROOT / "app/src/androidTest/java/dev/ipf/whitenoise/android/media/sandbox"
        source = (directory / "MediaAvCapabilityDeviceTest.kt").read_text()
        self.assertIn("@MediaContainmentAcceptance", source)
        self.assertNotIn("@PullRequestDeviceSmoke", source)
        method = "characterizeSelectedDescriptorPlatformMediaFromAnIsolatedUid"
        self.assertIn("fun " + method + "()", source)
        case = "dev.ipf.whitenoise.android.media.sandbox.MediaAvCapabilityDeviceTest#" + method
        focused = self.run_dispatch("workflow_dispatch", "false", "false", "false", "true")
        self.assertIn(case, focused)
        required = (ROOT / "config/instrumented-required-cases.txt").read_text().splitlines()
        names = ("IsolatedMediaDecoderDeviceTest", "AnimationSourceAdmissionDeviceTest",
                 "MediaCodecCorpusDeviceTest", "MediaWorkerBaselineDeviceTest")
        for line in required:
            if any("." + name + "#" in line for name in names):
                self.assertIn(line, focused)
        smoke = self.run_dispatch("pull_request")
        self.assertNotIn(case, smoke)
        self.assertIn("RunnerArguments.annotation=dev.ipf.whitenoise.android.PullRequestDeviceSmoke", smoke)

    def test_outbound_share_dispatch_is_focused_and_rejects_overlapping_suites(self):
        """Real package visibility uses its own emulator users without also running the full suite."""
        self.assertEqual(
            self.run_dispatch("workflow_dispatch", "false", "false", "false", "false", "true"),
            "run-outbound-share-contract.sh",
        )
        for flags in (("true", "false", "false", "false"), ("false", "true", "false", "false"), ("false", "false", "true", "false"), ("false", "false", "false", "true")):
            with self.subTest(flags=flags), self.assertRaises(subprocess.CalledProcessError):
                self.run_dispatch("workflow_dispatch", *flags, "true")

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
        self.assertIn("timeout_msec=120000", output)
        self.assertIn("timeout --signal=INT --kill-after=30s 25m", (ROOT / "scripts/run-android-instrumented-tests.sh").read_text())
        self.assertIn("required-cases", output)
        self.assertNotIn(":cryptoBenchmark:", output)
        with self.assertRaises(subprocess.CalledProcessError):
            self.run_dispatch("workflow_dispatch", "false", "false", "true", fail_required=True)
        with self.assertRaises(subprocess.CalledProcessError):
            self.run_dispatch("workflow_dispatch", "true", "false", "true")
