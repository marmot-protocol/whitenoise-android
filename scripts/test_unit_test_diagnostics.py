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

    def run_fake(self, source, seconds=2, extra=(), diagnostic_error=False, cleanup_error=False, cleanup_grace=0.1):
        # The production CLI is a dedicated supervisor; host fixtures use that
        # same isolation so the test runner's foreign processes cannot be adopted.
        module_root = str(Path(runner.__file__).resolve().parent.parent)
        invoke = ('import sys,time; sys.path.insert(0,' + repr(module_root) + '); '
                  'from pathlib import Path; from scripts import run_unit_tests_with_diagnostics as r; ' +
                  ('r.collect_diagnostics=lambda *_: (_ for _ in ()).throw(OSError("synthetic diagnostic error")); ' if diagnostic_error else '') +
                  ('real_cleanup=r.cleanup; r.cleanup=lambda *args: (real_cleanup(*args), (_ for _ in ()).throw(OSError("synthetic cleanup error")))[0]; ' if cleanup_error else '') +
                  'raise SystemExit(r.supervise(' + repr([sys.executable, '-c', source, *extra]) +
                  ',time.monotonic()+' + repr(seconds) + ',Path(' + repr(str(self.directory)) +
                  '),diagnostic_budget=0.15,cleanup_grace=' + repr(cleanup_grace) + '))')
        return subprocess.run([sys.executable, '-c', invoke], timeout=8, check=False).returncode


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
        self.assertEqual([p for p in runner.process_snapshot().values() if p['session'] == metadata['session']], [])
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
        source = ('import subprocess,sys,signal; signal.signal(signal.SIGTERM,signal.SIG_IGN); subprocess.Popen([sys.executable,"-c",'
                  '"import signal,time; signal.signal(signal.SIGTERM,signal.SIG_IGN); time.sleep(30)"])')
        self.assertEqual(self.run_fake(source, cleanup_grace=1), 0)
        metadata = json.loads((self.directory / 'invocation.json').read_text())
        self.assertEqual([p for p in runner.process_snapshot().values() if p['session'] == metadata['session']], [])

    def test_signal_owned_rejects_changed_process_identity(self):
        with mock.patch.object(runner.os, 'pidfd_open', return_value=10), \
             mock.patch.object(runner.os, 'close'), \
             mock.patch.object(runner.InvocationProcesses, 'processes', return_value=[{'pid': 99, 'start': 'new'}]), \
             mock.patch.object(runner.signal, 'pidfd_send_signal') as send:
            runner.signal_owned({'pid': 99, 'start': 'old'}, runner.InvocationProcesses(mock.Mock()), signal.SIGKILL)
        send.assert_not_called()

    def test_directory_io_failure_never_launches_a_child(self):
        # A file blocks diagnostics-directory creation before any child is launched.
        blocked = self.directory / 'blocked'
        blocked.write_text('file')
        with mock.patch.object(runner.subprocess, 'Popen') as launch:
            with self.assertRaises(FileExistsError):
                runner.supervise(['not-run'], time.monotonic() + 1, blocked)
        launch.assert_not_called()

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
            self.assertEqual([p for p in runner.process_snapshot().values() if p['session'] == metadata['session']], [])
        finally:
            if child.poll() is None:
                child.kill()
                child.wait()


    def test_setsid_descendant_is_owned_and_foreign_same_command_is_excluded(self):
        marker = self.directory / 'worker.json'
        child_code = ('import os,json,pathlib,time,signal; os.setsid(); '
                      'signal.signal(signal.SIGTERM,signal.SIG_IGN); '
                      f'pathlib.Path({str(marker)!r}).write_text(json.dumps(dict(pid=os.getpid(),sid=os.getsid(0)))); '
                      'time.sleep(30)')
        source = f'import subprocess,sys,time; subprocess.Popen([sys.executable,"-c",{child_code!r}]); time.sleep(30)'
        foreign = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(30)',
                                    'GradleWorkerMain', 'Gradle Test Executor 1'], start_new_session=True)
        try:
            self.assertEqual(self.run_fake(source, 2), 124)
            process = json.loads(marker.read_text())
            metadata = json.loads((self.directory / 'invocation.json').read_text())
            self.assertNotEqual(process['sid'], metadata['session'])
            owned = json.loads((self.directory / 'processes.json').read_text())
            self.assertIn(process['pid'], [p['pid'] for p in owned])
            self.assertNotIn(foreign.pid, [p['pid'] for p in owned])
            self.assertNotIn(process['pid'], runner.process_snapshot())
            self.assertIsNone(foreign.poll())
            self.assertTrue(all({'pid', 'ppid', 'session', 'start'} <= p.keys() for p in owned))
        finally:
            foreign.kill()
            foreign.wait()

    def test_setsid_orphan_is_cleaned_after_immediate_wrapper_exit(self):
        marker = self.directory / 'orphan'
        code = ('import os,pathlib,signal,time; os.setsid(); '
                'signal.signal(signal.SIGTERM,signal.SIG_IGN); '
                f'pathlib.Path({str(marker)!r}).write_text(str(os.getpid())); time.sleep(30)')
        source = f'import subprocess,sys,signal; signal.signal(signal.SIGTERM,signal.SIG_IGN); subprocess.Popen([sys.executable,"-c",{code!r}])'
        self.assertEqual(self.run_fake(source, cleanup_grace=1), 0)
        self.assertTrue(marker.exists())
        self.assertNotIn(int(marker.read_text()), runner.process_snapshot())

    def test_gradle_daemon_is_dumped_after_setsid(self):
        executable = self.directory / 'jcmd'
        executable.write_text('#!/bin/sh\necho synthetic-daemon-stack\n')
        executable.chmod(0o755)
        code = 'import os,time; os.setsid(); time.sleep(30)'
        source = ('import subprocess,sys,time; subprocess.Popen([sys.executable,"-c",'
                  f'{code!r},"org.gradle.launcher.daemon.bootstrap.GradleDaemon"]); time.sleep(30)')
        with mock.patch.dict(os.environ, {'PATH': str(self.directory) + os.pathsep + os.environ['PATH']}):
            self.assertEqual(self.run_fake(source, 2), 124)
        self.assertIn('synthetic-daemon-stack', (self.directory / 'thread-dumps.txt').read_text())

    def test_unrelated_process_cannot_be_owned_by_name(self):
        child = mock.Mock(pid=123)
        child.poll.return_value = 0
        tracker = runner.InvocationProcesses(child)
        snapshot = {999: {'pid': 999, 'ppid': 1, 'start': '1', 'session': 999,
                          'command': 'GradleWorkerMain Gradle Test Executor', 'state': 'S'}}
        with mock.patch.object(runner, 'process_snapshot', return_value=snapshot):
            self.assertEqual(tracker.processes(), [])



    def test_scrubbed_environment_setsid_orphan_is_reaped(self):
        marker = self.directory / 'scrubbed'
        code = ('import os,pathlib,signal,time; os.setsid(); '
                'signal.signal(signal.SIGTERM,signal.SIG_IGN); '
                f'pathlib.Path({str(marker)!r}).write_text(str(os.getpid())); time.sleep(30)')
        source = f'import subprocess,sys,signal; signal.signal(signal.SIGTERM,signal.SIG_IGN); subprocess.Popen([sys.executable,"-c",{code!r}],env={{}})'
        self.assertEqual(self.run_fake(source, cleanup_grace=1), 0)
        self.assertNotIn(int(marker.read_text()), runner.process_snapshot())

    def test_term_handler_late_setsid_fork_is_drained(self):
        marker = self.directory / 'late'
        ready = self.directory / 'term-handler-ready'
        late = ('import os,pathlib,signal,time; os.setsid(); '
                'signal.signal(signal.SIGTERM,signal.SIG_IGN); '
                f'pathlib.Path({str(marker)!r}).write_text(str(os.getpid())); time.sleep(30)')
        source = ('import subprocess,sys,signal,time,pathlib; '
                  f'signal.signal(signal.SIGTERM,lambda *_: (signal.signal(signal.SIGTERM,signal.SIG_IGN), subprocess.Popen([sys.executable,"-c",{late!r}],env={{}}))); '
                  f'pathlib.Path({str(ready)!r}).touch(); time.sleep(30)')
        self.assertEqual(self.run_fake(source, 2, cleanup_grace=1), 124)
        self.assertTrue(ready.exists(), 'The handler must be installed before the deadline')
        if marker.exists():
            self.assertNotIn(int(marker.read_text()), runner.process_snapshot())
        self.assertTrue(marker.exists(), 'TERM handler must actually exercise the late fork')

    def test_preexisting_child_is_refused_before_launch(self):
        foreign = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(30)'])
        try:
            with mock.patch.object(runner.subprocess, 'Popen') as launch:
                with self.assertRaisesRegex(RuntimeError, 'pre-existing'):
                    runner.supervise(['not-run'], time.monotonic() + 1, self.directory)
            launch.assert_not_called()
            self.assertIsNone(foreign.poll())
        finally:
            foreign.kill()
            foreign.wait()



    def test_cleanup_fails_closed_when_known_process_survives_kill_budget(self):
        invocation = mock.Mock()
        invocation.processes.return_value = [{'pid': 99, 'start': 'old'}]
        with mock.patch.object(runner, 'signal_owned') as send, \
             mock.patch.object(runner.time, 'monotonic', side_effect=[0, 1, 6]):
            with self.assertRaisesRegex(TimeoutError, 'remained alive'):
                runner.cleanup(invocation, mock.Mock(), grace=0)
        send.assert_called_once_with({'pid': 99, 'start': 'old'}, invocation, signal.SIGKILL)
        self.assertEqual(invocation.reap.call_count, 2)

    def test_thread_dump_is_discarded_when_numeric_pid_identity_changes(self):
        process = {'pid': 99, 'start': 'old', 'command': 'GradleWorkerMain Gradle Test Executor'}
        invocation = mock.Mock()
        invocation.processes.side_effect = [[process], [process], []]
        def write_fake_dump(*args, **kwargs):
            kwargs['stdout'].write('untrusted-numeric-pid-stack')
        with mock.patch.object(runner.subprocess, 'run', side_effect=write_fake_dump):
            runner.collect_diagnostics(invocation, self.directory)
        text = (self.directory / 'thread-dumps.txt').read_text()
        self.assertNotIn('untrusted-numeric-pid-stack', text)
        self.assertIn('target identity changed or exited', text)



    def test_adopted_zombie_is_known_and_reaped_without_signaling(self):
        child = mock.Mock(pid=123)
        child.poll.return_value = 0
        invocation = runner.InvocationProcesses(child)
        zombie = {'pid': 999, 'ppid': os.getpid(), 'start': '1', 'session': 999,
                  'command': '', 'state': 'Z'}
        with mock.patch.object(runner, 'process_snapshot', return_value={999: zombie}), \
             mock.patch.object(runner.os, 'waitpid', return_value=(999, 0)) as reap:
            self.assertEqual(invocation.processes(), [])
            invocation.reap()
        self.assertEqual(invocation.known, {999: '1'})
        reap.assert_called_once_with(999, os.WNOHANG)



    def test_reused_pid_for_new_adopted_child_updates_start_identity(self):
        child = mock.Mock(pid=123)
        child.poll.return_value = 0
        invocation = runner.InvocationProcesses(child)
        invocation.known[999] = 'old'
        process = {'pid': 999, 'ppid': os.getpid(), 'start': 'new', 'session': 999,
                   'command': '', 'state': 'S'}
        with mock.patch.object(runner, 'process_snapshot', return_value={999: process}):
            self.assertEqual(invocation.processes(), [process])
        self.assertEqual(invocation.known[999], 'new')

    def test_reused_foreign_pid_is_neither_owned_nor_reaped(self):
        child = mock.Mock(pid=123)
        child.poll.return_value = 0
        invocation = runner.InvocationProcesses(child)
        invocation.known[999] = 'old'
        foreign = {'pid': 999, 'ppid': 1, 'start': 'new', 'session': 999,
                   'command': 'GradleWorkerMain Gradle Test Executor', 'state': 'S'}
        with mock.patch.object(runner, 'process_snapshot', return_value={999: foreign}), \
             mock.patch.object(runner.os, 'waitpid') as reap:
            self.assertEqual(invocation.processes(), [])
            invocation.reap()
        reap.assert_not_called()



    def test_postlaunch_diagnostic_error_still_cleans_owned_processes_and_returns_124(self):
        self.assertEqual(self.run_fake('import time; time.sleep(30)', 0.2,
                                       diagnostic_error=True), 124)
        metadata = json.loads((self.directory / 'invocation.json').read_text())
        self.assertEqual(metadata['result'], 'deadline_expired')
        self.assertEqual(metadata['diagnostic_error'], 'synthetic diagnostic error')
        self.assertFalse(any(p['session'] == metadata['session']
                             for p in runner.process_snapshot().values()))

    def test_cleanup_error_records_truthful_metadata_and_stays_nonzero(self):
        self.assertEqual(self.run_fake('import time; time.sleep(30)', 0.2,
                                       cleanup_error=True), 1)
        metadata = json.loads((self.directory / 'invocation.json').read_text())
        self.assertEqual(metadata['cleanup_error'], 'synthetic cleanup error')
        self.assertEqual(metadata['cleanup_residual_processes'], [])

    def test_final_child_wait_uses_only_the_remaining_kill_drain_budget(self):
        invocation = mock.Mock()
        invocation.processes.return_value = []
        child = mock.Mock()
        with mock.patch.object(runner.time, 'monotonic', side_effect=[10, 14]):
            runner.cleanup(invocation, child, grace=0)
        child.wait.assert_called_once_with(timeout=1)


if __name__ == '__main__':
    unittest.main()
