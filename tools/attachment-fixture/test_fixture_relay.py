"""Real WebSocket framing and Nostr fixture publication/subscription contracts."""

import base64
import json
import os
import socket
import struct
import threading
import unittest

from fixture_relay import FixtureRelay, matches


class RelayContractTest(unittest.TestCase):
    """Prove SDK-facing handshakes and event delivery without any remote service."""

    def test_filters_apply_tags_time_and_kinds(self):
        """Exclude unrelated fixture groups, authors and time windows."""
        event = {"id": "event", "pubkey": "sender", "kind": 445, "created_at": 10, "tags": [["h", "group"]]}
        self.assertTrue(matches(event, {"kinds": [445], "#h": ["group"], "since": 10}))
        self.assertFalse(matches(event, {"authors": ["other"]}))
        self.assertFalse(matches(event, {"#h": ["wrong"]}))
        self.assertFalse(matches(event, {"until": 9}))

    def test_published_event_survives_client_reconnect_and_matches_subscription(self):
        """Reconnect retrieves published events without accepting unrelated tag subscriptions."""
        server = FixtureRelay()
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()

        def connect():
            """Require the HTTP version used by real SDK WebSocket handshakes."""
            client = socket.create_connection(("127.0.0.1", server.port), timeout=5)
            key = base64.b64encode(os.urandom(16)).decode()
            client.sendall((f"GET / HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                            f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n").encode())
            reader = client.makefile("rb")
            self.assertTrue(reader.readline().startswith(b"HTTP/1.1 101 "))
            while reader.readline() != b"\r\n":
                pass
            return client, reader

        def send(client, value):
            """Mask client frames as required by the WebSocket transport contract."""
            body = json.dumps(value).encode()
            mask = os.urandom(4)
            prefix = bytes([0x81, 0x80 | len(body)]) if len(body) < 126 else b"\x81\xfe" + struct.pack("!H", len(body))
            client.sendall(prefix + mask + bytes(v ^ mask[i % 4] for i, v in enumerate(body)))

        def receive(reader):
            """Decode unmasked fixture replies without hiding their opcode or length."""
            first, size = reader.read(2)
            self.assertEqual(0x81, first)
            if size == 126:
                size = struct.unpack("!H", reader.read(2))[0]
            return json.loads(reader.read(size))

        try:
            event = {"id": "event", "pubkey": "sender", "kind": 445, "created_at": 10, "tags": [["h", "group"]]}
            client, reader = connect()
            try:
                send(client, ["EVENT", event])
                self.assertEqual(["OK", "event", True, ""], receive(reader))
            finally:
                reader.close()
                client.close()
            client, reader = connect()
            try:
                send(client, ["REQ", "fixture", {"kinds": [445], "#h": ["group"]}])
                self.assertEqual(["EVENT", "fixture", event], receive(reader))
                self.assertEqual(["EOSE", "fixture"], receive(reader))
                send(client, ["REQ", "denied", {"#h": ["other"]}])
                self.assertEqual(["EOSE", "denied"], receive(reader))
            finally:
                reader.close()
                client.close()
        finally:
            server.shutdown()
            server.server_close()
            thread.join(5)


if __name__ == "__main__":
    unittest.main()
