"""Regressions for code hiding in apparently documentation-only diffs."""

import subprocess
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch

from scripts.check_ci_changes import classify, docs_only_diff


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


if __name__ == '__main__':
    unittest.main()
