"""Regressions for code hiding in apparently documentation-only diffs."""

import subprocess
import contextlib
import io
import json
import os
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch

from scripts.check_ci_changes import complete_diff, docs_only_diff, supplemental_campaigns_diff, validation_selection, main


def entry(path, old='100644', new='100644', status='M'):
    return f':{old} {new} {"a" * 40} {"b" * 40} {status}\0{path}\0'.encode()


def cli_outputs(event, mode='', labels=None, raw=None, base='a' * 40, head='b' * 40, observe=False):
    """Exercise the emitted contract and require one complete-diff observation."""
    stdout = io.StringIO()
    environment = {'ANDROID_CI_MODE': mode, 'CI_CURRENT_LABELS': json.dumps(labels)}
    with patch.dict(os.environ, environment, clear=True), contextlib.redirect_stdout(stdout), \
         patch('sys.argv', ['classifier', '--event', event, f'--base={base}', f'--head={head}']):
        if observe:
            main()
        else:
            with patch('scripts.check_ci_changes.complete_diff', return_value=raw) as diff:
                main()
                diff.assert_called_once_with(event, base, head)
    return dict(line.split('=', 1) for line in stdout.getvalue().splitlines())


def precheck_mode(event, mode, labels, raw):
    return cli_outputs(event, mode, labels, raw)['precheck'] == 'true'


def validation_mode(event, mode, labels, raw):
    return cli_outputs(event, mode, labels, raw)['ci_mode']


def classify(event, base, head):
    return cli_outputs(event, base=base, head=head, observe=True)['docs_only'] == 'true'


def supplemental_campaigns(event, base, head):
    return cli_outputs(event, base=base, head=head, observe=True)['supplemental_campaigns'] == 'true'


class CiChangesTest(unittest.TestCase):
    def test_precheck_requires_activation_current_labels_and_known_complete_diff(self):
        ordinary = entry('app/src/main/java/example/Screen.kt')
        self.assertTrue(precheck_mode('pull_request', 'merge-queue', [], ordinary))
        self.assertTrue(precheck_mode('pull_request', 'merge-queue', ['bug'], ordinary))
        for labels in (None, {}, ['ci:full'], ['bug', 'ci:full'], [1]):
            self.assertFalse(precheck_mode('pull_request', 'merge-queue', labels, ordinary))
        for event in ('push', 'merge_group', 'schedule', 'workflow_dispatch', ''):
            self.assertFalse(precheck_mode(event, 'merge-queue', [], ordinary))
        for mode in ('', 'unknown', 'legacy', 'MERGE-QUEUE'):
            self.assertFalse(precheck_mode('pull_request', mode, [], ordinary))
        for raw in (None, b'', b'bad', entry('gradle/libs.versions.toml'),
                    entry('.github/workflows/android-ci.yml'),
                    entry('app/src/main/marmotkit/MARMOT_VERSION'),
                    ordinary + entry('build.gradle.kts'),
                    entry('app/src/main/java/example/Screen.kt', new='120000')):
            self.assertFalse(precheck_mode('pull_request', 'merge-queue', [], raw))

    def test_required_test_metadata_is_not_docs_only_but_can_use_precheck(self):
        source = entry('app/src/main/java/example/Screen.kt')
        for path in ('docs/invariant-gates.md', 'docs/manual-release-testing-surfaces.json',
                     'docs/manual-release-testing/surfaces/app/src/main/java/example/Screen.kt.json'):
            for raw in (entry(path), source + entry(path)):
                values = cli_outputs('pull_request', 'merge-queue', [], raw)
                self.assertEqual(values['docs_only'], 'false')
                self.assertEqual(values['precheck'], 'true')
                self.assertEqual(values['supplemental_campaigns'], 'false')
            for raw in (entry(path, new='120000'), entry(path, old='100755'),
                        entry(path, status='T'), source + entry('docs/unknown.json')):
                self.assertEqual(validation_mode('pull_request', 'merge-queue', [], raw), 'full')

    def test_isolated_host_suites_use_complete_diff_and_fail_closed(self):
        source = entry('app/src/main/java/example/Screen.kt')
        legacy = cli_outputs('pull_request', '', [], source)
        self.assertEqual(legacy['ci_mode'], 'legacy-pr')
        self.assertEqual(legacy['tooling_regressions'], 'false')
        for event in ('pull_request', 'merge_group', 'push', 'schedule', 'workflow_dispatch'):
            value = cli_outputs(event, 'merge-queue', [], source)['tooling_regressions']
            self.assertEqual(value, str(event != 'pull_request').lower())
        for raw in (None, b'', b'bad', source + entry('scripts/helper.py'),
                    source + entry('tools/attachment-fixture/server.py'),
                    source + entry('.maestro/runtime/example.yaml'),
                    source + entry('app/src/androidTest/java/example/Fixture.kt'),
                    source + entry('build.gradle.kts')):
            self.assertEqual(cli_outputs('pull_request', '', [], raw)['tooling_regressions'], 'true')
        for labels in (None, {}, ['ci:full'], [1]):
            self.assertEqual(cli_outputs('pull_request', '', labels, source)['tooling_regressions'], 'true')

    def test_modes_are_exclusive_and_activated_prs_have_one_lane(self):
        for event in ('pull_request', 'merge_group', 'push', 'workflow_dispatch'):
            for configured in ('merge-queue', '', 'unknown'):
                for labels in (None, {}, [], ['ci:full'], ['bug'], [1], 'ci:full'):
                    for raw in (None, b'', entry('gradle.properties'), entry('app/src/main/java/example/Screen.kt')):
                        selected = validation_selection(event, configured, labels, raw)
                        mode = selected['ci_mode']
                        count = sum(selected[key] == 'true' for key in ('precheck', 'full_matrix'))
                        self.assertLessEqual(count, 1)
                        self.assertEqual(mode, validation_mode(event, configured, labels, raw))
                        self.assertIn(mode, ('full', 'precheck', 'legacy-pr'))
                        if event == 'pull_request' and configured == 'merge-queue':
                            self.assertEqual(count, 1)
                            self.assertIn(mode, ('full', 'precheck'))
                        if event != 'pull_request':
                            self.assertEqual(mode, 'full')
                        self.assertEqual(precheck_mode(event, configured, labels, raw), mode == 'precheck')

    def test_cli_core_and_supplemental_selection_are_independent(self):
        ordinary = entry('app/src/main/java/example/Screen.kt')
        for event, mode, labels, raw, full, campaigns in [
            ('pull_request', 'merge-queue', [], ordinary, 'false', 'false'),
            ('pull_request', 'merge-queue', ['ci:full'], ordinary, 'true', 'false'),
            ('pull_request', 'merge-queue', None, ordinary, 'true', 'false'),
            ('merge_group', '', [], ordinary, 'true', 'false'),
            ('merge_group', '', [], entry('README.md'), 'true', 'false'),
            ('merge_group', '', [], entry('gradle.properties'), 'true', 'true'),
            ('workflow_dispatch', '', [], None, 'true', 'true'),
        ]:
            with self.subTest(event=event, labels=labels, raw=raw):
                values = cli_outputs(event, mode, labels, raw)
                self.assertEqual(values['full_matrix'], full)
                self.assertEqual(values['supplemental_campaigns'], campaigns)
                if full == 'true':
                    self.assertEqual(values['docs_only'], 'false')

    def test_cli_github_output_matches_stdout_and_observes_diff_once(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'output'
            stdout = io.StringIO()
            with patch.dict(os.environ, {'GITHUB_OUTPUT': str(output)}, clear=True), \
                 contextlib.redirect_stdout(stdout), \
                 patch('sys.argv', ['classifier', '--event', 'merge_group', '--head', 'b' * 40]), \
                 patch('scripts.check_ci_changes.complete_diff', return_value=entry('README.md')) as diff:
                main()
            diff.assert_called_once_with('merge_group', '', 'b' * 40)
            self.assertEqual(output.read_text(), stdout.getvalue())
            self.assertEqual(set(dict(line.split('=', 1) for line in stdout.getvalue().splitlines())),
                             {'ci_mode', 'precheck', 'full_matrix', 'docs_only', 'supplemental_campaigns', 'tooling_regressions'})

    def test_only_known_plain_prose_files_skip_android(self):
        self.assertTrue(docs_only_diff(entry('README.md') + entry('docs/ci.md')))
        for path in ['app/src/main/assets/help.md', 'scripts/README.md',
                     'docs/manual-release-testing-surfaces.json',
                     'docs/composer-dictation-device-matrix.md', '.github/workflows/ci.yml',
                     'gradle.properties', 'new-unclassified-file.md', '../README.md']:
            with self.subTest(path=path):
                self.assertFalse(docs_only_diff(entry('README.md') + entry(path)))

    def test_registry_only_changes_run_the_invariant_validator(self):
        """Registry edits cannot skip their Kotlin validator or its test-input campaigns."""
        for status, old, new in [('A', '000000', '100644'), ('M', '100644', '100644'),
                                 ('D', '100644', '000000')]:
            with self.subTest(status=status):
                raw = entry('docs/invariant-gates.md', old=old, new=new, status=status)
                self.assertFalse(docs_only_diff(raw))
                self.assertFalse(supplemental_campaigns_diff(raw))
                self.assertFalse(docs_only_diff(entry('README.md') + raw))

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
            for event in ['workflow_dispatch', 'unknown']:
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


class MergeGroupDiffTest(unittest.TestCase):
    def test_queue_diff_requires_ancestor_and_uses_complete_integration(self):
        with patch('scripts.check_ci_changes.subprocess.run',
                   return_value=subprocess.CompletedProcess([], 0, entry('README.md'))) as run:
            self.assertEqual(complete_diff('merge_group', 'a' * 40, 'b' * 40), entry('README.md'))
            self.assertEqual(run.call_args_list[0].args[0],
                             ['git', 'merge-base', '--is-ancestor', 'a' * 40, 'b' * 40])
            self.assertEqual(run.call_args_list[1].args[0][-1], 'a' * 40 + '..' + 'b' * 40)

    def test_missing_objects_nonancestor_or_timeout_never_skip_validation(self):
        for error in [OSError('missing git'), subprocess.CalledProcessError(1, 'git'),
                      subprocess.TimeoutExpired('git', 30)]:
            with self.subTest(error=error), patch('scripts.check_ci_changes.subprocess.run',
                                                 side_effect=error):
                self.assertFalse(classify('merge_group', 'a' * 40, 'b' * 40))
                self.assertTrue(supplemental_campaigns('merge_group', 'a' * 40, 'b' * 40))
        with patch('scripts.check_ci_changes.subprocess.run') as run:
            for base, head in [('', 'b' * 40), ('a' * 40, ''), ('bad', 'b' * 40)]:
                self.assertIsNone(complete_diff('merge_group', base, head))
            run.assert_not_called()

    def test_docs_fixup_does_not_hide_earlier_queue_code(self):
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
            (root / 'Source.kt').write_text('code\n')
            git('add', '.')
            git('commit', '-qm', 'code')
            (root / 'README.md').write_text('docs fixup\n')
            git('commit', '-qam', 'docs fixup')
            head = git('rev-parse', 'HEAD')
            original = subprocess.run
            def in_fixture(args, **kw):
                return original(['git', '-C', directory, *args[1:]], **kw)
            with patch('scripts.check_ci_changes.subprocess.run', side_effect=in_fixture):
                self.assertFalse(classify('merge_group', base, head))
                self.assertTrue(supplemental_campaigns('merge_group', base, head))

    def test_ordinary_queue_diff_retains_existing_campaign_selection(self):
        for path, docs, campaigns in [('README.md', True, False),
                                      ('app/src/main/java/org/example/Screen.kt', False, False),
                                      ('.github/workflows/android-ci.yml', False, True)]:
            with self.subTest(path=path), patch('scripts.check_ci_changes.subprocess.run',
                    return_value=subprocess.CompletedProcess([], 0, entry(path))):
                self.assertFalse(classify('merge_group', 'a' * 40, 'b' * 40))
                self.assertEqual(supplemental_campaigns('merge_group', 'a' * 40, 'b' * 40), campaigns)


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
                'app/src/main/AndroidManifest.xml', 'app/src/main/assets/help.md', 'app/src/main/assets/java/Hidden.kt',
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
