"""Budget breaches must fail qualification even when transport and exact reads succeed."""

import copy
import http.client
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from budget_checker import LIMITS, PHASE_COUNTS, PROFILES, check_budget
import device_runner


def samples():
    """Construct exactly the measured phase shape at every declared ceiling."""
    return [{"phase": phase, "success": True, "payload_bytes": 1024,
             "elapsed_ms": LIMITS["cold_ms" if phase == "received-cold" else "local_ms"],
             "java_peak_bytes": LIMITS["java_peak_bytes"], "native_peak_bytes": LIMITS["native_peak_bytes"]}
            for phase, count in PHASE_COUNTS.items() for _ in range(count)]


class BudgetContractTest(unittest.TestCase):
    """Exercise limits, missing/invalid evidence and the runner's actual failure/report path."""

    def test_every_profile_accepts_samples_at_each_boundary(self):
        """Equality is permitted, including all ten local samples and native reopen."""
        for profile in PROFILES:
            self.assertTrue(check_budget(samples(), profile)["passed"])

    def test_each_phase_and_memory_ceiling_is_enforced(self):
        """A single slow read or excess heap must fail regardless of the other eleven samples."""
        for profile in PROFILES:
            for index in (0, 1, 11):
                for field in ("elapsed_ms", "java_peak_bytes", "native_peak_bytes"):
                    with self.subTest(profile=profile, index=index, field=field):
                        data = samples()
                        data[index][field] += 1
                        self.assertFalse(check_budget(data, profile)["passed"])

    def test_invalid_and_missing_evidence_cannot_pass(self):
        """Reject nonfinite/boolean/negative metrics, unsuccessful reads and wrong phase counts."""
        for value in (None, True, float("nan"), float("inf"), -1, "1", 10**400):
            for field in ("elapsed_ms", "java_peak_bytes", "native_peak_bytes"):
                data = samples()
                data[0][field] = value
                self.assertFalse(check_budget(data, "reference-api30-arm64")["passed"])
        invalid = [None, [], samples()[1:], samples() + [samples()[0]]]
        for field, value in (("success", False), ("success", 1), ("payload_bytes", 0), ("phase", "unknown")):
            data = samples()
            data[0][field] = value
            invalid.append(data)
        for data in invalid:
            self.assertFalse(check_budget(data, "reference-api30-arm64")["passed"])

    def test_cli_exits_nonzero_over_budget(self):
        """The reusable command itself fails, rather than merely recording a violation."""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "report.json"
            data = samples()
            data[0]["elapsed_ms"] += 1
            path.write_text(json.dumps({"metrics": data}))
            result = subprocess.run([sys.executable, str(Path(__file__).with_name("budget_checker.py")),
                                     str(path), "--profile", "ci-api34-x86_64"], capture_output=True, text=True)
            self.assertEqual(1, result.returncode)
            self.assertFalse(json.loads(result.stdout)["passed"])

    def run_real_http(self, data, missing_event=None):
        """Keep real transport intact while independently controlling metrics or a missing ledger event."""
        owned = {}
        original_server = device_runner.FixtureServer
        original_wait = device_runner.wait_for_ledger_completion

        def server(root):
            """Expose the actual loopback server to the simulated instrumentation client."""
            owned["server"] = original_server(root)
            event = owned["server"].ledger.event

            def record(request, token, kind, value=0):
                """Model a terminal ledger write that never arrives without changing transferred bytes."""
                return 0 if kind == missing_event else event(request, token, kind, value)

            owned["server"].ledger.event = record
            return owned["server"]

        def adb(_, serial, *args):
            """Simulate successful Android instrumentation after real host HTTP exchanges."""
            properties = {"ro.kernel.qemu": "1", "ro.build.version.sdk": "30", "ro.product.cpu.abi": "arm64-v8a"}
            if args[:2] == ("shell", "getprop"):
                return properties[args[2]]
            if args[:4] == ("shell", "pm", "list", "packages"):
                return "package:" + device_runner.APP
            if args[:3] != ("shell", "am", "instrument"):
                return ""
            client = http.client.HTTPConnection("127.0.0.1", owned["server"].server_port, timeout=5)
            try:
                client.request("PUT", "/upload", b"x" * 1040)
                upload = json.loads(client.getresponse().read())
                client.request("GET", "/" + upload["sha256"])
                self.assertEqual(1040, len(client.getresponse().read()))
                client.request("POST", "/__acquisition-unavailable")
                client.getresponse().read()
            finally:
                client.close()
            return "\n".join("INSTRUMENTATION_STATUS: controlled_attachment_json=" + json.dumps(m)
                             for m in data) + "\nOK (1 test)"

        with tempfile.TemporaryDirectory() as directory, patch.object(device_runner, "FixtureServer", side_effect=server), \
                patch.object(device_runner, "adb_command", side_effect=adb), \
                patch.object(device_runner, "wait_for_ledger_completion",
                             side_effect=lambda ledger, start: original_wait(ledger, start, timeout=0.1)):
            output = Path(directory) / "report.json"
            with self.assertRaises(RuntimeError):
                device_runner.run("adb", "emulator-5554", Path(directory) / "server", output)
            report = json.loads(output.read_text())
            self.assertTrue(report["instrumentation_passed"])
            self.assertEqual(1, report["http_acquisition_requests"])
            self.assertEqual(1040, report["successful_ciphertext_body_write_bytes"])
            self.assertFalse(report["qualified"])
            self.assertFalse(report["reverse_cleanup_failed"])
            return report

    def test_runner_preserves_over_budget_failure_with_complete_real_http(self):
        """Real upload, GET and durable completion cannot conceal an over-budget local read."""
        data = copy.deepcopy(samples())
        data[1]["elapsed_ms"] += 1
        report = self.run_real_http(data)
        self.assertTrue(report["ledger_finalized"])
        self.assertFalse(report["budget_check"]["passed"])

    def test_runner_preserves_timeout_report_when_a_terminal_event_is_missing(self):
        """Passing instrumentation, budgets and exact bytes still fail without either terminal event."""
        for event in ("complete", "upload_complete"):
            with self.subTest(missing=event):
                report = self.run_real_http(samples(), missing_event=event)
                self.assertTrue(report["budget_check"]["passed"])
                self.assertFalse(report["ledger_finalized"])
                self.assertNotIn(event, {e["kind"] for e in report["ledger"]})


if __name__ == "__main__":
    unittest.main()
