"""Failure-preserving, numerical-only runner diagnostics for a lost emulator."""

import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

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


if __name__ == '__main__':
    unittest.main()
