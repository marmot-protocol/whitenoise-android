"""Failure-preserving, numerical-only runner diagnostics for a lost emulator."""

import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

from scripts import maestro_runtime as runtime


class RunnerMemoryTest(unittest.TestCase):
    def test_retains_oom_evidence_without_unknown_fields_or_raw_values(self):
        samples = {
            '/proc/meminfo': 'MemTotal: 8192 kB\nMemAvailable: 512 kB\ncredential: secret\nSwapFree: invalid\n',
            '/proc/vmstat': 'oom_kill 3\ncommand_line private-command\n',
            '/sys/fs/cgroup/memory.events': 'oom 2\noom_kill 1\nunknown 123\n',
        }
        original = Path.read_text
        def read(path, *args, **kwargs):
            return samples[str(path)] if str(path) in samples else original(path, *args, **kwargs)
        with tempfile.TemporaryDirectory() as temporary, patch.object(Path, 'read_text', read):
            output = Path(temporary)
            runtime.runner_memory_snapshot(output, 'before')
            record = json.loads((output / 'runner-memory-before.json').read_text())
        self.assertEqual(record['memory_kib']['values'], {'MemTotal': 8192, 'MemAvailable': 512})
        self.assertEqual(record['kernel']['values'], {'oom_kill': 3})
        self.assertEqual(record['cgroup']['values'], {'oom': 2, 'oom_kill': 1})
        self.assertNotIn('secret', json.dumps(record))
        self.assertNotIn('private-command', json.dumps(record))
        self.assertNotIn('unknown', json.dumps(record))

    def test_missing_host_counters_do_not_replace_a_failed_ui_exit(self):
        failed = subprocess.CompletedProcess([], 1)
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            with patch.object(runtime, 'invoke_ui', return_value=failed), \
                    patch.object(Path, 'read_text', side_effect=PermissionError):
                result = runtime.run_ui('folders-delete-confirm', directory)
            self.assertIs(result, failed)
            for phase in ['before', 'after']:
                record = json.loads((directory / f'runner-memory-{phase}.json').read_text())
                self.assertTrue(all(record[kind] == {'available': False, 'values': {}}
                                    for kind in ['memory_kib', 'kernel', 'cgroup']))

    def test_diagnostic_write_failure_does_not_replace_the_original_timeout(self):
        timeout = subprocess.TimeoutExpired('maestro', 120)
        with tempfile.TemporaryDirectory() as temporary, \
                patch.object(runtime, 'invoke_ui', side_effect=timeout), \
                patch.object(Path, 'write_text', side_effect=OSError):
            with self.assertRaises(subprocess.TimeoutExpired) as observed:
                runtime.run_ui('folders-delete-confirm', Path(temporary))
        self.assertIs(observed.exception, timeout)


class FixtureDiagnosticTimingTest(unittest.TestCase):
    def exercise(self, directory, ui_failure=False, diagnostic_failure=False, native_failure=False, native_hang=False):
        name = 'inbound-share-document-warm-resume'
        generation = 'a' * 32
        finished = False
        waits = 0
        process = Mock(returncode=0)
        process.poll.side_effect = lambda: 0 if finished else None
        def wait(**kwargs):
            nonlocal finished, waits
            waits += 1
            if native_hang and waits == 1:
                raise subprocess.TimeoutExpired("instrumentation", 60)
            finished = True
            (directory / 'instrumentation.txt').write_text('OK (1 test)')
        process.wait.side_effect = wait
        def command(arguments, **kwargs):
            if 'disable' in arguments:
                return 'Component new state: disabled'
            if arguments[-3:] == ['pm', 'clear', runtime.PACKAGE]:
                return 'Success'
            if 'logcat' in arguments:
                self.assertTrue(finished, 'Native verifier must finish before diagnostic read')
                if diagnostic_failure:
                    raise subprocess.TimeoutExpired(arguments, 30)
                return 'MaestroShareProof: shelves=false copies=true removed=true ownership=false'
            for flag in ['ready', 'closed', 'verified']:
                if arguments[-1].endswith(f'/{flag}.json'):
                    row = {'generation': generation, flag: True}
                    if flag == 'ready':
                        row.update(accounts=3, fixture=runtime.CASES[name]['fixture'], uiObserver='maestro')
                    if flag == 'verified':
                        row['shareImportVerified'] = not native_failure
                    return json.dumps(row)
            if arguments[-1].endswith('/setup.json'):
                self.assertFalse(finished, 'Setup snapshot must precede teardown/deletion')
                return '{}'
            return ''
        with patch.object(runtime, 'command', side_effect=command), \
                patch.object(runtime.subprocess, 'Popen', return_value=process), \
                patch.object(runtime, 'run_ui', return_value=subprocess.CompletedProcess([], 1 if ui_failure else 0)), \
                patch.object(runtime, 'ui_result', return_value=1):
            return runtime.run_fixture(name, directory, generation)

    def test_retains_native_failure_diagnostic_after_verification_finishes(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            result = self.exercise(directory, native_failure=True)
            self.assertIn('shelves=false', (directory / 'emulator-errors.txt').read_text())
            self.assertIn('inbound share', result['cleanup_failure'])
            self.assertFalse(result['passed'])
            self.assertTrue(result['cleanup_safe'])

    def test_optional_diagnostic_timeout_preserves_original_ui_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            result = self.exercise(Path(temporary), ui_failure=True, diagnostic_failure=True)
        self.assertEqual(result['failure'], 'ValueError: Maestro exit 1')
        self.assertEqual(result['diagnostic_failures'], ['emulator-errors.txt: TimeoutExpired'])
        self.assertFalse(result['passed'])
        self.assertTrue(result['cleanup_safe'])

    def test_hung_native_verifier_remains_unsafe_and_logs_follow_termination(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            result = self.exercise(directory, native_hang=True)
            self.assertIn('MaestroShareProof', (directory / 'emulator-errors.txt').read_text())
        self.assertIn('TimeoutExpired', result['cleanup_failure'])
        self.assertFalse(result['passed'])
        self.assertFalse(result['cleanup_safe'])

    def test_optional_diagnostic_timeout_does_not_invalidate_completed_proof(self):
        with tempfile.TemporaryDirectory() as temporary:
            result = self.exercise(Path(temporary), diagnostic_failure=True)
        self.assertTrue(result['passed'])
        self.assertTrue(result['cleanup_safe'])
        self.assertEqual(result['diagnostic_failures'], ['emulator-errors.txt: TimeoutExpired'])


if __name__ == '__main__':
    unittest.main()
