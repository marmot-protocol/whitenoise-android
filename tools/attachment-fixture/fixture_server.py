"""Loopback-only generated Blossom fixture with a restart-persistent HTTP ledger."""

import argparse
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import re
import select
import socket
import sqlite3
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CHUNK = 16 * 1024
PACED_INTERVAL_SECONDS = 0.002
MAX_BYTES = 128 * 1024 * 1024
# A genuine upload may be as large as one file-backed send: 512 MiB - 16 bytes of plaintext plus its 16-byte tag.
# Generated, registered and served bodies keep the smaller MAX_BYTES bound.
UPLOAD_MAX_BYTES = 512 * 1024 * 1024
TOKEN = re.compile(r"[a-z0-9-]{1,64}\Z")
HASH = re.compile(r"[0-9a-f]{64}\Z")


class Ledger:
    """Commits each request/write/terminal event independently of the client lifecycle."""

    def __init__(self, root):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        if self.root.is_symlink() or self.root.stat().st_mode & 0o077:
            raise ValueError("fixture run root must be private and not a symlink")
        self.path = self.root / "ledger.sqlite3"
        self.lock = threading.Lock()
        with self.connect() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS blobs (
                    token TEXT PRIMARY KEY, locator TEXT UNIQUE, size INTEGER);
                CREATE TABLE IF NOT EXISTS events (
                    seq INTEGER PRIMARY KEY, request INTEGER, token TEXT,
                    kind TEXT, value INTEGER, at_ns INTEGER);
            """)
        os.chmod(self.path, 0o600)

    @contextmanager
    def connect(self):
        """Open a short-lived connection with durable commits and bounded contention."""
        db = sqlite3.connect(self.path, timeout=5)
        db.execute("PRAGMA synchronous=FULL")
        try:
            with db:
                yield db
        finally:
            db.close()

    def event(self, request, token, kind, value=0):
        """Append only synthetic tokens and closed metric names; return the sequence."""
        with self.lock, self.connect() as db:
            cursor = db.execute(
                "INSERT INTO events(request,token,kind,value,at_ns) VALUES(?,?,?,?,?)",
                (request, token, kind, value, time.time_ns()),
            )
            return cursor.lastrowid

    def register(self, token, locator, size):
        """Bind a generated fixture to its private disk file without exporting its hash."""
        with self.lock, self.connect() as db:
            db.execute("INSERT INTO blobs VALUES(?,?,?)", (token, locator, size))

    def blob(self, key):
        """Resolve synthetic tokens or Blossom hashes; neither becomes a request URL log."""
        with self.connect() as db:
            return db.execute(
                "SELECT token,locator,size FROM blobs WHERE token=? OR locator=?", (key, key)
            ).fetchone()

    def snapshot(self):
        """Export all attempts, including failed/unfinished ones, without locator metadata."""
        with self.connect() as db:
            rows = db.execute(
                "SELECT seq,request,token,kind,value,at_ns FROM events ORDER BY seq"
            ).fetchall()
        return [dict(zip(("seq", "request", "fixture", "kind", "value", "at_ns"), row))
                for row in rows]


class Control:
    """Bound body progress without EOF, with an explicit release independent of HTTP."""

    def __init__(self, header_delay=0, interval=0, hold_after=None, unknown_length=False):
        """Validate response pacing and own interruption/release independently of replacement requests."""
        if not 0 <= header_delay <= 60 or not 0 <= interval <= 5:
            raise ValueError("fixture delay outside bounded range")
        if hold_after is not None and not 0 <= hold_after <= MAX_BYTES:
            raise ValueError("fixture prefix outside bounded range")
        self.header_delay = header_delay
        self.interval = interval
        self.hold_after = hold_after
        self.unknown_length = unknown_length
        self.release = threading.Event()
        self.interrupt = threading.Event()
        self.validator_generation = 0


class Shape:
    """Deterministic bandwidth and latency applied to every request, so a constrained run is verified outside the app."""

    LIMIT = 10_000_000

    def __init__(self, down_kbps=0, up_kbps=0, latency_ms=0):
        """Zero means unshaped; every value is bounded and recorded in the ledger by the control that sets it."""
        if not all(isinstance(v, int) and 0 <= v <= self.LIMIT for v in (down_kbps, up_kbps, latency_ms)):
            raise ValueError("shape outside bounded range")
        self.down_bps = down_kbps * 1000
        self.up_bps = up_kbps * 1000
        self.latency = latency_ms / 1000

    def delay(self, nbytes, bps):
        """Seconds one chunk of nbytes occupies a link of bps bits per second; zero when unshaped."""
        return nbytes * 8 / bps if bps else 0.0


class FixtureServer(ThreadingHTTPServer):
    """Keep counters and bytes on disk; keep transport controls outside the app process."""

    daemon_threads = True

    def __init__(self, root, port=0, upload_extension=""):
        """Select a bounded canonical locator without changing ordinary fixture upload descriptors."""
        if upload_extension not in ("", ".bin"):
            raise ValueError("unsupported fixture upload extension")
        self.upload_extension = upload_extension
        self.ledger = Ledger(root)
        self.controls = {}
        self.payloads = {}
        self.stopping = threading.Event()
        self.acquisition_unavailable = threading.Event()
        self.acquisition_not_found = threading.Event()
        self.shape = Shape()
        super().__init__(("127.0.0.1", port), Handler)

    @property
    def url(self):
        """Return the loopback URL for explicit test configuration only."""
        return f"http://127.0.0.1:{self.server_port}"

    def set_shape(self, down_kbps, up_kbps, latency_ms):
        """Apply a bounded link shape and record it in the ledger; an invalid shape changes nothing."""
        self.shape = Shape(down_kbps, up_kbps, latency_ms)
        for kind, value in (("shape_down_kbps", down_kbps), ("shape_up_kbps", up_kbps),
                            ("shape_latency_ms", latency_ms)):
            self.ledger.event(None, "control", kind, value)

    def generate(self, token, size, control=None):
        """Generate bounded disk fixtures without allocating the complete payload."""
        if not TOKEN.fullmatch(token) or not 0 <= size <= MAX_BYTES:
            raise ValueError("invalid generated fixture")
        path = self.ledger.root / token
        digest = hashlib.sha256()
        seed = hashlib.sha256(token.encode()).digest()
        block = seed * (CHUNK // len(seed))
        with path.open("xb") as output:
            os.chmod(path, 0o600)
            for offset in range(0, size, CHUNK):
                chunk = block[:min(CHUNK, size - offset)]
                output.write(chunk)
                digest.update(chunk)
            output.flush()
            os.fsync(output.fileno())
        self.ledger.register(token, digest.hexdigest(), size)
        self.controls[token] = control or Control()
        return path

    def add_payload(self, token, path):
        """Expose one host-built file to the probe outside the counted acquisition ledger, never as a blob."""
        path = Path(path)
        if not TOKEN.fullmatch(token) or token in self.payloads or path.is_symlink() or not path.is_file():
            raise ValueError("invalid fixture payload")
        size = path.stat().st_size
        if not 0 < size <= MAX_BYTES:
            raise ValueError("fixture payload outside bounded range")
        self.payloads[token] = (path, size)

    def server_close(self):
        """Release held responses before closing the listening socket."""
        self.stopping.set()
        for control in self.controls.values():
            control.release.set()
        super().server_close()


class Handler(BaseHTTPRequestHandler):
    """Minimal Blossom upload and strong-validator download, plus local control endpoints."""

    protocol_version = "HTTP/1.1"

    # Reads and writes inside one request stay bounded so a dead peer cannot hold a handler.
    request_timeout = 5
    # A keep-alive connection that is merely idle between requests is not dead. A pooled client sends its next
    # request whenever it is ready, so this must outlast any client's own idle limit (90 s by default for the common
    # HTTP stacks): a server that closes first drops an upload whose body has already started and is never replayed.
    idle_timeout = 300

    def log_message(self, *_):
        """Suppress HTTP access logs, which otherwise contain private ciphertext locators."""

    def setup(self):
        """Bound dead sockets; the wait for a request line is bounded separately from the request itself."""
        super().setup()
        self.connection.settimeout(self.idle_timeout)

    def handle_one_request(self):
        """Wait out a pooled connection's idle gap with the long bound, then bound the rest of the request line."""
        self.connection.settimeout(self.idle_timeout)
        try:
            self.rfile.peek(1)
        except OSError:
            # Idle past the bound or reset: no request was started, so the connection is simply done.
            self.close_connection = True
            return
        # The first byte of a request has arrived, so a peer that stalls mid-request is bounded like any other.
        self.connection.settimeout(self.request_timeout)
        super().handle_one_request()

    def reply(self, status, value):
        """Return a bounded JSON control response without retaining request contents."""
        body = json.dumps(value).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        """Release a held body or deny future acquisition; never reset the ledger."""
        token = self.path.removeprefix("/__release/")
        if self.path == "/__acquisition-unavailable":
            self.server.acquisition_unavailable.set()
            self.server.ledger.event(None, "control", "acquisition_unavailable")
            self.reply(200, {"acquisition_unavailable": True})
        elif self.path == "/__hold-acquisition":
            for control in self.server.controls.values():
                control.hold_after = 1024
                control.release.clear()
            self.server.ledger.event(None, "control", "hold_acquisition")
            self.reply(200, {"held_after": 1024})
        elif self.path == "/__platform-stop-marker":
            self.server.ledger.event(None, "control", "platform_stop_marker")
            self.reply(200, {"marked": True})
        elif self.path in ("/__background-start", "/__background-end"):
            kind = "background_start" if self.path == "/__background-start" else "background_end"
            self.server.ledger.event(None, "control", kind)
            self.reply(200, {"marked": True})
        elif self.path == "/__pace-background-acquisition":
            for control in self.server.controls.values():
                control.interval = 0.25
            self.server.ledger.event(None, "control", "pace_background_acquisition")
            self.reply(200, {"paced": True})
        elif self.path == "/__hold-unknown-acquisition":
            for control in self.server.controls.values():
                control.hold_after = 2 * 1024 * 1024
                control.interval = 0.01
                control.unknown_length = True
                control.release.clear()
            self.server.ledger.event(None, "control", "hold_unknown_acquisition")
            self.reply(200, {"held_after": 2 * 1024 * 1024, "content_length": False})
        elif self.path == "/__hold-resumable-acquisition":
            for control in self.server.controls.values():
                control.hold_after = 2 * 1024 * 1024
                control.interval = 0.01
                control.release.clear()
            self.server.ledger.event(None, "control", "hold_resumable_acquisition")
            self.reply(200, {"held_after": 2 * 1024 * 1024})
        elif self.path in ("/__interrupt-acquisition", "/__interrupt-changed-validator", "/__interrupt-acquisition-held-resume"):
            changed = self.path == "/__interrupt-changed-validator"
            # Existing responses own the old control. Replacement requests must
            # not inherit its interruption or hold, even if the retry is immediate.
            for token, previous in list(self.server.controls.items()):
                replacement = Control(interval=0.01, hold_after=3 * 1024 * 1024) if self.path.endswith("held-resume") else Control()
                replacement.validator_generation = previous.validator_generation + int(changed)
                self.server.controls[token] = replacement
                previous.interrupt.set()
            self.server.ledger.event(None, "control", "interrupt_acquisition", int(changed))
            self.reply(200, {"interrupted": True, "validator_changed": changed})
        elif self.path == "/__pace-acquisition":
            # Slow every existing body so progress is observable without holding it.
            for control in self.server.controls.values():
                control.interval = PACED_INTERVAL_SECONDS
            self.server.ledger.event(None, "control", "pace_acquisition")
            self.reply(200, {"interval": PACED_INTERVAL_SECONDS})
        elif self.path.startswith("/__marker/"):
            # A numbered boundary in the ledger lets the host attribute requests and bytes to one measured sample.
            try:
                number = int(self.path.removeprefix("/__marker/"))
                if not 0 <= number <= 1_000_000:
                    raise ValueError
            except ValueError:
                self.reply(400, {})
                return
            self.server.ledger.event(None, "control", "marker", number)
            self.reply(200, {"marker": number})
        elif self.path.startswith("/__shape/"):
            # /__shape/<down kbps>/<up kbps>/<latency ms>; zeros restore an unshaped link. Counters are never reset.
            try:
                down, up, latency = (int(part) for part in self.path.removeprefix("/__shape/").split("/"))
                self.server.set_shape(down, up, latency)
            except ValueError:
                self.reply(400, {})
                return
            self.reply(200, {"down_kbps": down, "up_kbps": up, "latency_ms": latency})
        elif self.path == "/__acquisition-not-found":
            self.server.acquisition_not_found.set()
            self.server.ledger.event(None, "control", "acquisition_not_found")
            self.reply(200, {"acquisition_not_found": True})
        elif self.path == "/__restore-acquisition":
            # Lifts a prior denial; counters and failed attempts are never reset.
            self.server.acquisition_unavailable.clear()
            self.server.acquisition_not_found.clear()
            self.server.ledger.event(None, "control", "restore_acquisition")
            self.reply(200, {"acquisition_unavailable": False, "acquisition_not_found": False})
        elif self.path == "/__release-acquisition":
            for control in self.server.controls.values():
                control.release.set()
                control.hold_after = None
            self.server.ledger.event(None, "control", "release_acquisition")
            self.reply(200, {"released": True})
        elif self.path == "/__cancel-marker":
            self.server.ledger.event(None, "control", "cancel_marker")
            self.reply(200, {"marked": True})
        elif self.path.startswith("/__release/") and token in self.server.controls:
            self.server.controls[token].release.set()
            self.reply(200, {"released": True})
        else:
            self.reply(404, {})

    def do_PUT(self):
        """Stream a bounded genuine encrypted upload to disk; no authorization is logged."""
        if self.path != "/upload":
            self.reply(404, {})
            return
        try:
            size = int(self.headers.get("Content-Length", "-1"))
        except ValueError:
            size = -1
        if not 0 <= size <= UPLOAD_MAX_BYTES:
            self.close_connection = True
            self.reply(413, {})
            return
        shape = self.server.shape
        self.server.stopping.wait(shape.latency)
        token = "upload-" + os.urandom(8).hex()
        request = self.server.ledger.event(None, token, "upload", size)
        path = self.server.ledger.root / token
        digest = hashlib.sha256()
        remaining = size
        try:
            with path.open("xb") as output:
                os.chmod(path, 0o600)
                while remaining:
                    chunk = self.rfile.read(min(CHUNK, remaining))
                    if not chunk:
                        raise ConnectionResetError()
                    output.write(chunk)
                    digest.update(chunk)
                    remaining -= len(chunk)
                    self.server.ledger.event(request, token, "upload_bytes", len(chunk))
                    self.server.stopping.wait(shape.delay(len(chunk), shape.up_bps))
                output.flush()
                os.fsync(output.fileno())
            locator = digest.hexdigest()
            existing = self.server.ledger.blob(locator)
            if existing:
                path.unlink()
                token = existing[0]
            else:
                self.server.ledger.register(token, locator, size)
                self.server.controls[token] = Control()
            self.server.ledger.event(request, token, "upload_complete")
            self.reply(200, {"url": f"{self.server.url}/{locator}{self.server.upload_extension}", "sha256": locator,
                             "size": size, "type": "application/octet-stream",
                             "uploaded": int(time.time())})
        except (OSError, TimeoutError):
            path.unlink(missing_ok=True)
            self.server.ledger.event(request, token, "upload_disconnect")
            self.close_connection = True

    def do_HEAD(self):
        """Expose length/validator without counting ciphertext response bytes."""
        self.download(head=True)

    def do_GET(self):
        """Export a complete durable ledger, a host payload, or deliver one counted body attempt."""
        if self.path == "/__ledger":
            self.reply(200, self.server.ledger.snapshot())
        elif self.path.startswith("/__payload/"):
            self.payload(self.path.removeprefix("/__payload/"))
        else:
            self.download()

    def payload(self, token):
        """Stream a host-supplied payload under its own ledger kinds, so it is never a counted acquisition."""
        entry = self.server.payloads.get(token) if TOKEN.fullmatch(token) else None
        if entry is None:
            self.reply(404, {})
            return
        path, size = entry
        request = self.server.ledger.event(None, token, "payload_fetch", size)
        try:
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(size))
            self.end_headers()
            with path.open("rb") as source:
                chunk = source.read(CHUNK)
                while chunk:
                    self.wfile.write(chunk)
                    chunk = source.read(CHUNK)
            self.server.ledger.event(request, token, "payload_complete")
        except (OSError, TimeoutError):
            self.server.ledger.event(request, token, "payload_disconnect")
            self.close_connection = True

    def disconnected(self):
        """Detect peer FIN/reset during a held body, without writing more payload bytes."""
        if select.select([self.connection], [], [], 0)[0]:
            try:
                return self.connection.recv(1, socket.MSG_PEEK) == b""
            except OSError:
                return True
        return False

    def wait(self, seconds, control=None, allow_release=True):
        """Interrupt every wait on disconnect; explicit release bypasses holds but preserves body pacing."""
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            if self.server.stopping.is_set() or self.disconnected() or (control is not None and control.interrupt.is_set()):
                raise ConnectionResetError()
            if allow_release and control is not None and control.release.is_set():
                return
            self.server.stopping.wait(min(0.02, max(0, deadline - time.monotonic())))

    def download(self, head=False):
        """Record attempt, offset, headers, successful writes and a truthful terminal reason."""
        key = self.path[1:]
        if key.endswith(".bin") and HASH.fullmatch(key[:-4]):
            key = key[:-4]
        blob = self.server.ledger.blob(key) if TOKEN.fullmatch(key) or HASH.fullmatch(key) else None
        token, locator, size = blob or ("missing", "", 0)
        ledger = self.server.ledger
        request = ledger.event(None, token, "head" if head else "get")
        if self.server.acquisition_unavailable.is_set():
            ledger.event(request, token, "status", 503)
            self.reply(503, {})
            ledger.event(request, token, "complete")
            return
        if self.server.acquisition_not_found.is_set():
            ledger.event(request, token, "status", 404)
            self.reply(404, {})
            ledger.event(request, token, "complete")
            return
        if blob is None:
            ledger.event(request, token, "status", 404)
            self.reply(404, {})
            ledger.event(request, token, "complete")
            return
        control = self.server.controls.get(token, Control())
        validator = f'"{locator}-{control.validator_generation}"'
        offset = 0
        ranged = False
        range_header = self.headers.get("Range")
        if range_header:
            match = re.fullmatch(r"bytes=(\d+)-", range_header)
            if match:
                ledger.event(request, token, "range_requested_offset", int(match[1]))
            # Retain only the comparison outcome, never opaque HTTP validators.
            ledger.event(request, token, "if_range_match", int(self.headers.get("If-Range") == validator))
        if range_header and self.headers.get("If-Range", validator) == validator:
            match = re.fullmatch(r"bytes=(\d+)-", range_header)
            if not match or int(match[1]) >= size:
                ledger.event(request, token, "status", 416)
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{size}")
                self.send_header("Content-Length", "0")
                self.end_headers()
                ledger.event(request, token, "complete")
                return
            offset = int(match[1])
            ranged = True
        ledger.event(request, token, "range_offset", offset)
        try:
            shape = self.server.shape
            self.wait(control.header_delay + shape.latency, control)
            status = 206 if ranged else 200
            self.send_response(status)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("ETag", validator)
            self.send_header("Accept-Ranges", "bytes")
            if ranged:
                self.send_header("Content-Range", f"bytes {offset}-{size - 1}/{size}")
            if not control.unknown_length:
                self.send_header("Content-Length", str(size - offset))
            else:
                ledger.event(request, token, "unknown_content_length")
                self.send_header("Connection", "close")
                self.close_connection = True
            self.end_headers()
            ledger.event(request, token, "status", status)
            if not head:
                with (ledger.root / token).open("rb") as source:
                    source.seek(offset)
                    while offset < size:
                        if control.hold_after is not None and offset >= control.hold_after:
                            ledger.event(request, token, "held", offset)
                            self.wait(60, control)
                            if not control.release.is_set():
                                ledger.event(request, token, "hold_timeout")
                                self.close_connection = True
                                return
                        limit = CHUNK
                        if control.hold_after is not None and offset < control.hold_after:
                            limit = min(limit, control.hold_after - offset)
                        chunk = source.read(limit)
                        if not chunk:
                            raise OSError("fixture shortened")
                        # The chunk occupies the shaped link before it is delivered, so completion is never paced.
                        self.wait(shape.delay(len(chunk), shape.down_bps), control, allow_release=False)
                        self.wfile.write(chunk)
                        ledger.event(request, token, "body_bytes", len(chunk))
                        offset += len(chunk)
                        if offset < size:
                            self.wait(control.interval, control, allow_release=False)
            ledger.event(request, token, "complete")
        except (OSError, TimeoutError):
            ledger.event(request, token, "disconnect")
            self.close_connection = True


def main():
    """Create a fresh explicit run directory or reopen its ledger without resetting counters."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--port", type=int, default=0)
    args = parser.parse_args()
    server = FixtureServer(args.root, args.port)
    print(json.dumps({"port": server.server_port}), flush=True)
    try:
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
