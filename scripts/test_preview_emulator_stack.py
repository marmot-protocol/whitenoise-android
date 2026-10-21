"""Hosted-only real debugger controls prove that signal collection cannot fabricate or suppress a crash."""

import json
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest

from scripts.preview_emulator_stack import no_core_dump, start_debugger, stop_debugger


class HostStackSignalTest(unittest.TestCase):
    """Use disposable host children; no emulator, app, account or personal device participates."""

    def test_original_signal_delivery_for_handled_and_unhandled_faults(self):
        """An ordinary QEMU wakeup signal must not prevent capture and delivery of the later SIGSEGV."""
        for handled in (False, True):
            with self.subTest(handled=handled), tempfile.TemporaryDirectory() as directory:
                reports = Path(directory)
                source = '''import os,signal,time
from pathlib import Path
canary = "PRIVATE_ARGUMENT_VALUE_MUST_NOT_APPEAR"
received = []
ordinary = []
signal.signal(signal.SIGUSR1, lambda number, frame: ordinary.append(number))
HANDLER
ready = Path(REPORTS) / f"emulator-stack-ready-{os.getpid()}.json"
deadline = time.monotonic() + 25
while not ready.exists():
    if time.monotonic() > deadline:
        raise SystemExit(23)
    time.sleep(.01)
os.kill(os.getpid(), signal.SIGUSR1)
assert ordinary == [signal.SIGUSR1]
os.kill(os.getpid(), signal.SIGSEGV)
assert received == [signal.SIGSEGV]
'''.replace('HANDLER', 'signal.signal(signal.SIGSEGV, lambda number, frame: received.append(number))' if handled else '')
                source = source.replace('REPORTS', repr(str(reports)))
                child = subprocess.Popen([sys.executable, '-c', source], preexec_fn=no_core_dump)
                debugger = None
                try:
                    debugger = start_debugger(child.pid, reports, Path(sys.executable))
                    self.assertEqual(child.wait(timeout=20), 0 if handled else -signal.SIGSEGV)
                    debugger.wait(timeout=10)
                    result = json.loads((reports / f'emulator-stack-{child.pid}.json').read_text())
                    self.assertEqual(result['signal'], 'SIGSEGV')
                    self.assertTrue(result['detached'])
                    self.assertTrue(result['frames'])
                    mapped = [frame['mapping'] for frame in result['frames'] if frame['mapping']]
                    self.assertTrue(mapped)
                    self.assertTrue(all(int(frame['file_offset'], 16) >= 0 for frame in mapped))
                    self.assertTrue(all('/' not in (frame['module'] or '') for frame in mapped))
                    self.assertNotIn('error_type', result)
                    self.assertNotIn('PRIVATE_ARGUMENT_VALUE_MUST_NOT_APPEAR', json.dumps(result))
                    self.assertLessEqual(len(result['frames']), 24)
                finally:
                    if debugger is not None:
                        stop_debugger(debugger)
                    if child.poll() is None:
                        child.kill()
                        child.wait(timeout=5)

    def test_cleanup_detaches_without_stopping_or_signalling_the_live_target(self):
        """A debugger interrupt must leave the owned child alive and making progress without receiving SIGINT."""
        with tempfile.TemporaryDirectory() as directory:
            reports = Path(directory)
            progress = reports / 'progress.txt'
            interrupted = reports / 'unexpected-sigint.txt'
            source = '''import signal,time
from pathlib import Path
progress = Path(PROGRESS)
interrupted = Path(INTERRUPTED)
signal.signal(signal.SIGINT, lambda number, frame: interrupted.write_text("unexpected"))
for index in range(2000):
    progress.write_text(str(index))
    time.sleep(.02)
'''.replace('PROGRESS', repr(str(progress))).replace('INTERRUPTED', repr(str(interrupted)))
            child = subprocess.Popen([sys.executable, '-c', source], preexec_fn=no_core_dump)
            debugger = None
            try:
                debugger = start_debugger(child.pid, reports, Path(sys.executable))
                stop_debugger(debugger)
                before = progress.read_text() if progress.exists() else ''
                deadline = time.monotonic() + 5
                while (not progress.exists() or progress.read_text() == before) and time.monotonic() < deadline:
                    time.sleep(.05)
                self.assertIsNone(child.poll())
                self.assertTrue(progress.exists())
                self.assertNotEqual(progress.read_text(), before)
                self.assertFalse(interrupted.exists())
                result = json.loads((reports / f'emulator-stack-{child.pid}.json').read_text())
                self.assertTrue(result['detached'])
            finally:
                if debugger is not None:
                    stop_debugger(debugger)
                if child.poll() is None:
                    child.terminate()
                    child.wait(timeout=5)


if __name__ == '__main__':
    unittest.main()
