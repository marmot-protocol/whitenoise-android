"""Failure reports must survive instrumentation timeout and reverse-cleanup errors."""

import json
from pathlib import Path
import subprocess
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

import device_runner
from fixture_server import Ledger


class RunnerContractTest(unittest.TestCase):
    """Mock only adb failures; servers and their durable ledger remain real."""

    def test_waits_for_delayed_upload_and_acquisition_completion(self):
        """A successful body can precede both durable terminal events by more than 100 ms."""
        with tempfile.TemporaryDirectory() as root:
            ledger = Ledger(root)
            upload = ledger.event(None, "generated", "upload")
            ledger.event(upload, "generated", "upload_bytes", 1040)
            request = ledger.event(None, "generated", "get")
            ledger.event(request, "generated", "body_bytes", 1040)
            first_snapshot = threading.Event()
            snapshot = ledger.snapshot

            def observe():
                """Release finalization only after the waiter has observed incomplete evidence."""
                events = snapshot()
                first_snapshot.set()
                return events

            def finalize():
                """Persist each terminal event independently after the old fixed-delay window."""
                if first_snapshot.wait(2):
                    time.sleep(0.15)
                    ledger.event(request, "generated", "complete")
                    time.sleep(0.05)
                    ledger.event(upload, "generated", "upload_complete")

            thread = threading.Thread(target=finalize)
            thread.start()
            try:
                with patch.object(ledger, "snapshot", side_effect=observe):
                    events, finalized = device_runner.wait_for_ledger_completion(ledger, 0, timeout=2)
            finally:
                thread.join(3)
            self.assertFalse(thread.is_alive())
            self.assertTrue(finalized)
            self.assertEqual(snapshot(), events)
            self.assertEqual({"complete", "upload_complete"}, {e["kind"] for e in events[-2:]})

    def test_missing_terminal_events_time_out_without_discarding_evidence(self):
        """Neither complete body bytes nor one terminal event can qualify unfinished work."""
        for missing in ("complete", "upload_complete", "both"):
            with self.subTest(missing=missing), tempfile.TemporaryDirectory() as root:
                ledger = Ledger(root)
                ledger.event(None, "prior", "get")
                start = len(ledger.snapshot())
                upload = ledger.event(None, "generated", "upload")
                ledger.event(upload, "generated", "upload_bytes", 1040)
                request = ledger.event(None, "generated", "get")
                ledger.event(request, "generated", "body_bytes", 1040)
                if missing == "complete":
                    ledger.event(upload, "generated", "upload_complete")
                if missing == "upload_complete":
                    ledger.event(request, "generated", "complete")
                clock = iter((0, 0, 5))
                with patch.object(device_runner.time, "monotonic", side_effect=lambda: next(clock)), \
                        patch.object(device_runner.time, "sleep") as sleep:
                    events, finalized = device_runner.wait_for_ledger_completion(ledger, start, timeout=5)
                self.assertFalse(finalized)
                sleep.assert_called_once_with(0.01)
                self.assertEqual(ledger.snapshot()[start:], events)

    def test_unknown_length_wait_requires_both_correlated_terminals(self):
        """A probe disconnect and file completion are both required, while other modes still reject two GETs."""
        for missing in (None, "disconnect", "complete", "upload_complete", "acquisition_unavailable"):
            with self.subTest(missing=missing), tempfile.TemporaryDirectory() as root:
                ledger = Ledger(root)
                upload = ledger.event(None, "generated", "upload")
                ledger.event(upload, "generated", "upload_bytes", 4 * 1024 * 1024 + 16)
                if missing != "upload_complete":
                    ledger.event(upload, "generated", "upload_complete")
                ledger.event(None, "control", "hold_unknown_acquisition")
                probe = ledger.event(None, "generated", "get")
                for kind, value in (("range_offset", 0), ("unknown_content_length", 0), ("status", 200)):
                    ledger.event(probe, "generated", kind, value)
                ledger.event(probe, "generated", "body_bytes", 16384)
                if missing != "disconnect":
                    ledger.event(probe, "generated", "disconnect")
                transfer = ledger.event(None, "generated", "get")
                for kind, value in (("range_offset", 0), ("unknown_content_length", 0), ("status", 200),
                                    ("body_bytes", 2 * 1024 * 1024), ("held", 2 * 1024 * 1024)):
                    ledger.event(transfer, "generated", kind, value)
                ledger.event(None, "control", "release_acquisition")
                ledger.event(transfer, "generated", "body_bytes", 2 * 1024 * 1024 + 16)
                if missing != "complete":
                    ledger.event(transfer, "generated", "complete")
                if missing != "acquisition_unavailable":
                    ledger.event(None, "control", "acquisition_unavailable")
                events, finalized = device_runner.wait_for_unknown_length_completion(ledger, 0, timeout=0)
                self.assertEqual(missing is None, finalized)
                self.assertEqual(ledger.snapshot(), events)
                self.assertFalse(device_runner.wait_for_ledger_completion(ledger, 0, timeout=0)[1])

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

        with tempfile.TemporaryDirectory() as root, patch.object(device_runner, "adb_command", side_effect=adb), \
                patch.object(device_runner.subprocess, "run", side_effect=subprocess.TimeoutExpired("logcat", 10)):
            output = Path(root) / "report.json"
            with self.assertRaises(RuntimeError):
                device_runner.run("adb", "emulator-5554", Path(root) / "server", output)
            report = json.loads(output.read_text())
            self.assertEqual("TimeoutExpired", report["failure_class"])
            self.assertTrue(report["failure_diagnostics"]["timed_out"])
            self.assertEqual("instrumentation", report["failure_diagnostics"]["stage"])
            self.assertEqual("unavailable", report["failure_diagnostics"]["crash_capture"])
            self.assertIn("ledger", report)
            self.assertTrue(report["reverse_cleanup_failed"])
            self.assertFalse(report["qualified"])

    def test_failed_instrumentation_keeps_redacted_diagnostics_and_never_qualifies(self):
        """Distinguish a crash from missing measurements without leaking captured messages or another process."""
        secret = "PRIVATE-FIXTURE-CONTENT"
        def adb(binary, serial, *args):
            if args[:3] == ("shell", "getprop", "ro.kernel.qemu"):
                return "1"
            if args[:4] == ("shell", "pm", "list", "packages"):
                return "package:" + device_runner.APP
            if args[:3] == ("shell", "am", "instrument"):
                raise subprocess.CalledProcessError(1, [binary, "-s", serial, *args],
                    output="INSTRUMENTATION_RESULT: shortMsg=Process crashed.\n" + secret, stderr=secret)
            return ""
        raw = ("FATAL EXCEPTION: main\nProcess: unrelated.app, PID: 10\njava.lang.OutOfMemoryError: " + secret +
               "\nFATAL EXCEPTION: main\nProcess: " + device_runner.APP +
               ", PID: 20\njava.lang.IllegalStateException: " + secret)
        with tempfile.TemporaryDirectory() as root, patch.object(device_runner, "adb_command", side_effect=adb), \
                patch.object(device_runner.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, raw)) as capture, \
                patch.object(device_runner, "wait_for_ledger_completion", return_value=([], False)):
            output = Path(root) / "report.json"
            with self.assertRaises(RuntimeError):
                device_runner.run("chosen-adb", "emulator-5554", Path(root) / "server", output)
            report = json.loads(output.read_text())
            self.assertEqual("CalledProcessError", report["failure_class"])
            self.assertEqual("process-crashed", report["failure_diagnostics"]["subprocess_status"])
            self.assertEqual(1, report["failure_diagnostics"]["adb_returncode"])
            self.assertTrue(report["failure_diagnostics"]["recent_app_crash"])
            self.assertEqual("java.lang.IllegalStateException", report["failure_diagnostics"]["recent_app_crash_class"])
            self.assertEqual([], report["metrics"])
            self.assertFalse(report["qualified"])
            self.assertFalse(report["reverse_cleanup_failed"])
            self.assertNotIn(secret, output.read_text())
            self.assertNotIn("unrelated.app", output.read_text())
            self.assertEqual(["chosen-adb", "-s", "emulator-5554", "logcat", "-b", "crash", "-d", "-t", "100", "-v", "brief"],
                             capture.call_args.args[0])
            self.assertEqual(10, capture.call_args.kwargs["timeout"])

    def test_failure_diagnostics_do_not_capture_physical_or_noninstrumentation_logs(self):
        """The diagnostic opt-in stays narrower than the runner's physical-fixture contract."""
        for physical, stage in ((True, "instrumentation"), (False, "reverse-binding")):
            with self.subTest(physical=physical, stage=stage), patch.object(device_runner.subprocess, "run") as capture:
                error = subprocess.CalledProcessError(1, ["PRIVATE-COMMAND"], stderr="PRIVATE-STDERR")
                details = device_runner.failure_diagnostics(error, stage, "adb", "emulator-5554", physical)
                capture.assert_not_called()
                self.assertEqual("unknown", details["subprocess_status"])
                self.assertNotIn("PRIVATE", json.dumps(details))
                self.assertNotIn("recent_app_crash", details)

    def test_rejects_physical_devices_before_any_adb_call(self):
        """Physical-device rejection precedes even read-only adb interaction."""
        with patch.object(device_runner, "adb_command", side_effect=AssertionError("must not contact device")):
            with self.assertRaises(ValueError):
                device_runner.run("adb", "PHYSICAL000TEST", Path("unused"), Path("unused"))


if __name__ == "__main__":
    unittest.main()
