"""Emulator lane selection must narrow PRs without ever skipping on missing evidence."""

import subprocess
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch

from scripts.check_fuzz_pr_triggers import pr_paths
from scripts.select_instrumented_lanes import (
    FIXTURE_ONLY, FIXTURE_SHARED, WORKFLOW, changed_paths, matches, select,
)

ROOT = Path(__file__).resolve().parents[1]
TRIGGERS = pr_paths((ROOT / WORKFLOW).read_text())
MEDIA_TEST = 'app/src/androidTest/java/dev/ipf/whitenoise/android/media/'


class SelectInstrumentedLanesTest(unittest.TestCase):
    def test_every_selector_pattern_starts_the_workflow(self):
        for pattern in FIXTURE_ONLY + FIXTURE_SHARED:
            with self.subTest(pattern=pattern):
                self.assertIn(pattern, TRIGGERS)

    def test_fixture_only_inputs_are_not_pull_request_smoke_tests(self):
        for pattern in FIXTURE_ONLY:
            path = ROOT / pattern
            if path.suffix == '.kt':
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
        lanes = select('pull_request', [MEDIA_TEST + 'ControlledAttachmentProbe.kt',
                                        'tools/attachment-fixture/apk_checker.py'], TRIGGERS)
        self.assertEqual(lanes, {'attachment_fixture': True, 'connected': False})

    def test_smoke_input_change_skips_fixture_lane(self):
        lanes = select('pull_request', [
            'app/src/main/java/dev/ipf/whitenoise/android/ui/common/ScrollEdgeFade.kt',
            'app/src/main/java/dev/ipf/whitenoise/android/ui/Unrelated.kt'], TRIGGERS)
        self.assertEqual(lanes, {'attachment_fixture': False, 'connected': True})

    def test_shared_build_inputs_run_both(self):
        for path in ['app/build.gradle.kts', 'gradle/libs.versions.toml', WORKFLOW,
                     'app/src/main/marmotkit/MARMOT_VERSION']:
            with self.subTest(path=path):
                self.assertEqual(select('pull_request', [path], TRIGGERS),
                                 {'attachment_fixture': True, 'connected': True})

    def test_unrelated_files_beside_fixture_inputs_do_not_start_connected(self):
        lanes = select('pull_request', ['README.md', 'app/src/main/java/Other.kt',
                                        'scripts/run-controlled-attachment-fixture.sh'], TRIGGERS)
        self.assertEqual(lanes, {'attachment_fixture': True, 'connected': False})

    def test_missing_evidence_and_other_events_run_both(self):
        both = {'attachment_fixture': True, 'connected': True}
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
