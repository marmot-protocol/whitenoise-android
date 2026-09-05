#!/usr/bin/env python3
"""Loopback-only Nostr relay for the opt-in Pixel invite-contention fixture.

Stores test events in memory and can withhold EOSE during a catch-up. It does
not manufacture SDK exceptions. Requires websockets; forward both ports with
adb reverse. The control port accepts one JSON command per connection.
"""

import asyncio
import json
import time

from websockets.asyncio.server import serve


class Relay:
    """In-memory fixture relay with a separately controlled catch-up stall."""

    def __init__(self):
        """Start with no account events, subscriptions, or pending response hold."""
        self.events = {}
        self.subscriptions = {}
        self.hold_seconds = 0
        self.hold_until = 0
        self.blocked = 0
        self.sequence = 0

    def log(self, stage, **fields):
        """Print aggregate fixture stages without account identifiers or payloads."""
        print(json.dumps({"time": round(time.time(), 3), "stage": stage, **fields}), flush=True)

    @staticmethod
    def matches(event, filters):
        """Match the Nostr filter fields used by the fixture's real SDK clients."""
        for key, values in filters.items():
            if key in ("ids", "authors"):
                actual = event["id" if key == "ids" else "pubkey"]
                if not any(actual.startswith(prefix) for prefix in values):
                    return False
            elif key == "kinds" and event["kind"] not in values:
                return False
            elif key == "since" and event["created_at"] < values:
                return False
            elif key == "until" and event["created_at"] > values:
                return False
            elif key.startswith("#") and not any(
                len(tag) > 1 and tag[0] == key[1:] and tag[1] in values
                for tag in event.get("tags", [])
            ):
                return False
        return True

    async def eose(self, socket, subscription):
        """Withhold EOSE until the armed interval expires or control releases it."""
        if self.hold_seconds:
            if not self.hold_until:
                self.hold_until = time.monotonic() + self.hold_seconds
            if time.monotonic() < self.hold_until:
                self.blocked += 1
                self.log("eose_held", blocked=self.blocked)
                while time.monotonic() < self.hold_until:
                    await asyncio.sleep(0.05)
                self.log("eose_released")
        if subscription in self.subscriptions.get(socket, {}):
            await socket.send(json.dumps(["EOSE", subscription]))

    async def connection(self, socket):
        """Serve historical and live events, cancelling owned work on disconnect."""
        self.subscriptions[socket] = {}
        pending = set()
        try:
            async for message in socket:
                data = json.loads(message)
                if data[0] == "EVENT":
                    event = data[1]
                    self.events[event["id"]] = event
                    self.log("event", kind=event["kind"], total=len(self.events))
                    await socket.send(json.dumps(["OK", event["id"], True, ""]))
                    for peer, subscriptions in list(self.subscriptions.items()):
                        for subscription, filters in list(subscriptions.items()):
                            if any(self.matches(event, item) for item in filters):
                                await peer.send(json.dumps(["EVENT", subscription, event]))
                elif data[0] == "REQ":
                    subscription, filters = data[1], data[2:]
                    self.subscriptions[socket][subscription] = filters
                    self.sequence += 1
                    self.log("req", sequence=self.sequence, kinds=[f.get("kinds") for f in filters])
                    selected = {}
                    for item in filters:
                        matches = sorted(
                            (e for e in self.events.values() if self.matches(e, item)),
                            key=lambda e: (e["created_at"], e["id"]), reverse=True,
                        )
                        for event in matches[:item.get("limit", len(matches))]:
                            selected[event["id"]] = event
                    for event in selected.values():
                        await socket.send(json.dumps(["EVENT", subscription, event]))
                    task = asyncio.create_task(self.eose(socket, subscription))
                    pending.add(task)
                    task.add_done_callback(pending.discard)
                elif data[0] == "CLOSE":
                    self.subscriptions[socket].pop(data[1], None)
        finally:
            self.subscriptions.pop(socket, None)
            for task in pending:
                task.cancel()

    async def control(self, reader, writer):
        """Handle one local hold, release, or status command and close its socket."""
        try:
            request = await reader.readline()
            if not request:
                return
            command = json.loads(request)
            if command["command"] == "hold":
                self.hold_seconds = command["millis"] / 1000
                self.hold_until = 0
                self.blocked = 0
                self.log("hold_armed", seconds=self.hold_seconds)
            elif command["command"] == "release":
                self.hold_seconds = 0
                self.hold_until = 0
                self.log("hold_released")
            elif command["command"] != "status":
                raise ValueError("unknown control command")
            writer.write(json.dumps({"blocked": self.blocked, "events": len(self.events)}).encode() + b"\n")
            await writer.drain()
        finally:
            writer.close()
            await writer.wait_closed()


async def main():
    """Bind both fixture ports to loopback for access through adb reverse only."""
    relay = Relay()
    async with serve(relay.connection, "127.0.0.1", 19488, max_size=16 * 1024 * 1024):
        control = await asyncio.start_server(relay.control, "127.0.0.1", 19489)
        async with control:
            relay.log("ready", relay_port=19488, control_port=19489)
            await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(main())
