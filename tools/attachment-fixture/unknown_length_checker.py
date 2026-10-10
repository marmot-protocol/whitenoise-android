"""Validate functional byte-only progress independently of performance and UI qualification."""

import math

PAYLOAD_BYTES = 4 * 1024 * 1024
# The fixture writes paced 16-KiB chunks. Allow four writes to race the client's
# header-only disconnect; this is a fixture scheduling allowance, not an MDK guarantee.
PROBE_MAX_BYTES = 4 * 16 * 1024


def check_unknown_length(metrics, events):
    """Require an actual unknown response, native observations, exact plaintext and no repeated acquisition."""
    violations = []
    if not isinstance(metrics, list) or any(not isinstance(m, dict) for m in metrics):
        return {"passed": False, "violations": ["invalid metrics"], "performance_qualified": False}
    result = [m for m in metrics if m.get("phase") == "unknown-length"]
    if len(result) != 1 or any(result[0].get(k) is not True for k in (
        "success", "total_unknown", "fraction_unknown", "partial_plaintext_unavailable", "plaintext_exact",
        "platform_progress_semantics_qualified",
    )) or result[0].get("retained_reads") != 3:
        violations.append("missing native unknown-length assertions")
    else:
        received = result[0].get("observed_received_bytes")
        if isinstance(received, bool) or not isinstance(received, int) or not 1024 * 1024 <= received <= 2 * 1024 * 1024:
            violations.append("missing genuine positive held progress")
    samples = [m for m in metrics if m.get("phase") == "unknown-length-overall"]
    if len(samples) != 1:
        violations.append("missing measured native sample")
    else:
        sample = samples[0]
        for field in ("elapsed_ms", "java_peak_bytes", "native_peak_bytes"):
            value = sample.get(field)
            if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0:
                violations.append("invalid measured " + field)
        if sample.get("success") is not True or sample.get("payload_bytes") != PAYLOAD_BYTES:
            violations.append("wrong payload or unsuccessful sample")
    violations.extend(check_unknown_length_requests(events))
    return {"passed": not violations, "violations": violations, "performance_qualified": False,
            "scope": "packaged-native-unknown-length", "manual_ui_qualified": False}


def check_unknown_length_requests(events):
    """Correlate a bounded header probe and one completed file transfer, including retained-read quietness."""
    violations = []
    sequences = [e["seq"] for e in events]
    if any(isinstance(v, bool) or not isinstance(v, int) for v in sequences) or sequences != sorted(set(sequences)):
        return ["invalid event ordering"]
    gets = [e["seq"] for e in events if e["kind"] == "get"]
    uploads = [e["seq"] for e in events if e["kind"] == "upload"]
    if len(gets) != 2 or len(uploads) != 1:
        return ["expected one upload, one header probe and one completed file GET"]
    probe, transfer = gets
    upload = uploads[0]
    if any(e["kind"] in ("head", "range_requested_offset", "if_range_match", "hold_timeout") for e in events):
        violations.append("unexpected HEAD, range or hold timeout")
    request_kinds = {"body_bytes", "unknown_content_length", "status", "range_offset", "held", "disconnect", "complete"}
    if any(e["kind"] in request_kinds and e["request"] not in gets for e in events):
        violations.append("uncorrelated download evidence")
    if any(e["kind"] in ("upload_bytes", "upload_complete") and e["request"] != upload for e in events):
        violations.append("uncorrelated upload evidence")

    def rows(request, kind):
        return [e for e in events if e["request"] == request and e["kind"] == kind]

    def values(request, kind):
        return [e["value"] for e in rows(request, kind)]

    for request in gets:
        for kind, expected in {"unknown_content_length": [0], "status": [200], "range_offset": [0]}.items():
            if values(request, kind) != expected:
                violations.append("incorrect per-request " + kind)
        if any(e["seq"] <= request for e in events if e["kind"] in request_kinds and e["request"] == request):
            violations.append("response evidence precedes its request")
    if values(probe, "disconnect") != [0] or rows(probe, "complete") or rows(probe, "held"):
        violations.append("header probe must disconnect without completing or reaching the hold")
    probe_chunks = values(probe, "body_bytes")
    if any(isinstance(v, bool) or not isinstance(v, int) or not 0 < v <= 16 * 1024 for v in probe_chunks):
        violations.append("invalid header-probe chunk")
    elif sum(probe_chunks) > PROBE_MAX_BYTES:
        violations.append("excessive header-probe body bytes")
    for request, terminal in ((probe, "disconnect"), (transfer, "complete")):
        terminals = rows(request, terminal)
        if len(terminals) == 1 and any(e["seq"] > terminals[0]["seq"] for e in events
                                       if e["kind"] in request_kinds and e["request"] == request):
            violations.append("response evidence follows its terminal")
    if rows(transfer, "disconnect") or values(transfer, "complete") != [0]:
        violations.append("file GET must complete once without disconnecting")
    if values(transfer, "held") != [2 * 1024 * 1024]:
        violations.append("missing completed transfer hold")
    for request, kind in ((upload, "upload_bytes"), (transfer, "body_bytes")):
        chunks = values(request, kind)
        if any(isinstance(v, bool) or not isinstance(v, int) or v <= 0 for v in chunks) or sum(chunks) != PAYLOAD_BYTES + 16:
            violations.append("incorrect per-request " + kind)
    if values(upload, "upload_complete") != [0]:
        violations.append("missing upload completion")
    controls = {}
    for kind in ("hold_unknown_acquisition", "release_acquisition", "acquisition_unavailable"):
        matched = [e for e in events if e["kind"] == kind]
        if len(matched) != 1 or matched[0]["value"] != 0 or matched[0]["request"] is not None:
            violations.append("incorrect " + kind)
        else:
            controls[kind] = matched[0]["seq"]
    held = rows(transfer, "held")
    completed = rows(transfer, "complete")
    if len(controls) == 3 and len(held) == len(completed) == 1:
        if not (controls["hold_unknown_acquisition"] < probe < transfer < held[0]["seq"]
                < controls["release_acquisition"] < completed[0]["seq"] < controls["acquisition_unavailable"]):
            violations.append("invalid probe, transfer or retained-read ordering")
    return violations
