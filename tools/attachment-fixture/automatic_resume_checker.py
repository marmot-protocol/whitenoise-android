"""Require actual ordinary-worker interruption plus compatible native Range recovery, without process-death claims."""

import math

PAYLOAD_BYTES = 4 * 1024 * 1024
PREFIX_BYTES = 2 * 1024 * 1024


def check_automatic_resume(metrics, events):
    """Successful byte transfer alone cannot qualify a scheduler stop or a resumed ordinary WorkSpec."""
    violations = []
    result = [m for m in metrics if m.get("phase") == "automatic-platform-resume"]
    expected = {"success": True, "same_work_resumed": True, "actual_platform_stop": True,
                "ordinary_work": True, "interactive_intent": False, "deliberate_retry_used": False,
                "plaintext_exact": True, "partial_plaintext_unavailable": True,
                "diagnostics_private": True, "activity_stopped": True, "android_process_restart_qualified": False}
    if len(result) != 1 or any(result[0].get(k) is not v for k, v in expected.items()):
        violations.append("missing actual ordinary-work stop/resume assertions")
    if len(result) == 1:
        api = result[0].get("android_api")
        reason = "unavailable" if isinstance(api, int) and api < 31 else "3"
        if (isinstance(api, bool) or not isinstance(api, int) or api < 30
                or result[0].get("worker_stop_reason") != reason
                or result[0].get("worker_run_attempt") != 0
                or isinstance(result[0].get("worker_run_attempt"), bool)):
            violations.append("missing actual first-run scheduler stop diagnostics")
    samples = [m for m in metrics if m.get("phase") == "automatic-platform-resume-overall"]
    if len(samples) != 1:
        violations.append("missing measured sample")
    else:
        for field in ("elapsed_ms", "java_peak_bytes", "native_peak_bytes"):
            value = samples[0].get(field)
            if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0:
                violations.append("invalid measured " + field)
        if samples[0].get("success") is not True or samples[0].get("payload_bytes") != PAYLOAD_BYTES:
            violations.append("wrong or unsuccessful sample")
    gets = [e["seq"] for e in events if e["kind"] == "get"]
    uploads = [e["seq"] for e in events if e["kind"] == "upload"]
    if len(gets) != 2 or len(uploads) != 1 or any(e["kind"] == "head" for e in events):
        violations.append("wrong request count")
    elif not all((
        any(e["kind"] == "disconnect" and e["request"] == gets[0] for e in events),
        any(e["kind"] == "complete" and e["request"] == gets[1] for e in events),
        any(e["kind"] == "upload_complete" and e["request"] == uploads[0] for e in events),
        not any(e["kind"] == "complete" and e["request"] == gets[0] for e in events),
    )):
        violations.append("missing independent request outcomes")
    for kind, values in {"held": [PREFIX_BYTES, 3 * 1024 * 1024], "range_requested_offset": [PREFIX_BYTES],
                         "if_range_match": [1], "range_offset": [0, PREFIX_BYTES], "status": [200, 206],
                         "interrupt_acquisition": [0], "platform_stop_marker": [0], "release_acquisition": [0]}.items():
        if [e["value"] for e in events if e["kind"] == kind] != values:
            violations.append("incorrect " + kind)
    for kind in ("upload_bytes", "body_bytes"):
        if sum(e["value"] for e in events if e["kind"] == kind) != PAYLOAD_BYTES + 16:
            violations.append("incorrect " + kind)
    markers = [e["seq"] for e in events if e["kind"] == "platform_stop_marker"]
    ranges = [e["seq"] for e in events if e["kind"] == "range_requested_offset"]
    if len(markers) != 1 or len(ranges) != 1 or ranges[0] <= markers[0]:
        violations.append("Range recovery did not follow platform stop")
    return {"passed": not violations, "violations": violations, "scope": "ordinary-android-work-stop-and-native-resume",
            "platform_job_stop_qualified": not violations, "android_process_restart_qualified": False,
            "performance_qualified": False}
