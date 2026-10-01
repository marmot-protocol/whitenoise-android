"""Executable transport contracts; all clients connect to disposable loopback services."""

import http.client
import json
from pathlib import Path
import socket
import tempfile
import threading
import time
import unittest

from fixture_server import Control, FixtureServer, Ledger


class FixtureContractTest(unittest.TestCase):
    """Assert external request/byte evidence survives client and server lifecycle changes."""

    def setUp(self):
        """Use a new private run root so test boundaries, not client restart, reset counters."""
        self.directory = tempfile.TemporaryDirectory()
        self.start()

    def start(self):
        """Reopen the same host ledger when exercising server restart."""
        self.server = FixtureServer(self.directory.name)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def stop(self):
        """Stop only this disposable listener and join its serving thread."""
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(5)

    def tearDown(self):
        """Release generated artifacts after all contract assertions complete."""
        self.stop()
        self.directory.cleanup()

    def get(self, path, headers=None, method="GET", body=None):
        """Use a new socket per action, just as a restarted app would."""
        client = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=5)
        try:
            client.request(method, path, body=body, headers=headers or {})
            response = client.getresponse()
            return response.status, dict(response.getheaders()), response.read()
        finally:
            client.close()

    def await_event(self, kind, count=1):
        """Fail on missing server evidence; never accept a client-side count in its place."""
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            events = self.server.ledger.snapshot()
            if sum(event["kind"] == kind for event in events) >= count:
                return events
            time.sleep(0.01)
        self.fail(f"missing ledger event: {kind}")

    def test_full_body_range_and_validator_change_count_repeated_bytes(self):
        """Count compatible remainder bytes and the incompatible-validator full restart separately."""
        source = self.server.generate("range", 65537)
        status, headers, body = self.get("/range")
        self.assertEqual(200, status)
        self.assertEqual(source.read_bytes(), body)
        status, resumed, body = self.get("/range", {"Range": "bytes=16384-", "If-Range": headers["ETag"]})
        self.assertEqual(206, status)
        self.assertEqual("bytes 16384-65536/65537", resumed["Content-Range"])
        self.assertEqual(source.read_bytes()[16384:], body)
        status, _, body = self.get("/range", {"Range": "bytes=16384-", "If-Range": '"changed"'})
        self.assertEqual(200, status)
        self.assertEqual(source.read_bytes(), body)
        events = self.await_event("complete", 3)
        self.assertEqual(3, sum(e["kind"] == "get" for e in events))
        self.assertEqual(3 * 65537 - 16384, sum(e["value"] for e in events if e["kind"] == "body_bytes"))

    def test_unavailable_endpoint_still_counts_accidental_acquisition(self):
        """Deny acquisition after the baseline without hiding a retained-hit HTTP regression."""
        self.server.generate("offline", 1024)
        self.assertEqual(200, self.get("/offline")[0])
        self.assertEqual(200, self.get("/__acquisition-unavailable", method="POST")[0])
        self.assertEqual(503, self.get("/offline")[0])
        events = self.await_event("complete", 2)
        self.assertEqual(2, sum(e["kind"] == "get" for e in events))
        self.assertEqual(1024, sum(e["value"] for e in events if e["kind"] == "body_bytes"))
        self.assertEqual(1, sum(e["kind"] == "acquisition_unavailable" for e in events))

    def test_restart_preserves_all_attempts_including_missing_body(self):
        """A restart must retain successful and failed request attempts in one ledger."""
        self.server.generate("restart", 1024)
        self.get("/restart")
        self.get("/not-present")
        before = self.await_event("complete", 2)
        self.stop()
        self.start()
        self.assertEqual(before, self.server.ledger.snapshot())
        self.get("/restart")
        events = self.await_event("complete", 3)
        self.assertEqual(3, sum(e["kind"] == "get" for e in events))
        self.assertEqual(2048, sum(e["value"] for e in events if e["kind"] == "body_bytes"))
        self.assertTrue(any(e["kind"] == "status" and e["value"] == 404 for e in events))

    def test_cancellation_disconnects_held_body_without_eof_and_no_new_request(self):
        """Peer cancellation, rather than EOF, terminates the exact held prefix."""
        self.server.generate("cancel", 1024 * 1024, Control(hold_after=1024))
        client = socket.create_connection(("127.0.0.1", self.server.server_port), timeout=5)
        client.sendall(b"GET /cancel HTTP/1.1\r\nHost: localhost\r\n\r\n")
        self.await_event("held")
        started = time.monotonic()
        client.shutdown(socket.SHUT_RDWR)
        client.close()
        events = self.await_event("disconnect")
        self.assertLess(time.monotonic() - started, 5)
        self.assertFalse(any(e["kind"] == "complete" for e in events))
        self.assertEqual(1024, sum(e["value"] for e in events if e["kind"] == "body_bytes"))
        # Host contract uses a short quiet window; Android cancellation qualification requires 30s.
        time.sleep(0.1)
        self.assertEqual(events, self.server.ledger.snapshot())
        self.server.controls["cancel"].release.set()
        self.assertEqual(200, self.get("/cancel")[0])
        self.assertEqual(2, sum(e["kind"] == "get" for e in self.server.ledger.snapshot()))

    def test_explicit_release_completes_exact_checkpoint_and_resume(self):
        """The host control channel permits completion only after the held prefix is observed."""
        source = self.server.generate("prefix", 32768, Control(hold_after=1024))
        result = []
        client = threading.Thread(target=lambda: result.append(self.get("/prefix")))
        client.start()
        self.await_event("held")
        self.assertEqual(200, self.get("/__release/prefix", method="POST")[0])
        client.join(5)
        self.assertFalse(client.is_alive())
        self.assertEqual(source.read_bytes(), result[0][2])

    def test_unknown_length_head_and_invalid_range(self):
        """Unknown total length and invalid ranges remain distinguishable from a full known body."""
        source = self.server.generate("unknown", 1024, Control(unknown_length=True))
        status, headers, body = self.get("/unknown")
        self.assertEqual(200, status)
        self.assertNotIn("Content-Length", headers)
        self.assertEqual(source.read_bytes(), body)
        self.assertEqual(b"", self.get("/unknown", method="HEAD")[2])
        self.assertEqual(416, self.get("/unknown", {"Range": "bytes=1024-"})[0])
        self.assertEqual(416, self.get("/unknown", {"Range": "bytes=1-2,4-"})[0])

    def test_zero_offset_range_is_partial_response(self):
        """A valid zero-offset range still requires HTTP 206 and Content-Range."""
        source = self.server.generate("zero-range", 1024)
        status, headers, body = self.get("/zero-range", {"Range": "bytes=0-"})
        self.assertEqual(206, status)
        self.assertEqual("bytes 0-1023/1024", headers["Content-Range"])
        self.assertEqual(source.read_bytes(), body)

    def test_genuine_upload_is_streamed_and_private_locators_are_not_exported(self):
        """The upload descriptor routes exact bytes while exported evidence excludes its locator."""
        original = bytes(range(256)) * 256
        status, _, uploaded = self.get("/upload", method="PUT", body=original)
        self.assertEqual(200, status)
        descriptor = json.loads(uploaded)
        self.assertEqual(original, self.get("/" + descriptor["sha256"])[2])
        events = self.await_event("complete")
        serialized = json.dumps(events)
        self.assertNotIn(descriptor["sha256"], serialized)
        self.assertNotIn("127.0.0.1", serialized)
        self.assertEqual(len(original), sum(e["value"] for e in events if e["kind"] == "upload_bytes"))
        self.assertEqual(len(original), sum(e["value"] for e in events if e["kind"] == "body_bytes"))

    def test_rejects_path_escape_and_oversize_upload(self):
        """Fixture paths cannot escape the private run root and upload admission is bounded."""
        with self.assertRaises(ValueError):
            self.server.generate("../escape", 1)
        self.assertEqual(404, self.get("/../ledger.sqlite3")[0])
        self.assertEqual(413, self.get("/upload", method="PUT", headers={"Content-Length": "999999999"})[0])
        self.assertEqual(0o600, Path(self.server.ledger.path).stat().st_mode & 0o777)


if __name__ == "__main__":
    unittest.main()
