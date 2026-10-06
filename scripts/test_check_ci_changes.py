"""Regressions for code hiding in apparently documentation-only diffs."""

import subprocess
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch

from scripts.check_ci_changes import classify, docs_only_diff, supplemental_campaigns, supplemental_campaigns_diff


def entry(path, old='100644', new='100644', status='M'):
    return f':{old} {new} {"a" * 40} {"b" * 40} {status}\0{path}\0'.encode()


class CiChangesTest(unittest.TestCase):
    def test_only_known_plain_prose_files_skip_android(self):
        self.assertTrue(docs_only_diff(entry('README.md') + entry('docs/ci.md')))
        for path in ['app/src/main/assets/help.md', 'scripts/README.md',
                     'docs/manual-release-testing-surfaces.json',
                     'docs/composer-dictation-device-matrix.md', '.github/workflows/ci.yml',
                     'gradle.properties', 'new-unclassified-file.md', '../README.md']:
            with self.subTest(path=path):
                self.assertFalse(docs_only_diff(entry('README.md') + entry(path)))

    def test_renamed_or_deleted_code_cannot_be_hidden_as_docs(self):
        self.assertFalse(docs_only_diff(
            entry('app/src/main/Source.kt', new='000000', status='D') +
            entry('docs/Source.md', old='000000', status='A')))

    def test_symlinks_executables_and_unknown_status_are_full(self):
        for raw in [entry('README.md', new='120000'), entry('README.md', old='100755'),
                    entry('docs/README.md', status='T'), b'', b'broken\0',
                    entry('README.md')[:-1]]:
            self.assertFalse(docs_only_diff(raw))

    def test_push_dispatch_and_missing_refs_are_full_without_git(self):
        with patch('scripts.check_ci_changes.subprocess.run') as run:
            for event in ['push', 'workflow_dispatch', 'unknown']:
                self.assertFalse(classify(event, 'a' * 40, 'b' * 40))
            self.assertFalse(classify('pull_request', '', 'b' * 40))
            self.assertFalse(classify('pull_request', '--bad-ref', 'b' * 40))
            run.assert_not_called()

    def test_unavailable_diff_is_full(self):
        with patch('scripts.check_ci_changes.subprocess.run',
                   side_effect=subprocess.CalledProcessError(1, 'git')):
            self.assertFalse(classify('pull_request', 'a' * 40, 'b' * 40))

    def test_actual_git_diff_keeps_earlier_code_after_a_docs_fixup(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def git(*args):
                return subprocess.check_output(['git', '-C', directory, *args], text=True).strip()
            git('init', '-q')
            git('config', 'user.name', 'Fixture')
            git('config', 'user.email', 'fixture@example.invalid')
            git('config', 'commit.gpgsign', 'false')
            (root / 'README.md').write_text('base\n')
            git('add', '.')
            git('commit', '-qm', 'base')
            base = git('rev-parse', 'HEAD')
            (root / 'README.md').write_text('prose\n')
            git('commit', '-qam', 'docs')
            head = git('rev-parse', 'HEAD')
            raw = subprocess.check_output(['git', '-C', directory, 'diff', '--raw',
                                           '--no-abbrev', '--no-renames', '-z', f'{base}...{head}'])
            self.assertTrue(docs_only_diff(raw))
            (root / 'Source.kt').write_text('code\n')
            git('add', '.')
            git('commit', '-qm', 'code')
            (root / 'README.md').write_text('docs fixup\n')
            git('commit', '-qam', 'docs fixup')
            raw = subprocess.check_output(['git', '-C', directory, 'diff', '--raw',
                                           '--no-abbrev', '--no-renames', '-z', f'{base}...HEAD'])
            self.assertFalse(docs_only_diff(raw))


class SupplementalCampaignTest(unittest.TestCase):
    def test_ordinary_changes_defer_only_supplemental_campaigns(self):
        ordinary = ['README.md', 'docs/ci.md', 'app/src/main/java/org/example/Screen.kt',
                    'app/src/test/java/org/example/ScreenTest.kt',
                    'app/src/play/java/org/example/Push.java',
                    'app/src/main/res/values/strings.xml', 'app/src/test/snapshots/screen.png']
        for path in ordinary:
            with self.subTest(path=path):
                self.assertFalse(supplemental_campaigns_diff(entry(path)))
        self.assertFalse(supplemental_campaigns_diff(b''.join(entry(p) for p in ordinary)))
        for status, old, new in [('A', '000000', '100644'), ('D', '100644', '000000')]:
            self.assertFalse(supplemental_campaigns_diff(entry(ordinary[2], old, new, status)))

    def test_build_security_packaging_and_unknown_inputs_run_campaigns(self):
        full = ['app/build.gradle.kts', 'gradle/libs.versions.toml', 'gradlew',
                '.github/workflows/android-ci.yml', 'scripts/check_ci_changes.py',
                'app/src/main/AndroidManifest.xml', 'app/src/main/assets/help.md',
                'app/src/main/marmotkit/MARMOT_VERSION', 'app/src/main/jniLibs/lib.so',
                'docs/composer-dictation-device-matrix.md', 'unknown.md',
                '../README.md', 'app/src/main/java/../Hidden.kt',
                'app/src/main/java/./Hidden.kt', 'app/src/main/java//Hidden.kt',
                '/README.md', 'docs/evil\\name.md', 'docs/evil\nname.md']
        for path in full:
            with self.subTest(path=path):
                self.assertTrue(supplemental_campaigns_diff(entry('README.md') + entry(path)))

    def test_missing_malformed_or_unsafe_diff_is_full(self):
        for raw in [b'', b'broken\0', entry('README.md')[:-1],
                    entry('README.md', new='120000'), entry('README.md', old='100755'),
                    entry('README.md', status='R100'), entry('README.md', status='D'),
                    entry('README.md').replace(b'a' * 40, b'invalid'),
                    entry('README.md').replace(b'README.md', b'\xff')]:
            with self.subTest(raw=raw):
                self.assertTrue(supplemental_campaigns_diff(raw))

    def test_nightly_manual_tags_or_unavailable_evidence_run_full(self):
        with patch('scripts.check_ci_changes.subprocess.run') as run:
            for event in ['schedule', 'workflow_dispatch', 'unknown']:
                self.assertTrue(supplemental_campaigns(event, 'a' * 40, 'b' * 40))
            for event, base in [('pull_request', ''), ('push', 'bad'), ('push', '0' * 40)]:
                if base == '0' * 40:
                    run.side_effect = subprocess.CalledProcessError(1, 'git')
                self.assertTrue(supplemental_campaigns(event, base, 'b' * 40))
        with patch('scripts.check_ci_changes.subprocess.run',
                   return_value=subprocess.CompletedProcess([], 0, entry('README.md'))):
            self.assertFalse(supplemental_campaigns('pull_request', 'a' * 40, 'b' * 40))
            self.assertFalse(supplemental_campaigns('push', 'a' * 40, 'b' * 40))
        for error in [OSError('unavailable'), subprocess.TimeoutExpired('git', 30)]:
            with patch('scripts.check_ci_changes.subprocess.run', side_effect=error):
                self.assertTrue(supplemental_campaigns('pull_request', 'a' * 40, 'b' * 40))


if __name__ == '__main__':
    unittest.main()
