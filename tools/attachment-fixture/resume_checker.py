"""Check genuine native transport resume evidence; never claim Android job lifetime."""

import math

PAYLOAD_BYTES = 4 * 1024 * 1024
PREFIX_BYTES = 2 * 1024 * 1024


def check_resume(metrics, events, scenario):
    """Fail closed on missing transitions, duplicate requests, wrong ranges or partial publication."""
    violations = []
    if not isinstance(metrics, list) or any(not isinstance(m, dict) for m in metrics):
        return {"passed": False, "violations": ["invalid metric collection"],
                "scope": "packaged-native-transport-interruption",
                "performance_qualified": False, "platform_job_stop_qualified": False}
    changed = scenario == "changed-validator"
    if scenario not in ("compatible", "changed-validator"):
        violations.append("unknown resume scenario")
    result = [m for m in metrics if m.get("phase") == "transport-resume"]
    if len(result) != 1 or any(result[0].get(k) is not v for k, v in {
        "success": True, "plaintext_exact": True, "partial_plaintext_unavailable": True,
        "deliberate_retry_used": False, "platform_job_stop_qualified": False,
        "android_process_restart_qualified": False,
    }.items()) or result[0].get("scenario") != scenario or result[0].get("held_prefix_bytes") != PREFIX_BYTES:
        violations.append("missing exact native resume assertions")
    samples = [m for m in metrics if m.get("phase") == "transport-resume-overall"]
    if len(samples) != 1:
        violations.append("missing measured native resume sample")
    else:
        sample = samples[0]
        for field in ("elapsed_ms", "java_peak_bytes", "native_peak_bytes"):
            value = sample.get(field)
            if isinstance(value, bool) or not isinstance(value, (int, float)) or (isinstance(value, float) and not math.isfinite(value)) or value < 0:
                violations.append("invalid measured " + field)
        if sample.get("success") is not True or sample.get("payload_bytes") != PAYLOAD_BYTES:
            violations.append("native resume did not complete the declared payload")
    gets = [e["seq"] for e in events if e["kind"] == "get"]
    uploads = [e["seq"] for e in events if e["kind"] == "upload"]
    if len(gets) != 2 or len(uploads) != 1 or any(e["kind"] == "head" for e in events):
        violations.append("expected one genuine upload and exactly two GET attempts")
    elif not all([
        any(e["kind"] == "disconnect" and e["request"] == gets[0] for e in events),
        any(e["kind"] == "complete" and e["request"] == gets[1] for e in events),
        any(e["kind"] == "upload_complete" and e["request"] == uploads[0] for e in events),
        not any(e["kind"] == "complete" and e["request"] == gets[0] for e in events),
    ]):
        violations.append("missing independently committed request outcomes")
    expected = {
        "held": [PREFIX_BYTES], "range_requested_offset": [PREFIX_BYTES],
        "if_range_match": [int(not changed)], "range_offset": [0, 0 if changed else PREFIX_BYTES],
        "status": [200, 200 if changed else 206], "interrupt_acquisition": [int(changed)],
    }
    for kind, values in expected.items():
        if [e["value"] for e in events if e["kind"] == kind] != values:
            violations.append("incorrect " + kind)
    for kind, total in {
        "upload_bytes": PAYLOAD_BYTES + 16,
        "body_bytes": PAYLOAD_BYTES + 16 + (PREFIX_BYTES if changed else 0),
    }.items():
        if sum(e["value"] for e in events if e["kind"] == kind) != total:
            violations.append("incorrect " + kind)
    if sum(e["kind"] == "acquisition_unavailable" for e in events) != 1:
        violations.append("retained reads did not deny further acquisition")
    return {"passed": not violations, "violations": violations,
            "scope": "packaged-native-transport-interruption",
            "performance_qualified": False, "platform_job_stop_qualified": False}
