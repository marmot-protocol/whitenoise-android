"""Disposable loopback Nostr relay for generated identities; no user endpoints or logs."""

import base64
import hashlib
import json
import socketserver
import struct
import threading
from http.server import BaseHTTPRequestHandler

MAX_FRAME = 2 * 1024 * 1024


def matches(event, query):
    """Apply the Nostr filters used by the fixture identities, with OR across filters."""
    for field in ("ids", "authors", "kinds"):
        if field in query and event.get({"ids": "id", "authors": "pubkey", "kinds": "kind"}[field]) not in query[field]:
            return False
    if event.get("created_at", 0) < query.get("since", 0):
        return False
    if event.get("created_at", 0) > query.get("until", 2**63):
        return False
    return all(any(len(tag) > 1 and tag[0] == key[1:] and tag[1] in values
                   for tag in event.get("tags", []))
               for key, values in query.items() if key.startswith("#"))


class FixtureRelay(socketserver.ThreadingTCPServer):
    """Retain fixture events while app clients disconnect; discard only on explicit run end."""

    allow_reuse_address = True
    daemon_threads = True

    def __init__(self, port=0):
        self.events = {}
        self.clients = set()
        self.lock = threading.RLock()
        super().__init__(("127.0.0.1", port), RelayHandler)

    @property
    def port(self):
        """Expose the ephemeral port to the device's explicit reverse mapping."""
        return self.server_address[1]


class RelayHandler(BaseHTTPRequestHandler):
    """Serve masked WebSocket Nostr frames with bounded lengths and no event logging."""

    protocol_version = "HTTP/1.1"

    def log_message(self, *_):
        """Keep generated keys, message ids and ciphertext out of terminal output."""

    def send_frame(self, value, opcode=1):
        """Serialize writes so live events cannot interleave with subscription replies."""
        body = json.dumps(value).encode() if opcode == 1 else value
        prefix = bytes([0x80 | opcode])
        if len(body) < 126:
            prefix += bytes([len(body)])
        elif len(body) < 65536:
            prefix += b"\x7e" + struct.pack("!H", len(body))
        else:
            prefix += b"\x7f" + struct.pack("!Q", len(body))
        with self.write_lock:
            self.connection.sendall(prefix + body)

    def read_exact(self, size):
        """Reject truncated frames rather than parsing partial event contents."""
        result = self.rfile.read(size)
        if len(result) != size:
            raise ConnectionResetError()
        return result

    def frame(self):
        """Require complete masked client frames and cap their allocations."""
        first, second = self.read_exact(2)
        if not first & 0x80 or not second & 0x80:
            raise ValueError("unsupported fixture frame")
        size = second & 0x7f
        if size == 126:
            size = struct.unpack("!H", self.read_exact(2))[0]
        elif size == 127:
            size = struct.unpack("!Q", self.read_exact(8))[0]
        if size > MAX_FRAME:
            raise ValueError("fixture frame too large")
        mask = self.read_exact(4)
        body = bytes(value ^ mask[index % 4] for index, value in enumerate(self.read_exact(size)))
        return first & 0x0f, body

    def deliver(self, event):
        """Broadcast an accepted event to each matching live subscription."""
        for subscription, filters in list(self.subscriptions.items()):
            if any(matches(event, query) for query in filters):
                self.send_frame(["EVENT", subscription, event])

    def message(self, value):
        """Handle only EVENT/REQ/CLOSE; this test relay deliberately has no production auth."""
        if value[0] == "EVENT":
            event = value[1]
            with self.server.lock:
                self.server.events[event["id"]] = event
                for client in list(self.server.clients):
                    try:
                        client.deliver(event)
                    except OSError:
                        self.server.clients.discard(client)
            self.send_frame(["OK", event["id"], True, ""])
        elif value[0] == "REQ":
            subscription, filters = value[1], value[2:]
            with self.server.lock:
                self.subscriptions[subscription] = filters
                events = sorted(self.server.events.values(), key=lambda e: e["created_at"], reverse=True)
                selected = {}
                for query in filters:
                    matching = [e for e in events if matches(e, query)][:query.get("limit", 10000)]
                    selected.update({e["id"]: e for e in matching})
                for event in selected.values():
                    self.send_frame(["EVENT", subscription, event])
                self.send_frame(["EOSE", subscription])
        elif value[0] == "CLOSE":
            with self.server.lock:
                self.subscriptions.pop(value[1], None)

    def do_GET(self):
        """Upgrade a loopback connection and remove its subscriptions on every exit."""
        if self.headers.get("Upgrade", "").lower() != "websocket":
            self.send_error(400)
            return
        key = self.headers.get("Sec-WebSocket-Key", "")
        accept = base64.b64encode(hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest())
        self.send_response(101)
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", accept.decode())
        self.end_headers()
        self.write_lock = threading.Lock()
        self.subscriptions = {}
        with self.server.lock:
            self.server.clients.add(self)
        try:
            while True:
                opcode, body = self.frame()
                if opcode == 8:
                    self.send_frame(b"", 8)
                    break
                if opcode == 9:
                    self.send_frame(body, 10)
                elif opcode == 1:
                    self.message(json.loads(body))
        except (OSError, ValueError, KeyError, IndexError, TypeError):
            pass
        finally:
            with self.server.lock:
                self.server.clients.discard(self)
            self.close_connection = True
