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

    def test_not_found_and_restore_are_counted_and_never_reset_the_ledger(self):
        """A permanent-miss phase is a counted attempt, and restoring service keeps every earlier attempt."""
        source = self.server.generate("missing", 2048)
        self.assertEqual(200, self.get("/__acquisition-not-found", method="POST")[0])
        self.assertEqual(404, self.get("/missing")[0])
        self.assertEqual(200, self.get("/__restore-acquisition", method="POST")[0])
        self.assertEqual(source.read_bytes(), self.get("/missing")[2])
        events = self.await_event("complete", 2)
        self.assertEqual(2, sum(e["kind"] == "get" for e in events))
        self.assertEqual([404, 200], [e["value"] for e in events if e["kind"] == "status"])
        self.assertEqual(2048, sum(e["value"] for e in events if e["kind"] == "body_bytes"))
        self.assertEqual(1, sum(e["kind"] == "acquisition_not_found" for e in events))
        self.assertEqual(1, sum(e["kind"] == "restore_acquisition" for e in events))

    def test_restore_also_lifts_the_service_unavailable_denial(self):
        """One restore control clears either denial so a deliberate Retry can be exercised."""
        self.server.generate("denied", 1024)
        self.assertEqual(200, self.get("/__acquisition-unavailable", method="POST")[0])
        self.assertEqual(503, self.get("/denied")[0])
        self.assertEqual(200, self.get("/__restore-acquisition", method="POST")[0])
        self.assertEqual(200, self.get("/denied")[0])

    def test_pacing_slows_existing_bodies_without_holding_or_truncating_them(self):
        """Pacing is applied to uploaded bodies after creation and still delivers every byte."""
        source = self.server.generate("paced", 3 * 16 * 1024)
        self.assertEqual(0, self.server.controls["paced"].interval)
        self.assertEqual(200, self.get("/__pace-acquisition", method="POST")[0])
        self.assertGreater(self.server.controls["paced"].interval, 0)
        self.assertIsNone(self.server.controls["paced"].hold_after)
        started = time.monotonic()
        self.assertEqual(source.read_bytes(), self.get("/paced")[2])
        self.assertGreaterEqual(time.monotonic() - started, 2 * self.server.controls["paced"].interval)
        events = self.await_event("complete")
        self.assertEqual(0, sum(e["kind"] == "held" for e in events))
        self.assertEqual(1, sum(e["kind"] == "pace_acquisition" for e in events))

    def test_shaping_bounds_download_throughput_and_adds_latency_without_resetting_the_ledger(self):
        """A shaped link is deterministic and verifiable outside the app, and restoring it keeps every earlier attempt."""
        source = self.server.generate("shaped", 128 * 1024)
        started = time.monotonic()
        self.assertEqual(source.read_bytes(), self.get("/shaped")[2])
        unshaped = time.monotonic() - started
        self.assertEqual(200, self.get("/__shape/2000/0/200", method="POST")[0])
        started = time.monotonic()
        self.assertEqual(source.read_bytes(), self.get("/shaped")[2])
        shaped = time.monotonic() - started
        # 128 KiB at 2 Mbit/s is about 0.52 s on the wire, plus 0.2 s of latency.
        self.assertGreaterEqual(shaped, 0.55)
        self.assertLess(unshaped, shaped)
        self.assertEqual(200, self.get("/__shape/0/0/0", method="POST")[0])
        events = self.await_event("complete", 2)
        self.assertEqual(2, sum(e["kind"] == "get" for e in events))
        self.assertEqual([2000, 0], [e["value"] for e in events if e["kind"] == "shape_down_kbps"])
        self.assertEqual([200, 0], [e["value"] for e in events if e["kind"] == "shape_latency_ms"])

    def test_shaping_bounds_upload_throughput(self):
        """The upload path honors the same declared link, so an upload cell is a controlled measurement too."""
        self.assertEqual(200, self.get("/__shape/0/2000/0", method="POST")[0])
        body = bytes(range(256)) * 512
        started = time.monotonic()
        status, _, _ = self.get("/upload", method="PUT", body=body)
        elapsed = time.monotonic() - started
        self.assertEqual(200, status)
        # 128 KiB at 2 Mbit/s.
        self.assertGreaterEqual(elapsed, 0.45)

    def test_numbered_markers_delimit_samples_in_the_ledger(self):
        """A marker is a ledger boundary only; it changes no behavior and rejects out-of-range numbers."""
        self.server.generate("sampled", 1024)
        self.assertEqual(200, self.get("/__marker/7", method="POST")[0])
        self.get("/sampled")
        self.assertEqual(200, self.get("/__marker/8", method="POST")[0])
        for path in ("/__marker/-1", "/__marker/x", "/__marker/1000001"):
            self.assertEqual(400, self.get(path, method="POST")[0], path)
        events = self.await_event("complete")
        kinds = [(e["kind"], e["value"]) for e in events if e["kind"] in ("marker", "get")]
        self.assertEqual([("marker", 7), ("get", 0), ("marker", 8)], kinds)

    def test_shape_rejects_values_outside_the_bounded_range(self):
        """A malformed or unbounded shape never changes the link and is answered as a bad request."""
        for path in ("/__shape/-1/0/0", "/__shape/1/2", "/__shape/a/b/c", "/__shape/99999999/0/0"):
            self.assertEqual(400, self.get(path, method="POST")[0], path)
        self.assertEqual(0, self.server.shape.down_bps)
        started = time.monotonic()
        self.server.generate("unbound", 128 * 1024)
        self.get("/unbound")
        self.assertLess(time.monotonic() - started, 1.5)

    def test_global_hold_release_does_not_hold_the_deliberate_retry(self):
        """The release control removes the hold rather than reporting a second fake hold event."""
        source = self.server.generate("released", 1040)
        self.assertEqual(200, self.get("/__hold-acquisition", method="POST")[0])
        self.assertEqual(1024, self.server.controls["released"].hold_after)
        self.assertEqual(200, self.get("/__release-acquisition", method="POST")[0])
        self.assertEqual(source.read_bytes(), self.get("/released")[2])
        events = self.await_event("complete")
        self.assertEqual(0, sum(e["kind"] == "held" for e in events))

    def test_releasing_hold_preserves_body_pacing(self):
        """Release admits the suffix without turning a constrained response into an instant body."""
        control = Control(interval=0.1, hold_after=16384)
        source = self.server.generate("paced-release", 65537, control)
        control.release.set()
        self.assertEqual(source.read_bytes(), self.get("/paced-release")[2])
        events = self.await_event("complete")
        chunks = [event for event in events if event["kind"] == "body_bytes"]
        self.assertEqual(5, len(chunks))
        self.assertGreaterEqual(chunks[-1]["at_ns"] - chunks[0]["at_ns"], 350_000_000)

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

    def test_background_controls_preserve_hold_and_record_independent_markers(self):
        """Pacing changes only future chunks; Android lifecycle evidence cannot reset request counters."""
        self.server.generate("background", 4 * 1024 * 1024)
        self.assertEqual(200, self.get("/__hold-resumable-acquisition", method="POST")[0])
        self.assertEqual(200, self.get("/__pace-background-acquisition", method="POST")[0])
        control = self.server.controls["background"]
        self.assertEqual(0.25, control.interval)
        self.assertEqual(2 * 1024 * 1024, control.hold_after)
        self.assertFalse(control.release.is_set())
        self.assertEqual(200, self.get("/__background-start", method="POST")[0])
        self.assertEqual(200, self.get("/__background-end", method="POST")[0])
        kinds = [e["kind"] for e in self.server.ledger.snapshot()]
        self.assertEqual(["hold_resumable_acquisition", "pace_background_acquisition", "background_start", "background_end"], kinds)

    def test_native_unknown_length_control_holds_then_releases_without_content_length(self):
        """Exercise the actual opt-in endpoint, response framing and durable byte totals."""
        source = self.server.generate("unknown-held", 4 * 1024 * 1024 + 16)
        self.assertEqual(200, self.get("/__hold-unknown-acquisition", method="POST")[0])
        client = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=5)
        try:
            client.request("GET", "/unknown-held")
            response = client.getresponse()
            self.assertIsNone(response.getheader("Content-Length"))
            self.assertEqual("close", response.getheader("Connection"))
            prefix = response.read(2 * 1024 * 1024)
            self.await_event("held")
            self.assertEqual(200, self.get("/__release-acquisition", method="POST")[0])
            self.assertEqual(source.read_bytes(), prefix + response.read())
            events = self.await_event("complete")
            self.assertEqual(1, sum(e["kind"] == "unknown_content_length" for e in events))
            self.assertEqual(source.stat().st_size, sum(e["value"] for e in events if e["kind"] == "body_bytes"))
        finally:
            client.close()

    def test_interruption_preserves_prefix_and_replacement_control(self):
        """Both validator paths close the held socket and keep replacement responses independent."""
        for changed in (False, True):
            with self.subTest(changed=changed):
                token = "changed-prefix" if changed else "compatible-prefix"
                source = self.server.generate(token, 4 * 1024 * 1024)
                self.get("/__hold-resumable-acquisition", method="POST")
                client = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=5)
                client.request("GET", "/" + token)
                response = client.getresponse()
                validator = response.getheader("ETag")
                prefix = response.read(2 * 1024 * 1024)
                held_count = 2 if changed else 1
                self.await_event("held", held_count)
                path = "/__interrupt-changed-validator" if changed else "/__interrupt-acquisition"
                self.assertEqual(200, self.get(path, method="POST")[0])
                with self.assertRaises(http.client.IncompleteRead):
                    response.read()
                client.close()
                self.await_event("disconnect", held_count)
                status, _, body = self.get("/" + token, {
                    "Range": f"bytes={len(prefix)}-", "If-Range": validator,
                })
                self.assertEqual(200 if changed else 206, status)
                self.assertEqual(source.read_bytes() if changed else source.read_bytes()[len(prefix):], body)
                events = self.await_event("complete", held_count)
                relevant = [e for e in events if e["fixture"] == token]
                self.assertEqual([int(not changed)], [e["value"] for e in relevant if e["kind"] == "if_range_match"])
                self.assertEqual([len(prefix)], [e["value"] for e in relevant if e["kind"] == "range_requested_offset"])
                self.assertNotIn(validator, json.dumps(events))

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

    def test_binary_suffix_is_the_same_generated_blob_without_changing_default_uploads(self):
        """Match MDK's canonical group fallback only when that explicit fixture mode requests it."""
        original = b"generated suffix fixture"
        self.server.upload_extension = ".bin"
        status, _, body = self.get("/upload", method="PUT", body=original)
        self.assertEqual(200, status)
        descriptor = json.loads(body)
        self.assertTrue(descriptor["url"].endswith(descriptor["sha256"] + ".bin"))
        self.assertEqual(original, self.get("/" + descriptor["sha256"] + ".bin")[2])
        self.assertEqual(original, self.get("/" + descriptor["sha256"])[2])
        self.assertEqual(404, self.get("/../ledger.sqlite3.bin")[0])
        with self.assertRaises(ValueError):
            FixtureServer(self.directory.name, upload_extension="/../escape")


if __name__ == "__main__":
    unittest.main()
