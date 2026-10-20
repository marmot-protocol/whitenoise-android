"""Device listeners are allocated independently of host sockets and cleaned up by ownership."""

import http.client
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from urllib.parse import urlsplit

import device_runner


class ReverseBindingTest(unittest.TestCase):
    """Exercise the real runner with ADB responses and device-port contention controlled."""

    def test_allocated_ports_reach_both_processes_and_only_owned_mappings_are_removed(self):
        """Occupied device ports cannot block or redirect upload descriptors in either instrumentation process."""
        for restart in (False, True):
            with self.subTest(restart=restart), tempfile.TemporaryDirectory() as directory:
                calls, allocated, host_ports, listeners = [], [], [], {45001: "unrelated"}

                def adb(binary, serial, *args, **options):
                    """Model independent device listeners, including contention at host-numbered device ports."""
                    self.assertEqual(("chosen-adb", "emulator-5554"), (binary, serial))
                    calls.append(args)
                    if args[:2] == ("shell", "getprop"):
                        return {"ro.kernel.qemu": "1", "ro.build.version.sdk": "30",
                                "ro.product.cpu.abi": "arm64-v8a"}[args[2]]
                    if args[:4] == ("shell", "pm", "list", "packages"):
                        return "package:" + device_runner.APP
                    if args[:2] == ("reverse", "--no-rebind"):
                        host_port = int(args[3].removeprefix("tcp:"))
                        if host_port not in host_ports:
                            host_ports.append(host_port)
                        listeners.setdefault(host_port, "unrelated")
                        port = int(args[2].removeprefix("tcp:"))
                        self.assertGreaterEqual(port, 32768)
                        if port in listeners:
                            raise subprocess.CalledProcessError(1, [binary, *args],
                                stderr="cannot bind listener: Address already in use PRIVATE")
                        listeners[port] = "fixture"
                        allocated.append(port)
                        return f"{port}\n"
                    if args[:2] == ("reverse", "--remove"):
                        self.assertEqual("fixture", listeners.pop(int(args[2].removeprefix("tcp:"))))
                    elif args[:3] == ("shell", "am", "instrument"):
                        self.assertEqual(("shell", "am", "instrument", "-w", "-r", "-e", "class", device_runner.PROBE,
                                          "-e", "allowControlledAttachmentProbe", "true"), args[:11])
                        self.assertEqual(device_runner.APP + ".test/androidx.test.runner.AndroidJUnitRunner", args[-1])
                        self.assertEqual(str(allocated[0]), args[args.index("fixtureBlobPort") + 1])
                        self.assertEqual(str(allocated[1]), args[args.index("fixtureRelayPort") + 1])
                        # Simulate the device namespace: resolve the advertised listener through its mapping.
                        client = http.client.HTTPConnection("127.0.0.1", host_ports[0], timeout=5)
                        try:
                            body = b"fixture-ciphertext"
                            client.request("PUT", "/upload", body)
                            upload = json.loads(client.getresponse().read())
                            locator = urlsplit(upload["url"])
                            self.assertEqual("127.0.0.1", locator.hostname)
                            self.assertEqual(allocated[0], locator.port)
                            self.assertEqual("fixture", listeners[locator.port])
                            client.request("GET", locator.path)
                            self.assertEqual(body, client.getresponse().read())
                        finally:
                            client.close()
                        return "OK (1 test)"
                    return ""

                output = Path(directory) / "report.json"
                with patch.object(device_runner, "adb_command", side_effect=adb), \
                        patch.object(device_runner.random, "SystemRandom") as random, \
                        patch.object(device_runner, "wait_for_ledger_completion",
                                     side_effect=lambda ledger, start: (ledger.snapshot()[start:], False)):
                    random.return_value.sample.side_effect = [[45001, 45002, 45003, 45004], [45005, 45006, 45007, 45008]]
                    # Routing-only instrumentation has no measurements and must not qualify the app.
                    with self.assertRaises(RuntimeError):
                        device_runner.run("chosen-adb", "emulator-5554", Path(directory) / "server", output,
                                          process_restart=restart)
                report = json.loads(output.read_text())
                self.assertNotIn("failure_class", report)
                self.assertTrue(report["instrumentation_passed"])
                self.assertFalse(report["qualified"])
                self.assertFalse(report["reverse_cleanup_failed"])
                self.assertEqual(2 if restart else 1, report["http_upload_requests"])
                self.assertEqual(2 if restart else 1, report["http_get_requests"])
                self.assertEqual(2, len(allocated))
                self.assertEqual({"unrelated"}, set(listeners.values()))
                self.assertEqual((set(host_ports) | {45001}) - set(allocated), set(listeners))
                self.assertGreaterEqual(report["reverse_admission"][0]["occupied_ports"], 1)
                self.assertEqual(["admitted", "admitted"], [r["status"] for r in report["reverse_admission"]])
                self.assertEqual(2 if restart else 1, sum(c[:3] == ("shell", "am", "instrument") for c in calls))
                self.assertEqual(restart, ("shell", "am", "force-stop", device_runner.APP) in calls)
                self.assertFalse(any("--remove-all" in c or "uninstall" in c or "clear" in c for c in calls))

    def test_failed_second_bind_retains_failure_and_removes_only_the_first_allocation(self):
        """Partial setup never starts instrumentation or exposes raw ADB error text in the report."""
        for failure in ("transport", "timeout", "occupied"):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory:
                calls, bindings = [], []

                def adb(binary, serial, *args, **options):
                    """Admit one mapping, then fail the second listener without running any instrumentation."""
                    calls.append(args)
                    if args[:3] == ("shell", "getprop", "ro.kernel.qemu"):
                        return "1"
                    if args[:4] == ("shell", "pm", "list", "packages"):
                        return "package:" + device_runner.APP
                    if args[:2] == ("reverse", "--no-rebind"):
                        bindings.append(args)
                        if len(bindings) == 1:
                            return ""
                        if failure == "timeout":
                            raise subprocess.TimeoutExpired("PRIVATE", 180)
                        message = "cannot bind listener: Address already in use PRIVATE" if failure == "occupied" else "PRIVATE"
                        raise subprocess.CalledProcessError(1, [binary, *args], stderr=message)
                    return ""

                output = Path(directory) / "report.json"
                with patch.object(device_runner, "adb_command", side_effect=adb), \
                        patch.object(device_runner.random, "SystemRandom") as random, \
                        patch.object(device_runner, "wait_for_ledger_completion", return_value=([], False)), \
                        patch.object(device_runner.subprocess, "run") as capture:
                    random.return_value.sample.side_effect = [[45001, 45002, 45003, 45004], [45005, 45006, 45007, 45008]]
                    with self.assertRaises(RuntimeError):
                        device_runner.run("chosen-adb", "emulator-5554", Path(directory) / "server", output)
                    capture.assert_not_called()
                report = json.loads(output.read_text())
                self.assertEqual("reverse-binding", report["failure_diagnostics"]["stage"])
                self.assertEqual("TimeoutExpired" if failure == "timeout" else "CalledProcessError", report["failure_class"])
                self.assertEqual(5 if failure == "occupied" else 2, len(bindings))
                self.assertEqual("exhausted" if failure == "occupied" else "failed", report["reverse_admission"][1]["status"])
                if failure == "occupied":
                    self.assertEqual([f"tcp:{p}" for p in (45005, 45006, 45007, 45008)], [b[2] for b in bindings[1:]])
                    self.assertEqual({"attempts": 4, "occupied_ports": 4, "status": "exhausted"}, report["reverse_admission"][1])
                self.assertFalse(report["qualified"])
                self.assertEqual([], report["metrics"])
                self.assertNotIn("PRIVATE", output.read_text())
                self.assertFalse(any(c[:3] == ("shell", "am", "instrument") for c in calls))
                self.assertEqual([("reverse", "--remove", "tcp:45001")], [c for c in calls if c[:2] == ("reverse", "--remove")])

    def test_only_confirmed_occupied_socket_advances_to_another_candidate(self):
        """Explicit reservation owns the requested port; arbitrary stdout never selects a device listener."""
        error = subprocess.CalledProcessError(1, "PRIVATE", stderr="cannot bind listener: Address already in use PRIVATE")
        receipt = {}
        with patch.object(device_runner.random, "SystemRandom") as random, \
                patch.object(device_runner, "adb_command", side_effect=[error, "PRIVATE-NOT-A-PORT"]) as adb:
            random.return_value.sample.return_value = [45001, 45002, 45003, 45004]
            self.assertEqual(45002, device_runner.bind_reverse_port("chosen-adb", "emulator-5554", 32100, receipt))
            random.return_value.sample.assert_called_once_with(range(32768, 61000), 4)
            self.assertEqual([("chosen-adb", "emulator-5554", "reverse", "--no-rebind", f"tcp:{p}", "tcp:32100")
                              for p in (45001, 45002)], [c.args for c in adb.call_args_list])
        self.assertEqual({"attempts": 2, "occupied_ports": 1, "status": "admitted"}, receipt)
        self.assertNotIn("PRIVATE", json.dumps(receipt))

    def test_timeouts_transport_errors_and_unrecognized_collisions_fail_immediately(self):
        """Only the complete known occupied-socket error permits another admission attempt."""
        errors = [subprocess.TimeoutExpired("PRIVATE", 180), OSError("PRIVATE")]
        for text in ("cannot bind listener", "Address already in use", "cannot rebind existing socket", "error: device offline"):
            errors.append(subprocess.CalledProcessError(1, "PRIVATE", stderr=text))
        errors.append(subprocess.CalledProcessError(255, "PRIVATE", stderr="cannot bind listener: Address already in use"))
        for error in errors:
            with self.subTest(error=repr(error)), patch.object(device_runner.random, "SystemRandom") as random, \
                    patch.object(device_runner, "adb_command", side_effect=error) as adb:
                random.return_value.sample.return_value = [45001, 45002, 45003, 45004]
                receipt = {}
                with self.assertRaises(type(error)):
                    device_runner.bind_reverse_port("chosen-adb", "emulator-5554", 32100, receipt)
                self.assertEqual(1, adb.call_count)
                self.assertEqual({"attempts": 1, "occupied_ports": 0, "status": "failed"}, receipt)

    def test_client_upload_port_preserves_the_host_url_and_rejects_invalid_configuration(self):
        """Only upload descriptors change authority; failed configuration preserves the current valid port."""
        with tempfile.TemporaryDirectory() as directory:
            server = device_runner.FixtureServer(Path(directory))
            try:
                host_url = server.url
                self.assertEqual(host_url, server.upload_url)
                server.set_upload_port(1)
                self.assertEqual("http://127.0.0.1:1", server.upload_url)
                self.assertEqual(host_url, server.url)
                for port in (None, True, "123", 0, -1, 65536):
                    with self.subTest(port=port), self.assertRaises(ValueError):
                        server.set_upload_port(port)
                    self.assertEqual("http://127.0.0.1:1", server.upload_url)
            finally:
                server.server_close()


if __name__ == "__main__":
    unittest.main()
