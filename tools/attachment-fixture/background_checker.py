"""Validate actual elevated Android background continuation without claiming interrupted-job resume."""

import math

PAYLOAD_BYTES = 4 * 1024 * 1024


def check_background(metrics, events, environment, locked=False):
    """Require a stopped Activity, actual execution class, continued host bytes and successful single acquisition."""
    violations = []
    if len([m for m in metrics if m.get("phase") == "platform-background" and m.get("screen_lock_qualified") is locked]) != 1:
        violations.append("missing actual screen-lock classification")
    result = [m for m in metrics if m.get("phase") == "platform-background"]
    if not environment or not str(environment.get("api", "")).isdigit():
        violations.append("missing platform environment")
        execution = None
    else:
        execution = "user-initiated-job" if int(environment["api"]) >= 34 else "foreground-work"
    if len(result) != 1 or any(result[0].get(k) is not True for k in (
        "success", "activity_stopped", "plaintext_exact", "interactive_intent_retired",
        "transfer_active_after_30_seconds",
    )) or result[0].get("actual_execution_class") != execution or result[0].get("platform_job_stop_qualified") is not False:
        violations.append("missing actual platform assertions")
    elif (isinstance(result[0].get("background_millis"), bool)
          or not isinstance(result[0].get("background_millis"), (int, float))
          or not math.isfinite(result[0]["background_millis"]) or result[0]["background_millis"] < 30000):
        violations.append("insufficient actual background interval")
    samples = [m for m in metrics if m.get("phase") == "platform-background-overall"]
    if len(samples) != 1:
        violations.append("missing timing and memory")
    else:
        for field in ("elapsed_ms", "java_peak_bytes", "native_peak_bytes"):
            value = samples[0].get(field)
            if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0:
                violations.append("invalid measured " + field)
        if samples[0].get("success") is not True or samples[0].get("payload_bytes") != PAYLOAD_BYTES:
            violations.append("wrong payload or unsuccessful sample")
    starts = [e["seq"] for e in events if e["kind"] == "background_start"]
    ends = [e["seq"] for e in events if e["kind"] == "background_end"]
    if len(starts) != 1 or len(ends) != 1 or ends[0] <= starts[0]:
        violations.append("missing ordered independent background markers")
    else:
        start_event = next(e for e in events if e["seq"] == starts[0])
        end_event = next(e for e in events if e["seq"] == ends[0])
        body_events = [e for e in events if e["kind"] == "body_bytes" and starts[0] < e["seq"] < ends[0]]
        continued = sum(e["value"] for e in body_events)
        if (end_event.get("at_ns", 0) - start_event.get("at_ns", 0) < 30_000_000_000
                or not any(e.get("at_ns", 0) - start_event.get("at_ns", 0) >= 25_000_000_000 for e in body_events)
                or any(e["kind"] == "complete" and starts[0] < e["seq"] < ends[0] for e in events)):
            violations.append("no independently sustained background body")
        if continued <= 0 or len(result) != 1 or result[0].get("body_bytes_while_backgrounded") != continued:
            violations.append("body did not advance during the verified background interval")
    gets = [e["seq"] for e in events if e["kind"] == "get"]
    uploads = [e["seq"] for e in events if e["kind"] == "upload"]
    if len(gets) != 1 or len(uploads) != 1 or any(e["kind"] in ("head", "disconnect", "range_requested_offset") for e in events):
        violations.append("backgrounding restarted acquisition")
    elif not (
        sum(e["kind"] == "complete" and e["request"] == gets[0] for e in events) == 1
        and sum(e["kind"] == "upload_complete" and e["request"] == uploads[0] for e in events) == 1
    ):
        violations.append("missing independent completion")
    for kind in ("upload_bytes", "body_bytes"):
        if sum(e["value"] for e in events if e["kind"] == kind) != PAYLOAD_BYTES + 16:
            violations.append("incorrect " + kind)
    return {"passed": not violations, "violations": violations,
            "scope": "android-scheduled-background-continuation", "performance_qualified": False,
            "platform_job_stop_qualified": False, "screen_lock_qualified": locked and not violations}
