"""Failure receipts expose only bounded allowlisted facts, never raw device or subprocess output."""

import json
import subprocess
import unittest
from unittest.mock import patch

import device_runner


class RunnerDiagnosticsTest(unittest.TestCase):
    """Keep diagnostics read-only, explicit-device, privacy safe and independent of qualification."""

    def test_mapping_receipt_matches_both_ends_without_exporting_other_owners(self):
        """A correct device listener aimed at the wrong host port is distinguished from the expected mapping."""
        listing = "UsbFfs tcp:45001 tcp:32001\nUsbFfs tcp:45002 tcp:32003\nPRIVATE tcp:60000 tcp:61000\n"
        with patch.object(device_runner, "adb_command", return_value=listing) as adb:
            details = device_runner.reverse_mapping_diagnostics("chosen-adb", "emulator-5554", [45001, 45002], [32001, 32002])
        self.assertEqual({"available": True, "blob": True, "relay": False}, details)
        adb.assert_called_once_with("chosen-adb", "emulator-5554", "reverse", "--list", timeout=10)
        self.assertNotIn("PRIVATE", json.dumps(details))
        self.assertNotIn("45001", json.dumps(details))

    def test_unavailable_mapping_receipt_never_throws_or_discards_the_original_failure(self):
        """Timeout and transport errors remain diagnostic unavailability rather than another runner exception."""
        for error in (subprocess.TimeoutExpired("PRIVATE", 10),
                      subprocess.CalledProcessError(1, "PRIVATE", stderr="PRIVATE"), OSError("PRIVATE")):
            with self.subTest(error=type(error).__name__), patch.object(device_runner, "adb_command", side_effect=error):
                details = device_runner.reverse_mapping_diagnostics("chosen-adb", "emulator-5554", [45001], [32001])
                self.assertEqual({"available": False, "blob": False, "relay": False}, details)

    def test_readiness_receipt_uses_only_known_state_and_exact_fixture_registration(self):
        """Neither an unrelated package nor an arbitrary shell response can masquerade as readiness."""
        component = "instrumentation:" + device_runner.APP + ".test/androidx.test.runner.AndroidJUnitRunner"
        for values, expected in (
            (["device\n", "1\n", component + " (target=" + device_runner.APP + ")\nPRIVATE"],
             {"state": "device", "boot_completed": True, "fixture_registered": True}),
            (["PRIVATE", "0", "instrumentation:PRIVATE/AndroidJUnitRunner"],
             {"state": "unknown", "boot_completed": False, "fixture_registered": False}),
        ):
            with self.subTest(expected=expected), patch.object(device_runner, "adb_command", side_effect=values) as adb:
                details = device_runner.device_failure_diagnostics("chosen-adb", "emulator-5554")
                self.assertEqual(expected, details)
                self.assertNotIn("PRIVATE", json.dumps(details))
                self.assertTrue(all(call.args[:2] == ("chosen-adb", "emulator-5554") for call in adb.call_args_list))
                self.assertTrue(all(call.kwargs == {"timeout": 10} for call in adb.call_args_list))
                self.assertEqual([("get-state",), ("shell", "getprop", "sys.boot_completed"),
                                  ("shell", "pm", "list", "instrumentation")], [c.args[2:] for c in adb.call_args_list])

    def test_failed_readiness_reads_are_explicitly_unavailable(self):
        """A failed read is unknown evidence and does not become a false device-health claim."""
        with patch.object(device_runner, "adb_command", side_effect=subprocess.TimeoutExpired("PRIVATE", 10)):
            details = device_runner.device_failure_diagnostics("chosen-adb", "emulator-5554")
        self.assertEqual({"state": None, "boot_completed": None, "fixture_registered": None}, details)

    def test_exit_255_and_cleanup_classification_do_not_export_error_messages(self):
        """Only fixed categories and allowlisted exception classes survive private subprocess failures."""
        for message, status, exception in (
            ("Can't find service: activity", "activity-service-missing", "unknown"),
            ("Failure calling service activity", "activity-service-failed", "unknown"),
            ("java.lang.SecurityException: PRIVATE", "security-exception", "java.lang.SecurityException"),
            ("error: listener 'PRIVATE' not found", "listener-error", "unknown"),
            ("PRIVATE", "unknown", "unknown"),
        ):
            with self.subTest(status=status), patch.object(device_runner.subprocess, "run") as capture:
                error = subprocess.CalledProcessError(255, ["PRIVATE"], output="PRIVATE", stderr=message + " PRIVATE")
                details = device_runner.failure_diagnostics(error, "reverse-cleanup", "chosen-adb", "emulator-5554", False)
                capture.assert_not_called()
                self.assertEqual(255, details["adb_returncode"])
                self.assertEqual(status, details["subprocess_status"])
                self.assertEqual(exception, details["command_exception_class"])
                self.assertNotIn("PRIVATE", json.dumps(details))


if __name__ == "__main__":
    unittest.main()
