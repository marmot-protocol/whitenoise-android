"""Host-only supervisor regressions; every child is a synthetic Python process."""

import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock

from scripts import run_unit_tests_with_diagnostics as runner


class UnitTestDiagnosticsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.directory = Path(self.temporary.name)
        self.addCleanup(self.temporary.cleanup)

    def run_fake(self, source, seconds=2, extra=()):
        return runner.supervise([sys.executable, '-c', source, *extra],
                                time.monotonic() + seconds, self.directory,
                                diagnostic_budget=0.15, cleanup_grace=0.1)

    def test_preserves_success_and_nonzero_status(self):
        for status in (0, 7):
            with self.subTest(status=status):
                self.assertEqual(self.run_fake(f'raise SystemExit({status})'), status)
                metadata = json.loads((self.directory / 'invocation.json').read_text())
                self.assertEqual(metadata['result'], 'completed')
                self.assertEqual(metadata['child_exit'], status)

    def test_exhausted_job_budget_never_launches_child(self):
        with mock.patch.object(runner.subprocess, 'Popen') as launch:
            result = runner.supervise(['not-run'], time.monotonic() - 1, self.directory)
        launch.assert_not_called()
        self.assertEqual(result, 124)

    def test_nonfinite_deadline_never_launches_child(self):
        for deadline in (float('nan'), float('inf')):
            with mock.patch.object(runner.subprocess, 'Popen') as launch:
                self.assertEqual(runner.supervise(['not-run'], deadline, self.directory), 124)
            launch.assert_not_called()

    def test_deadline_collects_owned_processes_and_kills_term_ignoring_child(self):
        unrelated = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(30)'],
                                     start_new_session=True)
        self.addCleanup(unrelated.wait)
        self.addCleanup(unrelated.kill)
        before = time.monotonic()
        result = self.run_fake('import signal,time; signal.signal(signal.SIGTERM, signal.SIG_IGN); time.sleep(30)', 0.2)
        self.assertEqual(result, 124)
        self.assertLess(time.monotonic() - before, 3)
        self.assertIsNone(unrelated.poll())
        processes = json.loads((self.directory / 'processes.json').read_text())
        self.assertTrue(processes)
        self.assertNotIn(unrelated.pid, [p['pid'] for p in processes])
        metadata = json.loads((self.directory / 'invocation.json').read_text())
        self.assertEqual(runner.session_processes(metadata['session']), [])
        self.assertIn('No invocation-owned', (self.directory / 'thread-dumps.txt').read_text())

    def test_worker_stack_transport_is_bounded(self):
        executable = self.directory / 'jcmd'
        # This fake replaces itself, so timeout owns exactly one synthetic process.
        executable.write_text(f'#!/bin/sh\nexec {sys.executable} -c "import time; time.sleep(30)"\n')
        executable.chmod(0o755)
        with mock.patch.dict(os.environ, {'PATH': str(self.directory) + os.pathsep + os.environ['PATH']}):
            before = time.monotonic()
            result = self.run_fake('import time; time.sleep(30)', 0.2,
                                   ('GradleWorkerMain', 'Gradle Test Executor 1'))
        self.assertEqual(result, 124)
        self.assertLess(time.monotonic() - before, 3)
        self.assertIn('Bounded thread dump unavailable', (self.directory / 'thread-dumps.txt').read_text())

    def test_kotlin_daemon_is_not_reported_as_executor(self):
        self.assertEqual(self.run_fake('import time; time.sleep(30)', 0.2,
                                       ('KotlinCompileDaemon',)), 124)
        self.assertIn('No invocation-owned', (self.directory / 'thread-dumps.txt').read_text())

    def test_reparented_session_child_is_cleaned_after_parent_exit(self):
        # Grandchild retains the owned session after direct parent exits.
        source = ('import subprocess,sys; subprocess.Popen([sys.executable,"-c",'
                  '"import signal,time; signal.signal(signal.SIGTERM,signal.SIG_IGN); time.sleep(30)"])')
        self.assertEqual(self.run_fake(source), 0)
        metadata = json.loads((self.directory / 'invocation.json').read_text())
        self.assertEqual(runner.session_processes(metadata['session']), [])

    def test_signal_owned_rejects_changed_process_identity(self):
        with mock.patch.object(runner.os, 'pidfd_open', return_value=10), \
             mock.patch.object(runner.os, 'close'), \
             mock.patch.object(runner, 'session_processes', return_value=[{'pid': 99, 'start': 'new'}]), \
             mock.patch.object(runner.signal, 'pidfd_send_signal') as send:
            runner.signal_owned({'pid': 99, 'start': 'old'}, 99, signal.SIGKILL)
        send.assert_not_called()

    def test_diagnostic_io_failure_still_cleans_up_and_fails(self):
        with mock.patch.object(runner, 'collect_diagnostics', side_effect=OSError('synthetic error')):
            self.assertEqual(self.run_fake('import time; time.sleep(30)', 0.2), 124)
        metadata = json.loads((self.directory / 'invocation.json').read_text())
        self.assertEqual(metadata['diagnostic_error'], 'synthetic error')
        self.assertEqual(runner.session_processes(metadata['session']), [])

    def test_cli_sigterm_retains_diagnostics_and_signal_exit(self):
        script = Path(runner.__file__).resolve()
        cwd = self.directory
        marker = cwd / 'ready'
        environment = dict(os.environ, UNIT_TEST_DEADLINE_MONOTONIC=str(time.monotonic() + 30))
        source = f'import pathlib,time; pathlib.Path({str(marker)!r}).touch(); time.sleep(30)'
        child = subprocess.Popen([sys.executable, str(script), '--', sys.executable, '-c', source],
                                 cwd=cwd, env=environment)
        try:
            end = time.monotonic() + 3
            while not marker.exists() and time.monotonic() < end:
                time.sleep(0.01)
            self.assertTrue(marker.exists())
            child.send_signal(signal.SIGTERM)
            self.assertEqual(child.wait(timeout=5), 143)
            directory = cwd / 'app/build/reports/unit-test-diagnostics'
            metadata = json.loads((directory / 'invocation.json').read_text())
            self.assertEqual(metadata['result'], 'interrupted')
            self.assertTrue((directory / 'processes.json').exists())
            self.assertEqual(runner.session_processes(metadata['session']), [])
        finally:
            if child.poll() is None:
                child.kill()
                child.wait()


if __name__ == '__main__':
    unittest.main()
