"""Exercise the actual instrumentation retention conditions and named-step Bash."""

import os
from pathlib import Path
import re
import subprocess
import tempfile
import textwrap
from types import SimpleNamespace
import unittest


WORKFLOW = Path(__file__).resolve().parents[1] / '.github/workflows/android-instrumented.yml'
REVISION = 'Record instrumentation checkout revision'
UPLOAD = 'Retain built-revision instrumentation APKs'
APP = 'app/build/outputs/apk/devZapstore/debug/app-devZapstore-universal-debug.apk'
TEST = 'app/build/outputs/apk/androidTest/devZapstore/debug/app-devZapstore-debug-androidTest.apk'
MARKER = 'app/build/outputs/apk/source-revision.txt'


def named_step(workflow, name):
    """Bound extraction at the next step, so later YAML cannot become Bash."""
    marker = f'      - name: {name}\n'
    if workflow.count(marker) != 1:
        raise AssertionError(f'expected exactly one {name!r} step')
    remainder = workflow.split(marker, 1)[1]
    boundary = re.search(r'\n(?:      - |  [a-zA-Z][a-zA-Z0-9_-]*:\n)', remainder)
    return marker + (remainder[:boundary.start()] if boundary else remainder)


def expression_value(expression, event, selected, status, outcome='skipped'):
    """Evaluate trusted workflow expressions against explicit GitHub status fixtures."""
    expression = expression.replace('&&', ' and ').replace('||', ' or ')
    expression = re.sub(r'!(?!=)', 'not ', expression)
    values = {
        'github': SimpleNamespace(event_name=event),
        'inputs': SimpleNamespace(media_sandbox_only=selected),
        'job': SimpleNamespace(status=status),
        'steps': SimpleNamespace(instrumentation_apk_revision=SimpleNamespace(outcome=outcome)),
        'success': lambda: status == 'success',
        'failure': lambda: status == 'failure',
        'cancelled': lambda: status == 'cancelled',
    }
    return bool(eval(expression, {'__builtins__': {}}, values))


class InstrumentedArtifactRetentionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = WORKFLOW.read_text()
        cls.revision = named_step(cls.workflow, REVISION)
        cls.upload = named_step(cls.workflow, UPLOAD)
        cls.conditions = {
            name: re.search(r'^        if: (.+)$', step, re.MULTILINE).group(1)
            for name, step in [('revision', cls.revision), ('upload', cls.upload)]
        }
        cls.shell = textwrap.dedent(cls.revision.split('        run: |\n', 1)[1])
        cls.failed_environment = re.search(r'RETAIN_FAILED_MEDIA: \$\{\{ (.+) \}\}', cls.revision).group(1)

    def admitted(self, name, event, selected, status, outcome='skipped'):
        return expression_value(self.conditions[name], event, selected, status, outcome)

    def run_shell(self, root, selected=True, status='failure'):
        env = dict(os.environ)
        env['RETAIN_FAILED_MEDIA'] = str(expression_value(
            self.failed_environment, 'workflow_dispatch', selected, status)).lower()
        return subprocess.run(['bash', '-e', '-o', 'pipefail', '-c', self.shell], cwd=root,
                              env=env, capture_output=True, text=True, check=False, timeout=5)

    @staticmethod
    def create_source(root):
        subprocess.run(['git', '-c', 'core.hooksPath=/dev/null', 'init', '-q'], cwd=root, check=True)
        subprocess.run(['git', '-c', 'core.hooksPath=/dev/null', '-c', 'commit.gpgsign=false',
                        '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
                        'commit', '--allow-empty', '-qm', 'Fixture source revision'], cwd=root, check=True)
        return subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True)

    @staticmethod
    def put(root, name):
        target = root / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(b'host-only presence fixture; never an APK qualification')

    def test_retention_contract_is_reachable_in_existing_ci_command(self):
        workflow = (WORKFLOW.parent / 'android-ci.yml').read_text()
        block = named_step(workflow, 'Test instrumented dispatch and required cases')
        commands = textwrap.dedent(block.split('        run: |\n', 1)[1]).strip().splitlines()
        self.assertEqual(commands, [
            'python3 -m unittest scripts/test_run_android_instrumented_dispatch.py '
            'scripts/test_hosted_startup_trace.py scripts/test_instrumented_artifact_retention.py',
            'python3 -m unittest scripts/test_outbound_share_visibility.py',
            'python3 -m unittest scripts/test_select_android_cmdline_tools.py',
            'python3 -m unittest scripts/test_check_instrumented_required_cases.py',
            'python3 -m unittest scripts/test_select_instrumented_lanes.py',
        ])
        self.assertEqual(workflow.count('scripts/test_instrumented_artifact_retention.py'), 1)
        self.assertNotIn('offline-zsp:', block)

    def test_status_and_event_matrix_preserves_success_and_scopes_failure(self):
        for event in ['pull_request', 'push', 'schedule', 'workflow_dispatch', 'merge_group', 'workflow_call']:
            for selected in [False, True]:
                for status in ['success', 'failure', 'cancelled']:
                    with self.subTest(event=event, selected=selected, status=status):
                        expected = status == 'success' or (
                            status == 'failure' and event == 'workflow_dispatch' and selected)
                        self.assertEqual(self.admitted('revision', event, selected, status), expected)
                        self.assertEqual(self.admitted('upload', event, selected, status, 'success'), expected)
                        for bad_outcome in ['failure', 'skipped', 'cancelled']:
                            self.assertEqual(self.admitted('upload', event, selected, status, bad_outcome),
                                             status == 'success')

    def test_named_step_shell_and_upload_preserve_existing_contract(self):
        self.assertIn('        id: instrumentation_apk_revision\n', self.revision)
        self.assertIn('        shell: bash\n', self.revision)
        self.assertNotIn('      - name:', self.shell)
        self.assertNotIn('mkdir', self.shell)
        self.assertNotIn('continue-on-error', self.revision + self.upload)
        for preserved in ['name: android-instrumented-apks-${{ github.sha }}',
                          'app/build/outputs/apk/**/*.apk', MARKER,
                          'scripts/outbound-share-fixture/build/fixture.apk',
                          'scripts/outbound-share-fixture/build/control.apk',
                          'if-no-files-found: error', 'retention-days: 7']:
            self.assertIn(preserved, self.upload)
        self.assertTrue(self.shell.rstrip().endswith(f'git rev-parse HEAD > {MARKER}'))

    def test_failed_focused_test_with_actual_pair_records_checkout_revision(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = self.create_source(root)
            self.put(root, APP)
            self.put(root, TEST)
            result = self.run_shell(root)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual((root / MARKER).read_text(), source)
            self.assertTrue(self.admitted('upload', 'workflow_dispatch', True, 'failure', 'success'))

    def test_success_shell_keeps_prior_revision_behavior_without_pair_gate(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = self.create_source(root)
            (root / MARKER).parent.mkdir(parents=True)
            result = self.run_shell(root, selected=False, status='success')
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual((root / MARKER).read_text(), source)

    def test_failed_missing_partial_ambiguous_and_wrong_variant_pairs_are_not_retained(self):
        cases = [[], [APP], [TEST], [APP, TEST, APP.replace('app-', 'other-', 1)],
                 [APP, TEST, TEST.replace('app-', 'other-', 1)],
                 [APP.replace('devZapstore', 'devPlay'), TEST],
                 [APP, TEST.replace('devZapstore', 'devPlay')]]
        for paths in cases:
            with self.subTest(paths=paths), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                self.create_source(root)
                for path in paths:
                    self.put(root, path)
                result = self.run_shell(root)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse((root / MARKER).exists())
                self.assertFalse(self.admitted('upload', 'workflow_dispatch', True, 'failure', 'failure'))

    def test_pair_directories_are_not_files_and_old_marker_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.create_source(root)
            (root / APP).mkdir(parents=True)
            self.put(root, TEST)
            (root / MARKER).write_text('old-marker-must-not-authorize-upload\n')
            result = self.run_shell(root)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual((root / MARKER).read_text(), 'old-marker-must-not-authorize-upload\n')
            self.assertFalse(self.admitted('upload', 'workflow_dispatch', True, 'failure', 'failure'))


if __name__ == '__main__':
    unittest.main()
