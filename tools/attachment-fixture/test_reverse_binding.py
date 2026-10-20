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
                calls, allocated, host_ports, listeners = [], [], [], {}

                def adb(binary, serial, *args):
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
                        host_ports.append(host_port)
                        listeners.setdefault(host_port, "unrelated")
                        if args[2] != "tcp:0":
                            raise subprocess.CalledProcessError(1, [binary, *args], stderr="Address already in use")
                        port = next(p for p in range(45001, 65536) if p not in listeners)
                        listeners[port] = "fixture"
                        allocated.append(port)
                        return f"{port}\n"
                    if args[:2] == ("reverse", "--remove"):
                        self.assertEqual("fixture", listeners.pop(int(args[2].removeprefix("tcp:"))))
                    elif args[:3] == ("shell", "am", "instrument"):
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
                        patch.object(device_runner, "wait_for_ledger_completion", return_value=([], False)):
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
                self.assertEqual(set(host_ports) - set(allocated), set(listeners))
                self.assertEqual(2 if restart else 1, sum(c[:3] == ("shell", "am", "instrument") for c in calls))
                self.assertEqual(restart, ("shell", "am", "force-stop", device_runner.APP) in calls)
                self.assertFalse(any("--remove-all" in c or "uninstall" in c or "clear" in c for c in calls))

    def test_failed_second_bind_retains_failure_and_removes_only_the_first_allocation(self):
        """Partial setup never starts instrumentation or exposes raw ADB error text in the report."""
        for second_response in (None, "PRIVATE-INVALID-PORT\n"):
            with self.subTest(second_response=second_response), tempfile.TemporaryDirectory() as directory:
                calls, bindings = [], []

                def adb(binary, serial, *args):
                    """Allocate once, then simulate a transport failure or malformed allocated-port output."""
                    calls.append(args)
                    if args[:3] == ("shell", "getprop", "ro.kernel.qemu"):
                        return "1"
                    if args[:4] == ("shell", "pm", "list", "packages"):
                        return "package:" + device_runner.APP
                    if args[:2] == ("reverse", "--no-rebind"):
                        bindings.append(args)
                        if len(bindings) == 1:
                            return "45001\n"
                        if second_response is None:
                            raise subprocess.CalledProcessError(1, [binary, *args], stderr="PRIVATE-TRANSPORT-ERROR")
                        return second_response
                    return ""

                output = Path(directory) / "report.json"
                with patch.object(device_runner, "adb_command", side_effect=adb), \
                        patch.object(device_runner, "wait_for_ledger_completion", return_value=([], False)), \
                        patch.object(device_runner.subprocess, "run") as capture:
                    with self.assertRaises(RuntimeError):
                        device_runner.run("chosen-adb", "emulator-5554", Path(directory) / "server", output)
                    capture.assert_not_called()
                report = json.loads(output.read_text())
                self.assertEqual("reverse-binding", report["failure_diagnostics"]["stage"])
                self.assertEqual("CalledProcessError" if second_response is None else "ValueError", report["failure_class"])
                self.assertFalse(report["qualified"])
                self.assertEqual([], report["metrics"])
                self.assertNotIn("PRIVATE", output.read_text())
                self.assertFalse(any(c[:3] == ("shell", "am", "instrument") for c in calls))
                self.assertEqual([("reverse", "--remove", "tcp:45001")], [c for c in calls if c[:2] == ("reverse", "--remove")])

    def test_allocated_port_output_is_a_single_valid_ascii_tcp_port(self):
        """Reject empty, zero, signed, multiple, non-ASCII or out-of-range responses without echoing them."""
        for response in ("", "0", "65536", "-1", "+123", "tcp:123", "123\n456", "１２３", "PRIVATE", "1" * 100):
            with self.subTest(response=response), patch.object(device_runner, "adb_command", return_value=response):
                with self.assertRaises(ValueError) as error:
                    device_runner.bind_reverse_port("chosen-adb", "emulator-5554", 32100)
                self.assertNotIn(response or "PRIVATE", str(error.exception))
        for response, port in (("1\n", 1), ("65535\n", 65535)):
            with self.subTest(response=response), patch.object(device_runner, "adb_command", return_value=response) as adb:
                self.assertEqual(port, device_runner.bind_reverse_port("chosen-adb", "emulator-5554", 32100))
                adb.assert_called_once_with("chosen-adb", "emulator-5554", "reverse", "--no-rebind", "tcp:0", "tcp:32100")

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
