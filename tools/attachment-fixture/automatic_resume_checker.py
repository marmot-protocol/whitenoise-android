"""Require actual ordinary-worker interruption plus compatible native Range recovery, without process-death claims."""

import math
import re

PAYLOAD_BYTES = 4 * 1024 * 1024
PREFIX_BYTES = 2 * 1024 * 1024


def _closed(value):
    """A step or exception name kept to a short closed alphabet, so a failure row can never carry free text."""
    return value if isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9_-]{1,64}", value) else "unknown"


def check_automatic_resume(metrics, events):
    """Successful byte transfer alone cannot qualify a scheduler stop or a resumed ordinary WorkSpec."""
    violations = []
    for failure in (m for m in metrics if m.get("phase") == "automatic-platform-resume-failure"):
        violations.append(f"the probe failed at step {_closed(failure.get('step'))} ({_closed(failure.get('exception'))})")
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
    queries = [m for m in metrics if m.get("phase") == "automatic-resume-work-query"]
    if len(queries) != 1:
        violations.append("missing unique successful same-WorkSpec query proof")
    else:
        query = queries[0]
        initial, current = query.get("initial_run_attempt"), query.get("run_attempt")
        if (query.get("state") != "SUCCEEDED" or query.get("same_work_id") is not True
                or type(initial) is not int or type(current) is not int
                or initial < 0 or current <= initial):
            violations.append("same WorkSpec did not succeed in an advanced ordinary run")
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
    markers = [e["seq"] for e in events if e["kind"] == "platform_stop_marker"]
    selected = []
    setup = []
    if len(markers) == 1:
        marker = markers[0]
        before = [r for r in gets if r < marker]
        after = [r for r in gets if r > marker]
        if before and len(after) == 1:
            selected = [before[-1], after[0]]
            setup = before[:-1]
    if not selected:
        violations.append("missing unique held initial and post-stop acquisition pair")
    uploads = [e["seq"] for e in events if e["kind"] == "upload"]
    if len(uploads) != 1 or any(e["kind"] == "head" for e in events):
        violations.append("wrong request count")
    elif not any(e["kind"] == "upload_complete" and e["request"] == uploads[0] for e in events):
        violations.append("missing upload outcome")
    for kind in ("interrupt_acquisition", "platform_stop_marker", "release_acquisition"):
        if [e["value"] for e in events if e["kind"] == kind] != [0]:
            violations.append("incorrect " + kind)
    if sum(e["value"] for e in events if e["kind"] == "upload_bytes") != PAYLOAD_BYTES + 16:
        violations.append("incorrect upload_bytes")
    # Setup losses remain in the ledger and diagnostics; only the selected recovery pair qualifies.
    for request in setup:
        rows = [e for e in events if e["request"] == request]
        ends = [e for e in rows if e["kind"] in ("disconnect", "complete")]
        if len(ends) != 1 or ends[0]["kind"] != "disconnect" or ends[0]["seq"] >= selected[0]:
            violations.append("setup acquisition was not aborted before selected initial request")
    if selected:
        initial, resumed = selected
        rows = [e for e in events if e["request"] in selected]
        # Range/validator evidence from any unselected request cannot be silently ignored.
        if any(e["kind"] in ("range_requested_offset", "if_range_match")
               and e["request"] != resumed for e in events):
            violations.append("unmatched Range or validator evidence")
        initial_rows = [e for e in rows if e["request"] == initial]
        resumed_rows = [e for e in rows if e["request"] == resumed]
        for request_rows, count in ((initial_rows, PREFIX_BYTES), (resumed_rows, PAYLOAD_BYTES + 16 - PREFIX_BYTES)):
            if sum(e["value"] for e in request_rows if e["kind"] == "body_bytes") != count:
                violations.append("incorrect selected body_bytes")
        for request_rows, expected_values in ((initial_rows, {"range_offset": [0], "status": [200],
                                                            "held": [PREFIX_BYTES]}),
                                              (resumed_rows, {"range_offset": [PREFIX_BYTES], "status": [206],
                                                              "range_requested_offset": [PREFIX_BYTES],
                                                              "if_range_match": [1], "held": [3 * 1024 * 1024]})):
            for kind, values in expected_values.items():
                if [e["value"] for e in request_rows if e["kind"] == kind] != values:
                    violations.append("incorrect request-bound " + kind)
        def seq(request_rows, kind):
            matches = [e["seq"] for e in request_rows if e["kind"] == kind]
            return matches[0] if len(matches) == 1 else None
        held, disconnected = seq(initial_rows, "held"), seq(initial_rows, "disconnect")
        resumed_held, completed = seq(resumed_rows, "held"), seq(resumed_rows, "complete")
        interrupts = [e["seq"] for e in events if e["kind"] == "interrupt_acquisition"]
        releases = [e["seq"] for e in events if e["kind"] == "release_acquisition"]
        if (None in (held, disconnected, resumed_held, completed) or len(interrupts) != 1 or len(releases) != 1
                or not initial < held < markers[0] < interrupts[0] < disconnected < resumed < resumed_held < releases[0] < completed
                or any(e["kind"] == "complete" for e in initial_rows)
                or any(e["kind"] == "disconnect" for e in resumed_rows)):
            violations.append("incorrect selected hold/stop/release/outcome ordering")
        else:
            if (any(not initial < e["seq"] <= disconnected for e in initial_rows)
                    or any(not resumed < e["seq"] <= completed for e in resumed_rows)
                    or (seq(resumed_rows, "range_requested_offset") or float("inf")) >= resumed_held
                    or (seq(resumed_rows, "if_range_match") or float("inf")) >= resumed_held):
                violations.append("incorrect request event ordering")
            if (sum(e["value"] for e in resumed_rows if e["kind"] == "body_bytes" and e["seq"] < resumed_held)
                    != 1024 * 1024
                    or sum(e["value"] for e in initial_rows if e["kind"] == "body_bytes" and e["seq"] < held)
                    != PREFIX_BYTES):
                violations.append("incorrect live held checkpoint")
    return {"passed": not violations, "violations": violations, "scope": "ordinary-android-work-stop-and-native-resume",
            "platform_job_stop_qualified": not violations, "android_process_restart_qualified": False,
            "performance_qualified": False,
            "setup_acquisition_count": len(setup),
            "setup_body_bytes": sum(e["value"] for e in events if e["kind"] == "body_bytes" and e["request"] in setup),
            "ledger_acquisition_count": len(gets),
            "ledger_body_bytes": sum(e["value"] for e in events if e["kind"] == "body_bytes")}
