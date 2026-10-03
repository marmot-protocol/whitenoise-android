"""Aggregate, check and compare controlled attachment latency matrices; every value is a privacy-safe measurement."""

import math

CIPHERTEXT_OVERHEAD = 16
SCHEMA = 1
NS_PER_MS = 1_000_000.0
# Cell metrics that are lower-is-better latencies, in milliseconds.
LATENCY_METRICS = ("prep_visible_ms", "upload_publish_ms", "mdk_upload_ms", "server_upload_ms", "admission_ms",
                   "first_progress_ms", "body_complete_ms", "transfer_overhead_ms", "ready_ms", "feed_ready_ms",
                   "lease_ms", "post_ready_ms",
                   "verify_decrypt_ms", "subscription_delay_ms", "materialize_ms", "warm_lease_ms", "cold_elapsed_ms")
# What the reader waits for through the shipping path. A candidate is rejected only for a slower value here; the rest
# (the probe's own raw feed recorder, the server's view, the derived splits) describe the engine and explain the cause.
GATING_METRICS = ("prep_visible_ms", "upload_publish_ms", "admission_ms", "first_progress_ms", "body_complete_ms",
                  "transfer_overhead_ms", "ready_ms", "lease_ms", "post_ready_ms", "warm_lease_ms")
# Which layer a latency belongs to when attributing a dominant delay.
LAYER = {"prep_visible_ms": "android-preparation-ui", "mdk_upload_ms": "ffi-mdk", "server_upload_ms": "transport",
         "admission_ms": "ffi-mdk", "transport_ms": "transport", "verify_decrypt_ms": "ffi-mdk",
         "subscription_delay_ms": "ffi-mdk", "materialize_ms": "android-storage"}
UPLOAD_PARTS = ("prep_visible_ms", "mdk_upload_ms", "server_upload_ms")
DOWNLOAD_PARTS = ("admission_ms", "transport_ms", "verify_decrypt_ms", "subscription_delay_ms", "materialize_ms")
REQUIRED = ("prep_visible_ms", "upload_publish_ms", "ready_ms", "lease_ms", "warm_lease_ms")
SHAPE_TOLERANCE = 1.15
MIN_SHAPED_BYTES = 1024 * 1024
P95_MINIMUM = 20


def _finite(value):
    """Accept only measured, non-negative, finite numbers; booleans are never measurements."""
    return (not isinstance(value, bool) and isinstance(value, (int, float))
            and not (isinstance(value, float) and not math.isfinite(value)) and value >= 0)


def segment(events, marker):
    """The ledger events strictly between marker N and marker N+1, so a sample owns exactly its own requests."""
    start = next((i for i, e in enumerate(events) if e["kind"] == "marker" and e["value"] == marker), None)
    end = next((i for i, e in enumerate(events) if e["kind"] == "marker" and e["value"] == marker + 1), None)
    return None if start is None or end is None or end < start else events[start + 1:end]


def _server(events, size):
    """Server-side counts, bytes and wall-clock durations of one sample, from independently committed events."""
    uploads = [e for e in events if e["kind"] == "upload"]
    gets = [e for e in events if e["kind"] == "get"]
    by_request = {}
    for event in events:
        by_request.setdefault(event["request"], {}).setdefault(event["kind"], event)
    out = {"uploads": len(uploads), "gets": len(gets), "heads": sum(e["kind"] == "head" for e in events),
           "upload_bytes": sum(e["value"] for e in events if e["kind"] == "upload_bytes"),
           "body_bytes": sum(e["value"] for e in events if e["kind"] == "body_bytes"),
           "statuses": [e["value"] for e in events if e["kind"] == "status"],
           "completed": sum(e["kind"] == "complete" for e in events),
           "uploaded_complete": sum(e["kind"] == "upload_complete" for e in events)}
    if len(uploads) == 1 and out["uploaded_complete"] == 1:
        done = by_request.get(uploads[0]["seq"], {}).get("upload_complete")
        if done:
            out["server_upload_ms"] = (done["at_ns"] - uploads[0]["at_ns"]) / NS_PER_MS
    if len(gets) == 1:
        rows = by_request.get(gets[0]["seq"], {})
        if "status" in rows and "complete" in rows:
            out["server_header_ms"] = (rows["status"]["at_ns"] - gets[0]["at_ns"]) / NS_PER_MS
            out["server_body_ms"] = (rows["complete"]["at_ns"] - rows["status"]["at_ns"]) / NS_PER_MS
    out["expected_ciphertext"] = size + CIPHERTEXT_OVERHEAD
    return out


def build_samples(profile):
    """Join each device sample with its measured stages and its own ledger segment; absent evidence stays absent."""
    rows = profile["foreground_metrics"]
    measures = {m["phase"]: m for m in rows if isinstance(m.get("phase"), str) and "elapsed_ms" in m}
    samples = []
    for row in (m for m in rows if m.get("phase") == "matrix-sample"):
        marker = row["marker"]
        sample = {k: v for k, v in row.items() if k != "phase"}
        for stage, prefix in (("upload", "upload"), ("cold", "cold"), ("warm", "warm")):
            measure = measures.get(f"matrix-{stage}-{marker}")
            if measure:
                sample[f"{prefix}_success"] = measure.get("success")
                sample[f"{prefix}_elapsed_ms"] = measure.get("elapsed_ms")
                sample[f"{prefix}_java_peak_bytes"] = measure.get("java_peak_bytes")
                sample[f"{prefix}_native_peak_bytes"] = measure.get("native_peak_bytes")
        window = segment(profile["ledger"], marker)
        sample["ledger_segment"] = window is not None
        sample.update(_server(window or [], row["size"]))
        if _finite(sample.get("upload_publish_ms")) and _finite(sample.get("server_upload_ms")):
            sample["mdk_upload_ms"] = max(0.0, sample["upload_publish_ms"] - sample["server_upload_ms"])
        if _finite(sample.get("ready_ms")) and _finite(sample.get("body_complete_ms")):
            sample["verify_decrypt_ms"] = max(0.0, sample["ready_ms"] - sample["body_complete_ms"])
        # The authoritative state turns READY before the subscription delivers it; the gap is a delivery delay, not
        # Android work. Only the time after delivery, until the lease exists, is materialization.
        delivered = sample.get("feed_ready_ms") if _finite(sample.get("feed_ready_ms")) else sample.get("ready_ms")
        if _finite(sample.get("ready_ms")) and _finite(delivered):
            sample["subscription_delay_ms"] = max(0.0, delivered - sample["ready_ms"])
        if _finite(sample.get("lease_ms")) and _finite(delivered):
            sample["materialize_ms"] = max(0.0, sample["lease_ms"] - delivered)
        # What the shipping path adds after the engine's authoritative READY: delivery plus materialization.
        if _finite(sample.get("lease_ms")) and _finite(sample.get("ready_ms")):
            sample["post_ready_ms"] = max(0.0, sample["lease_ms"] - sample["ready_ms"])
        if _finite(sample.get("body_complete_ms")) and _finite(sample.get("admission_ms")):
            sample["transport_ms"] = max(0.0, sample["body_complete_ms"] - sample["admission_ms"])
        # What the client adds to the server's own time to answer and send the body: the cost beyond the link.
        if (_finite(sample.get("body_complete_ms")) and _finite(sample.get("server_header_ms"))
                and _finite(sample.get("server_body_ms"))):
            sample["transfer_overhead_ms"] = max(
                0.0, sample["body_complete_ms"] - sample["server_header_ms"] - sample["server_body_ms"])
        samples.append(sample)
    return samples


def _percentile(values, fraction):
    """Nearest-rank percentile of a non-empty list."""
    ordered = sorted(values)
    return ordered[max(0, math.ceil(fraction * len(ordered)) - 1)]


def stats(values):
    """Distribution of one metric; p95 is reported only when the sample is large enough to mean something."""
    values = [v for v in values if _finite(v)]
    if not values:
        return None
    return {"n": len(values), "min": min(values), "p50": _percentile(values, 0.5),
            "p95": _percentile(values, 0.95) if len(values) >= P95_MINIMUM else None,
            "max": max(values), "mean": sum(values) / len(values)}


def attribute(cell, parts):
    """The dominant component by median, with each component's share, so an optimization targets the right layer."""
    medians = {p: cell[p]["p50"] for p in parts if cell.get(p)}
    total = sum(medians.values())
    if not medians or total <= 0:
        return None
    dominant = max(medians, key=medians.get)
    return {"dominant": dominant, "layer": LAYER[dominant], "median_ms": medians[dominant],
            "shares": {p: round(v / total, 4) for p, v in medians.items()}}


def aggregate(raw):
    """Group samples by profile and size into distributions, attributions and correctness counters.

    Accepts one raw matrix or several repeats of the same matrix; repeats are pooled at the sample level, so the
    observed spread includes run-to-run variation.
    """
    raws = raw if isinstance(raw, list) else [raw]
    by_profile = {}
    for run in raws:
        for profile in run["profiles"]:
            entry = by_profile.setdefault(profile["name"], {"shape": profile["shape"], "samples": [], "recreated": {}})
            entry["samples"].extend(build_samples(profile))
            for m in profile["recreated_metrics"]:
                if m.get("phase") == "matrix-recreated":
                    entry["recreated"].setdefault(m["size"], []).append(m["native_lease_ms"])
    profiles = []
    for name, entry in by_profile.items():
        by_size = {}
        for sample in entry["samples"]:
            by_size.setdefault(sample["size"], []).append(sample)
        cells = []
        for size, samples in sorted(by_size.items()):
            cell = {"size": size, "samples": len(samples)}
            for metric in LATENCY_METRICS:
                cell[metric] = stats([s.get(metric) for s in samples])
            for field in ("upload_java_peak_bytes", "upload_native_peak_bytes", "cold_java_peak_bytes",
                          "cold_native_peak_bytes", "transport_ms"):
                cell[field] = stats([s.get(field) for s in samples])
            cell["requests"] = {"uploads": sum(s["uploads"] for s in samples), "gets": sum(s["gets"] for s in samples),
                                "retries": sum(max(0, s["gets"] - 1) for s in samples)}
            cell["bytes"] = {"uploaded": sum(s["upload_bytes"] for s in samples),
                             "downloaded": sum(s["body_bytes"] for s in samples)}
            cell["attribution"] = {"upload": attribute(cell, UPLOAD_PARTS), "download": attribute(cell, DOWNLOAD_PARTS)}
            cells.append(cell)
        profiles.append({"name": name, "shape": entry["shape"], "cells": cells,
                         "recreated_native_lease_ms": {size: stats(values) for size, values in entry["recreated"].items()}})
    return {"schema": SCHEMA, "environment": raws[0]["environment"], "runs": len(raws), "profiles": profiles}


def check_matrix(raw):
    """Fail closed on a missing sample, a retry, an inexact byte count, a false shape label or a missing restart read."""
    violations = []
    if not isinstance(raw, dict) or not isinstance(raw.get("profiles"), list) or not raw["profiles"]:
        return {"passed": False, "violations": ["invalid matrix"], "scope": "controlled-attachment-latency-matrix",
                "performance_qualified": False}
    for profile in raw["profiles"]:
        name = profile.get("name")
        samples = build_samples(profile)
        planned = sum(reps for _, reps in profile["plan"])
        if len(samples) != planned or sorted(s["marker"] for s in samples) != list(range(1, planned + 1)):
            violations.append(f"{name}: expected {planned} samples with consecutive markers")
        for sample in samples:
            _check_sample(name, sample, profile["shape"], violations)
        _check_recreated(name, profile, violations)
    return {"passed": not violations, "violations": violations, "scope": "controlled-attachment-latency-matrix",
            "performance_qualified": False}


def _check_sample(name, sample, shape, violations):
    """One sample: its own ledger segment, exact bytes, one acquisition, finite measurements and a true link shape."""
    label = f"{name} size={sample.get('size')} marker={sample.get('marker')}"
    if (not sample["ledger_segment"] or sample["uploads"] != 1 or sample["uploaded_complete"] != 1
            or sample["upload_bytes"] != sample["expected_ciphertext"]):
        violations.append(f"{label}: expected exactly one complete upload of the exact ciphertext")
    if (sample["gets"] != 1 or sample["heads"] != 0 or sample["statuses"] != [200] or sample["completed"] != 1
            or sample["body_bytes"] != sample["expected_ciphertext"]):
        violations.append(f"{label}: expected exactly one successful acquisition and no retry or retained refetch")
    for field in REQUIRED:
        if not _finite(sample.get(field)):
            violations.append(f"{label}: missing {field}")
    for prefix in ("upload", "cold", "warm"):
        if sample.get(f"{prefix}_success") is not True or not all(
                _finite(sample.get(f"{prefix}_{k}")) for k in ("elapsed_ms", "java_peak_bytes", "native_peak_bytes")):
            violations.append(f"{label}: missing or failed {prefix} measurement")
    if sample["size"] >= MIN_SHAPED_BYTES:
        _check_shape(label, sample, shape, violations)


def _check_shape(label, sample, shape, violations):
    """A constrained label is true only if the server-observed throughput stays within the declared link."""
    for direction, kbps, nbytes, millis in (
            ("download", shape["down_kbps"], sample["body_bytes"], sample.get("server_body_ms")),
            ("upload", shape["up_kbps"], sample["upload_bytes"], sample.get("server_upload_ms"))):
        if kbps and (not _finite(millis) or millis <= 0
                     or nbytes * 8 / (millis / 1000) > kbps * 1000 * SHAPE_TOLERANCE):
            violations.append(f"{label}: {direction} exceeded the declared {kbps} kbit/s link")


def _check_recreated(name, profile, violations):
    """Each kept size is read exactly after restart, and the server saw no acquisition after the boundary."""
    sizes = sorted({size for size, _ in profile["plan"]})
    rows = profile["recreated_metrics"]
    for size in sizes:
        for stage in ("matrix-recreated-native", "matrix-recreated-resolver"):
            row = next((m for m in rows if m.get("phase") == f"{stage}-{size}"), None)
            if (row is None or row.get("success") is not True
                    or not all(_finite(row.get(k)) for k in ("elapsed_ms", "java_peak_bytes", "native_peak_bytes"))):
                violations.append(f"{name}: missing exact {stage} for size {size}")
        if not any(m.get("phase") == "matrix-recreated" and m.get("size") == size
                   and _finite(m.get("native_lease_ms")) for m in rows):
            violations.append(f"{name}: missing restart lease timing for size {size}")
    if not any(m.get("phase") == "matrix-recreated-complete" and m.get("items") == len(sizes) for m in rows):
        violations.append(f"{name}: the restart stage did not verify every kept size")
    boundary = profile.get("boundary")
    if not isinstance(boundary, int) or isinstance(boundary, bool) or not 0 < boundary <= len(profile["ledger"]):
        violations.append(f"{name}: missing process boundary")
    elif any(e["kind"] in ("get", "head") for e in profile["ledger"][boundary:]):
        violations.append(f"{name}: a retained read acquired over HTTP after restart")


def compare(baseline, candidate):
    """Per-cell median change, flagged only when it exceeds both run spread and 10 percent, plus correctness equality."""
    result = {"cells": [], "correctness_diffs": []}
    base = {(p["name"], c["size"]): c for p in baseline["profiles"] for c in p["cells"]}
    cand = {(p["name"], c["size"]): c for p in candidate["profiles"] for c in p["cells"]}
    for key in sorted(set(base) & set(cand)):
        before, after = base[key], cand[key]
        if before["requests"] != after["requests"] or before["bytes"] != after["bytes"] \
                or before["samples"] != after["samples"]:
            result["correctness_diffs"].append({"cell": key, "before": before["requests"], "after": after["requests"]})
        for metric in LATENCY_METRICS:
            a, b = before.get(metric), after.get(metric)
            if not a or not b:
                continue
            delta = b["p50"] - a["p50"]
            spread = max(a["max"] - a["min"], b["max"] - b["min"])
            material = abs(delta) > spread and abs(delta) > 0.10 * a["p50"] and min(a["n"], b["n"]) >= 3
            verdict = "unchanged" if not material else ("faster" if delta < 0 else "slower")
            result["cells"].append({"profile": key[0], "size": key[1], "metric": metric, "baseline_p50": a["p50"],
                                    "candidate_p50": b["p50"], "delta_ms": delta, "spread_ms": spread,
                                    "verdict": verdict, "gating": metric in GATING_METRICS})
    gating = [c for c in result["cells"] if c["gating"]]
    result["faster"] = [c for c in gating if c["verdict"] == "faster"]
    result["slower"] = [c for c in gating if c["verdict"] == "slower"]
    result["diagnostic_changes"] = [c for c in result["cells"] if not c["gating"] and c["verdict"] != "unchanged"]
    result["accepted"] = not result["correctness_diffs"] and not result["slower"]
    return result
