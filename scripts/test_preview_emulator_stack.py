"""Hosted-only real debugger controls prove that signal collection cannot fabricate or suppress a crash."""

import json
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import unittest

from scripts.preview_emulator_stack import no_core_dump, start_debugger, stop_debugger


class HostStackSignalTest(unittest.TestCase):
    """Use disposable host children; no emulator, app, account or personal device participates."""

    def test_original_signal_delivery_for_handled_and_unhandled_faults(self):
        """Handled SIGSEGV arrives once and returns normally; an unhandled signal still terminates at -11."""
        for handled in (False, True):
            with self.subTest(handled=handled), tempfile.TemporaryDirectory() as directory:
                reports = Path(directory)
                source = '''import os,signal,time
from pathlib import Path
canary = "PRIVATE_ARGUMENT_VALUE_MUST_NOT_APPEAR"
received = []
HANDLER
ready = Path(REPORTS) / f"emulator-stack-ready-{os.getpid()}.json"
deadline = time.monotonic() + 25
while not ready.exists():
    if time.monotonic() > deadline:
        raise SystemExit(23)
    time.sleep(.01)
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
                    self.assertNotIn('error_type', result)
                    self.assertNotIn('PRIVATE_ARGUMENT_VALUE_MUST_NOT_APPEAR', json.dumps(result))
                    self.assertLessEqual(len(result['frames']), 24)
                finally:
                    if debugger is not None:
                        stop_debugger(debugger)
                    if child.poll() is None:
                        child.kill()
                        child.wait(timeout=5)


if __name__ == '__main__':
    unittest.main()
