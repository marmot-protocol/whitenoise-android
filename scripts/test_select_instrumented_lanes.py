"""Emulator lane selection must narrow PRs without ever skipping on missing evidence."""

import subprocess
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch

from scripts.check_fuzz_pr_triggers import pr_paths
from scripts.select_instrumented_lanes import (
    BOTH, FIXTURE_DISPATCH, FIXTURE_ONLY, FIXTURE_SHARED, VIDEO_METADATA_FIXTURE_ONLY, VIDEO_METADATA_SHARED, WORKFLOW,
    changed_paths, matches, output_value, select,
)

ROOT = Path(__file__).resolve().parents[1]
TRIGGERS = pr_paths((ROOT / WORKFLOW).read_text())
MEDIA_TEST = 'app/src/androidTest/java/dev/ipf/whitenoise/android/media/'
UI_MEDIA_TEST = 'app/src/androidTest/java/dev/ipf/whitenoise/android/ui/conversation/media/'
SCRUBBER = 'app/src/main/java/dev/ipf/whitenoise/android/media/VideoMetadataScrubber.kt'
CLASSIFIER = 'app/src/main/java/dev/ipf/whitenoise/android/media/VideoContentClassifier.kt'
VIDEO_RUNNER = 'tools/attachment-fixture/video_metadata_runner.py'
VIDEO_TEST = UI_MEDIA_TEST + 'VideoMetadataFixtureDeviceTest.kt'
FULL = {'attachment_fixture': True, 'attachment_fixture_scope': 'full', 'connected': True}


class SelectInstrumentedLanesTest(unittest.TestCase):
    def test_every_selector_pattern_starts_the_workflow(self):
        """Every path the selector classifies also starts the workflow, or the selector could never see it."""
        for pattern in FIXTURE_ONLY + FIXTURE_SHARED:
            with self.subTest(pattern=pattern):
                self.assertIn(pattern, TRIGGERS)
        # A file under a listed directory starts the workflow through that directory's own pattern.
        for pattern in VIDEO_METADATA_FIXTURE_ONLY + VIDEO_METADATA_SHARED + FIXTURE_DISPATCH:
            with self.subTest(pattern=pattern):
                self.assertTrue(pattern in TRIGGERS or ('*' not in pattern and matches(pattern, TRIGGERS)))

    def test_fixture_only_inputs_are_not_pull_request_smoke_tests(self):
        """A fixture-only test input never carries the annotation the connected PR run selects."""
        for pattern in FIXTURE_ONLY + VIDEO_METADATA_FIXTURE_ONLY:
            path = ROOT / pattern
            if path.suffix in ('.kt', '.java'):
                with self.subTest(pattern=pattern):
                    self.assertNotIn('PullRequestDeviceSmoke', path.read_text())

    def test_glob_semantics_match_github_path_filters(self):
        self.assertTrue(matches('tools/attachment-fixture/a/b.py', ['tools/attachment-fixture/**']))
        self.assertTrue(matches('app/src/main/java/dev/ipf/whitenoise/android/media/AnimationSourceGif.kt',
                                ['app/src/main/java/dev/ipf/whitenoise/android/media/AnimationSource*.kt']))
        self.assertFalse(matches('app/src/main/java/dev/ipf/whitenoise/android/media/x/AnimationSourceGif.kt',
                                 ['app/src/main/java/dev/ipf/whitenoise/android/media/AnimationSource*.kt']))
        self.assertFalse(matches('app/build.gradle.kts.bak', ['app/build.gradle.kts']))

    def test_fixture_only_change_skips_connected_lane(self):
        """Shared fixture probes and tools run the whole fixture campaign without the connected emulator."""
        lanes = select('pull_request', [MEDIA_TEST + 'ControlledAttachmentProbe.kt',
                                        'tools/attachment-fixture/apk_checker.py'], TRIGGERS)
        self.assertEqual(lanes, {'attachment_fixture': True, 'attachment_fixture_scope': 'full', 'connected': False})

    def test_smoke_input_change_skips_fixture_lane(self):
        """Connected-only inputs boot no fixture emulator and select no fixture scope."""
        lanes = select('pull_request', [
            'app/src/main/java/dev/ipf/whitenoise/android/ui/common/ScrollEdgeFade.kt',
            'app/src/main/java/dev/ipf/whitenoise/android/ui/Unrelated.kt'], TRIGGERS)
        self.assertEqual(lanes, {'attachment_fixture': False, 'attachment_fixture_scope': '', 'connected': True})

    def test_shared_build_inputs_run_both(self):
        """Build and workflow inputs run both lanes and the whole fixture campaign."""
        for path in ['app/build.gradle.kts', 'gradle/libs.versions.toml', WORKFLOW,
                     'app/src/main/marmotkit/MARMOT_VERSION']:
            with self.subTest(path=path):
                self.assertEqual(select('pull_request', [path], TRIGGERS), FULL)

    def test_network_wait_registry_alone_runs_the_fixture(self):
        """A change limited to the network-wait registry starts the workflow and selects the receipt fixture."""
        path = 'app/src/main/java/dev/ipf/whitenoise/android/state/AutomaticAttachmentNetworkWaits.kt'
        self.assertTrue(matches(path, TRIGGERS))
        self.assertTrue(select('pull_request', [path], TRIGGERS)['attachment_fixture'])

    def test_policy_diagnostics_run_their_contract_and_controlled_fixture(self):
        """The helper affects both lanes; its isolated contract test starts connected smoke by itself."""
        helper = MEDIA_TEST + 'BackgroundReceiptPolicyDiagnostics.kt'
        contract = MEDIA_TEST + 'BackgroundReceiptPolicyDiagnosticsAndroidTest.kt'
        self.assertTrue(matches(helper, TRIGGERS))
        self.assertTrue(matches(contract, TRIGGERS))
        self.assertEqual(select('pull_request', [helper], TRIGGERS), FULL)
        self.assertEqual(select('pull_request', [contract], TRIGGERS),
                         {'attachment_fixture': False, 'attachment_fixture_scope': '', 'connected': True})

    def test_unrelated_files_beside_fixture_inputs_do_not_start_connected(self):
        """The fixture run script on its own runs the whole campaign, and unrelated files start nothing more."""
        lanes = select('pull_request', ['README.md', 'app/src/main/java/Other.kt',
                                        'scripts/run-controlled-attachment-fixture.sh'], TRIGGERS)
        self.assertEqual(lanes, {'attachment_fixture': True, 'attachment_fixture_scope': 'full', 'connected': False})

    def test_video_metadata_inputs_alone_run_only_the_focused_scenario(self):
        """Routing, fixture and provider changes run the video-metadata scenario and boot no connected emulator."""
        removal = 'app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/media/VideoMetadataRemoval.kt'
        paths = [CLASSIFIER, removal, VIDEO_RUNNER, VIDEO_TEST, UI_MEDIA_TEST + 'GeneratedDocumentProvider.java']
        for path in paths:
            with self.subTest(path=path):
                self.assertEqual(select('pull_request', [path], TRIGGERS),
                                 {'attachment_fixture': True, 'attachment_fixture_scope': 'video-metadata',
                                  'connected': False})

    def test_scrubber_inputs_run_the_focused_scenario_and_the_platform_proof(self):
        """The production scrubber and its fixtures feed both the scenario and the connected platform proof."""
        for path in [SCRUBBER, 'app/src/androidTest/assets/video-metadata/mp4-tagged.mp4.b64']:
            with self.subTest(path=path):
                self.assertEqual(select('pull_request', [path], TRIGGERS),
                                 {'attachment_fixture': True, 'attachment_fixture_scope': 'video-metadata',
                                  'connected': True})

    def test_dispatch_plumbing_beside_video_metadata_inputs_stays_focused(self):
        """Adding the scenario's own mode, runner and lane selection does not rerun every other scenario."""
        paths = [WORKFLOW, 'scripts/select_instrumented_lanes.py', 'scripts/run-controlled-attachment-fixture.sh',
                 'tools/attachment-fixture/README.md', VIDEO_RUNNER, VIDEO_TEST, SCRUBBER]
        lanes = select('pull_request', paths, TRIGGERS)
        self.assertEqual('video-metadata', lanes['attachment_fixture_scope'])
        self.assertTrue(lanes['attachment_fixture'])

    def test_dispatch_plumbing_alone_or_any_other_fixture_input_runs_the_whole_campaign(self):
        """Plumbing on its own, or any input another scenario reads, keeps the whole campaign."""
        cases = [['scripts/run-controlled-attachment-fixture.sh'], [WORKFLOW],
                 [SCRUBBER, 'app/src/main/java/dev/ipf/whitenoise/android/media/MediaPipeline.kt'],
                 [VIDEO_RUNNER, 'tools/attachment-fixture/media_lifecycle_runner.py'],
                 [VIDEO_TEST, MEDIA_TEST + 'FixtureSession.kt'],
                 [CLASSIFIER, 'app/build.gradle.kts']]
        for paths in cases:
            with self.subTest(paths=paths):
                self.assertEqual('full', select('pull_request', paths, TRIGGERS)['attachment_fixture_scope'])

    def test_the_fixture_job_runs_only_the_focused_mode_for_a_focused_scope(self):
        """Every other mode is skipped for the video-metadata scope, and the focused mode runs in every scope."""
        workflow = (ROOT / WORKFLOW).read_text()
        selector = workflow.split('  changes:\n', 1)[1].split('\n  attachment-fixture:\n', 1)[0]
        self.assertIn('attachment_fixture_scope: ${{ steps.select.outputs.attachment_fixture_scope }}', selector)
        job = workflow.split('\n  attachment-fixture:\n', 1)[1].split('\n  instrumented:\n', 1)[0]
        self.assertIn('FIXTURE_SCOPE: ${{ needs.changes.outputs.attachment_fixture_scope }}', job)
        modes = [line.strip() for line in job.splitlines() if 'run-controlled-attachment-fixture.sh' in line]
        focused = [line for line in modes if line.endswith(' controller-video-metadata')]
        self.assertEqual(1, len(focused))
        self.assertTrue(focused[0].startswith('bash scripts/run-controlled-attachment-fixture.sh '))
        others = [line for line in modes if line not in focused]
        self.assertGreater(len(others), 10)
        for line in others:
            with self.subTest(line=line):
                self.assertTrue(line.startswith('[ "$FIXTURE_SCOPE" = video-metadata ] || bash '))
        script = (ROOT / 'scripts/run-controlled-attachment-fixture.sh').read_text()
        self.assertIn('controller-video-metadata) ;;', script)
        self.assertIn('tools/attachment-fixture/video_metadata_runner.py', script)

    def test_outputs_write_booleans_and_the_scope_name(self):
        """GitHub reads lanes as true or false and the scope by its name, with no scope for no fixture lane."""
        self.assertEqual(['true', 'false', 'video-metadata', ''],
                         [output_value(True), output_value(False), output_value('video-metadata'), output_value('')])
        self.assertEqual('full', BOTH['attachment_fixture_scope'])

    def test_missing_evidence_and_other_events_run_both(self):
        """Any other event, or a diff that cannot be read or matched, runs everything."""
        both = FULL
        for event in ['push', 'workflow_dispatch', 'merge_group', 'schedule']:
            self.assertEqual(select(event, ['README.md'], TRIGGERS), both)
        self.assertEqual(select('pull_request', None, TRIGGERS), both)
        self.assertEqual(select('pull_request', [], TRIGGERS), both)
        self.assertEqual(select('pull_request', ['README.md'], None), both)
        # GitHub started the workflow, so a diff with no matching path is not trusted.
        self.assertEqual(select('pull_request', ['README.md'], TRIGGERS), both)

    def test_unreadable_diffs_return_none(self):
        self.assertIsNone(changed_paths('', 'b' * 40))
        self.assertIsNone(changed_paths('--output=x', 'b' * 40))
        with patch('scripts.select_instrumented_lanes.subprocess.run',
                   side_effect=subprocess.CalledProcessError(128, 'git')):
            self.assertIsNone(changed_paths('a' * 40, 'b' * 40))

    def test_actual_git_diff_lists_deleted_and_renamed_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)

            def git(*args):
                return subprocess.check_output(['git', '-C', directory, *args], text=True).strip()
            git('init', '-q')
            git('config', 'user.name', 'Fixture')
            git('config', 'user.email', 'fixture@example.invalid')
            git('config', 'commit.gpgsign', 'false')
            (root / 'old.kt').write_text('a\n')
            (root / 'gone.kt').write_text('b\n')
            git('add', '.')
            git('commit', '-qm', 'base')
            base = git('rev-parse', 'HEAD')
            git('mv', 'old.kt', 'new.kt')
            git('rm', '-q', 'gone.kt')
            git('commit', '-qm', 'head')
            head = git('rev-parse', 'HEAD')
            real_run = subprocess.run
            with patch('scripts.select_instrumented_lanes.subprocess.run',
                       side_effect=lambda cmd, **kw: real_run(['git', '-C', directory, *cmd[1:]], **kw)):
                self.assertEqual(sorted(changed_paths(base, head)), ['gone.kt', 'new.kt', 'old.kt'])


if __name__ == '__main__':
    unittest.main()
