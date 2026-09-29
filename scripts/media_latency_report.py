#!/usr/bin/env python3
"""Turn guarded media-probe status lines into a bounded, privacy-safe JSON report."""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
from pathlib import Path


SIZES = {"small", "medium", "large", "near_limit"}
OPERATIONS = {"preparation", "upload", "download"}
AGGREGATE_KEYS = {
    "schema",
    "operation",
    "size",
    "payload_bytes_per_sample",
    "samples",
    "successes",
    "failures",
    "payload_bytes_total",
    "network_bytes",
    "duration_ms",
    "peak_heap_bytes",
}
NATIVE_PHASES = {
    "upload",
    "download",
    "queue_wait",
    "preparation",
    "host_setup",
    "response_headers",
    "first_byte",
    "body_transfer",
    "locator_failover",
    "ciphertext_verify",
    "decrypt",
    "plaintext_verify",
}
COMPONENT_PHASES = {
    "fixture_bytes",
    "native_download_ms",
    "native_distinct_backlog_request_ms",
    "native_distinct_backlog_batch_ms",
    "memory_cache_admission_ms",
    "encrypted_cache_admission_ms",
    "encrypted_cache_write_ms",
    "encrypted_cache_read_ms",
    "image_decode_ms",
}
STATUS_PREFIX = "INSTRUMENTATION_STATUS: "
STATUS_CODE = "INSTRUMENTATION_CODE: -1"
KEY_VALUE = re.compile(r"([a-z][a-z0-9_]*)=([0-9.]+|[a-z_]+)")
HEX40 = re.compile(r"[0-9a-f]{40}\Z")
HEX64 = re.compile(r"[0-9a-f]{64}\Z")


def _nonnegative_number(value: object) -> bool:
    return type(value) in (int, float) and math.isfinite(value) and value >= 0


def _validate_aggregate(record: object) -> dict:
    if not isinstance(record, dict) or set(record) != AGGREGATE_KEYS:
        raise ValueError("media aggregate has unexpected keys")
    if record["schema"] != 1 or record["operation"] not in OPERATIONS or record["size"] not in SIZES:
        raise ValueError("media aggregate has unknown schema or labels")
    for key in ("payload_bytes_per_sample", "samples", "successes", "failures", "payload_bytes_total"):
        if type(record[key]) is not int or record[key] < 0:
            raise ValueError(f"media aggregate {key} must be a nonnegative integer")
    if record["samples"] != record["successes"] + record["failures"]:
        raise ValueError("media aggregate sample counts disagree")
    if record["payload_bytes_total"] != record["payload_bytes_per_sample"] * record["successes"]:
        raise ValueError("media aggregate payload counts disagree")
    if record["network_bytes"] is not None:
        raise ValueError("network bytes are not measured by this probe")
    duration = record["duration_ms"]
    heap = record["peak_heap_bytes"]
    if not isinstance(duration, dict) or set(duration) != {"p50", "p95", "max"}:
        raise ValueError("media aggregate has unexpected duration keys")
    if not isinstance(heap, dict) or set(heap) != {"java", "native"}:
        raise ValueError("media aggregate has unexpected heap keys")
    if record["successes"]:
        if not all(_nonnegative_number(value) for value in duration.values()):
            raise ValueError("successful media aggregate has invalid durations")
        if not duration["p50"] <= duration["p95"] <= duration["max"]:
            raise ValueError("media aggregate percentiles are unordered")
    elif any(value is not None for value in duration.values()):
        raise ValueError("failed media aggregate invents duration")
    if not all(type(value) is int and value >= 0 for value in heap.values()):
        raise ValueError("media aggregate has invalid heap peaks")
    return record


def _parse_fixed_fields(line: str, allowed: set[str]) -> dict:
    fields = dict(KEY_VALUE.findall(line))
    if not fields or len(fields) != len(line.split()) or not set(fields) <= allowed:
        raise ValueError("unrecognized media probe status fields")
    return fields


def parse_status(output: str) -> dict:
    """Parse only recognized status keys and numeric values; never copy raw logs."""
    aggregates: list[dict] = []
    native: list[dict] = []
    components: list[dict] = []
    for line in output.splitlines():
        if not line.startswith(STATUS_PREFIX):
            continue
        status = line[len(STATUS_PREFIX) :]
        if status.startswith("media_probe_json="):
            aggregates.append(_validate_aggregate(json.loads(status.partition("=")[2])))
        elif status.startswith("media_probe_native="):
            fields = _parse_fixed_fields(
                status.partition("=")[2],
                {"size", "phase", "attempts", "successes", "failures", "duration_sum_ms", "overflow_count", "upper_bound_ms", "count"},
            )
            if fields.get("phase") not in NATIVE_PHASES or fields.get("size", "unspecified") not in SIZES | {"unspecified"}:
                raise ValueError("unknown native phase or size")
            native.append({key: int(value) if key not in {"size", "phase"} else value for key, value in fields.items()})
        elif status.startswith("media_probe="):
            fields = _parse_fixed_fields(status.partition("=")[2], {"phase", "count", "p50", "p95", "max"})
            if fields.get("phase") not in COMPONENT_PHASES:
                raise ValueError("unknown component phase")
            components.append({key: value if key == "phase" else float(value) for key, value in fields.items()})
    complete = STATUS_CODE in output.splitlines()
    if complete and not (aggregates or native or components):
        raise ValueError("completed instrumentation contained no media measurements")
    return {
        "complete": complete,
        "aggregates": aggregates,
        "native_phases": native,
        "component_metrics": components,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, help="raw am instrument output")
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--mdk-sha", required=True)
    parser.add_argument("--app-apk-sha256", required=True)
    parser.add_argument("--test-apk-sha256", required=True)
    parser.add_argument("--device-class", choices=("emulator", "physical-test-device"), required=True)
    parser.add_argument("--network-profile", choices=("wifi_unshaped", "wifi_constrained", "cellular_unshaped", "offline_resume", "unknown"), required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    for name, value, pattern in (
        ("source-sha", args.source_sha, HEX40),
        ("mdk-sha", args.mdk_sha, HEX40),
        ("app-apk-sha256", args.app_apk_sha256, HEX64),
        ("test-apk-sha256", args.test_apk_sha256, HEX64),
    ):
        if not pattern.fullmatch(value):
            parser.error(f"{name} must be a lowercase full hex digest")
    report = {
        "schema": 1,
        "source_sha": args.source_sha,
        "mdk_sha": args.mdk_sha,
        "app_apk_sha256": args.app_apk_sha256,
        "test_apk_sha256": args.test_apk_sha256,
        "device_class": args.device_class,
        "network_profile": args.network_profile,
        **parse_status(args.input.read_text()),
    }
    args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    return 0 if report["complete"] else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"media probe report rejected: {error}", file=sys.stderr)
        sys.exit(2)
