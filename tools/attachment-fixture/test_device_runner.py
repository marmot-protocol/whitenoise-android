"""Failure reports must survive instrumentation timeout and reverse-cleanup errors."""

import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import device_runner


class RunnerContractTest(unittest.TestCase):
    """Mock only adb failures; servers and their durable ledger remain real."""

    def test_timeout_and_cleanup_failure_preserve_failed_report(self):
        """An adb failure cannot discard the host evidence or become qualification."""
        def adb(_, serial, *args):
            """Fail instrumentation and cleanup independently, after verified emulator setup."""
            self.assertEqual("emulator-5554", serial)
            if args[:3] == ("shell", "getprop", "ro.kernel.qemu"):
                return "1\n"
            if args[:4] == ("shell", "pm", "list", "packages"):
                return "package:" + device_runner.APP + "\n"
            if args[:2] == ("reverse", "--remove"):
                raise subprocess.CalledProcessError(1, "adb")
            if args[:3] == ("shell", "am", "instrument"):
                raise subprocess.TimeoutExpired("adb", 180)
            return ""

        with tempfile.TemporaryDirectory() as root, patch.object(device_runner, "adb_command", side_effect=adb):
            output = Path(root) / "report.json"
            with self.assertRaises(RuntimeError):
                device_runner.run("adb", "emulator-5554", Path(root) / "server", output)
            report = json.loads(output.read_text())
            self.assertEqual("TimeoutExpired", report["failure_class"])
            self.assertIn("ledger", report)
            self.assertTrue(report["reverse_cleanup_failed"])
            self.assertFalse(report["qualified"])

    def test_rejects_physical_devices_before_any_adb_call(self):
        """Physical-device rejection precedes even read-only adb interaction."""
        with patch.object(device_runner, "adb_command", side_effect=AssertionError("must not contact device")):
            with self.assertRaises(ValueError):
                device_runner.run("adb", "46131FDAS003CG", Path("unused"), Path("unused"))


if __name__ == "__main__":
    unittest.main()
