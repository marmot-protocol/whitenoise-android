"""Validate functional byte-only progress independently of performance and UI qualification."""

import math

PAYLOAD_BYTES = 4 * 1024 * 1024


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
    gets = [e["seq"] for e in events if e["kind"] == "get"]
    uploads = [e["seq"] for e in events if e["kind"] == "upload"]
    if len(gets) != 1 or len(uploads) != 1 or any(e["kind"] in ("head", "disconnect", "range_requested_offset") for e in events):
        violations.append("expected one upload and one uninterrupted GET")
    elif not (
        sum(e["kind"] == "complete" and e["request"] == gets[0] for e in events) == 1
        and sum(e["kind"] == "upload_complete" and e["request"] == uploads[0] for e in events) == 1
    ):
        violations.append("missing independent request completion")
    for kind, values in {
        "unknown_content_length": [0], "held": [2 * 1024 * 1024], "status": [200],
        "hold_unknown_acquisition": [0], "release_acquisition": [0], "acquisition_unavailable": [0],
    }.items():
        if [e["value"] for e in events if e["kind"] == kind] != values:
            violations.append("incorrect " + kind)
    for kind in ("upload_bytes", "body_bytes"):
        if sum(e["value"] for e in events if e["kind"] == kind) != PAYLOAD_BYTES + 16:
            violations.append("incorrect " + kind)
    return {"passed": not violations, "violations": violations, "performance_qualified": False,
            "scope": "packaged-native-unknown-length", "manual_ui_qualified": False}
