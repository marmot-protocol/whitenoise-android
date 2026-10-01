"""Measure harness transport only; this is never Android/MDK end-to-end evidence."""

import argparse
import http.client
import json
from pathlib import Path
import resource
import statistics
import sys
import threading
import time
import tracemalloc

from fixture_server import Control, FixtureServer


def measure(root, repetitions=20):
    """Report real HTTP ledger deltas, body latency and host allocation peaks by size."""
    server = FixtureServer(root)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    report = {"schema": 1, "scope": "host-http-harness-only", "samples": []}
    tracemalloc.start()
    try:
        for size in (1024, 65536, 1048576, 8388608, 31457280):
            token = f"size-{size}"
            server.generate(token, size, Control(header_delay=0.05))
            samples = []
            tracemalloc.reset_peak()
            for _ in range(repetitions):
                started = time.monotonic_ns()
                client = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=5)
                try:
                    client.request("GET", "/" + token)
                    response = client.getresponse()
                    header_ms = (time.monotonic_ns() - started) / 1e6
                    received = 0
                    while chunk := response.read(16384):
                        received += len(chunk)
                    assert response.status == 200 and received == size
                    samples.append({"headers_ms": header_ms, "total_ms": (time.monotonic_ns() - started) / 1e6})
                finally:
                    client.close()
            peak = tracemalloc.get_traced_memory()[1]
            deadline = time.monotonic() + 5
            while True:
                events = [e for e in server.ledger.snapshot() if e["fixture"] == token]
                if sum(e["kind"] == "complete" for e in events) == repetitions:
                    break
                if time.monotonic() >= deadline:
                    raise RuntimeError("missing terminal server ledger evidence")
                time.sleep(0.01)
            assert sum(e["value"] for e in events if e["kind"] == "body_bytes") == size * repetitions
            report["samples"].append({
                "payload_bytes": size, "runs": repetitions, "failures": 0,
                "http_requests": sum(e["kind"] == "get" for e in events),
                "successful_body_write_bytes": sum(e["value"] for e in events if e["kind"] == "body_bytes"),
                "headers_p50_ms": statistics.median(s["headers_ms"] for s in samples),
                "total_p50_ms": statistics.median(s["total_ms"] for s in samples),
                "total_p95_ms": sorted(s["total_ms"] for s in samples)[int(repetitions * .95 + .999) - 1],
                "host_python_peak_traced_bytes": peak,
                "raw": samples,
            })
        rss = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
        report["host_process_peak_rss_bytes"] = rss if sys.platform == "darwin" else rss * 1024
        report["android_java_peak_bytes"] = None
        report["android_native_peak_bytes"] = None
        return report
    finally:
        tracemalloc.stop()
        server.shutdown()
        server.server_close()
        thread.join(5)


def main():
    """Require an explicit fresh run root and save aggregate-only evidence."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    args.output.write_text(json.dumps(measure(args.root), indent=2) + "\n")


if __name__ == "__main__":
    main()
