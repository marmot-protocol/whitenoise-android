"""Check one received APK whose download was interrupted by process death and then completed exactly once."""

import math

from apk_checker import OBSERVE_MIN_MS

SCOPE = "received-apk-process-recreation-during-download"
CIPHERTEXT_OVERHEAD = 16
HOLD_OFFSET = 2 * 1024 * 1024
MIN_PREFIX_BYTES = 1024 * 1024
ZAPSTORE_DISPATCH = ("allowed", "Opened", True)
PLAY_DISPATCH = ("n/a", "InstallUnsupported", False)


def _finite(value):
    """Accept only measured, non-negative, finite numbers; booleans are never measurements."""
    return (not isinstance(value, bool) and isinstance(value, (int, float))
            and not (isinstance(value, float) and not math.isfinite(value)) and value >= 0)


def _one(metrics, phase):
    """The single metric row of a phase, or None when it is missing or repeated."""
    rows = [m for m in metrics if m.get("phase") == phase]
    return rows[0] if len(rows) == 1 else None


def _check_metrics(metrics, distribution, violations):
    """The held prefix, the recreated transfer, the exact file and the platform dispatch were all evidenced."""
    payload = None
    held = _one(metrics, "apk-recreate-held")
    transfer = _one(metrics, "apk-transfer-recreated")
    received = _one(metrics, "apk-received")
    if (transfer is None or transfer.get("success") is not True
            or not all(_finite(transfer.get(k)) for k in ("elapsed_ms", "java_peak_bytes", "native_peak_bytes"))
            or not transfer.get("payload_bytes")):
        violations.append("missing measured evidence for the recreated transfer")
    else:
        payload = transfer["payload_bytes"]
    if received is None or received.get("exact") is not True or received.get("bytes") != payload:
        violations.append("the recreated download was not proven to be the complete verified package")
    if (held is None or held.get("case") != "valid" or payload is None
            or held.get("total_ciphertext_bytes") != payload + CIPHERTEXT_OVERHEAD
            or not isinstance(held.get("received_bytes"), int) or isinstance(held.get("received_bytes"), bool)
            or not MIN_PREFIX_BYTES <= held["received_bytes"] < held["total_ciphertext_bytes"]):
        violations.append("the first process did not hold a real partial body before it ended")
    found = _one(metrics, "apk-recreate-found")
    if found is None or not isinstance(found.get("native_state"), str) or not _finite(found.get("attempt")):
        violations.append("the recreated process did not report the native state it found")
    expected = ZAPSTORE_DISPATCH if distribution == "Zapstore" else PLAY_DISPATCH
    rows = [m for m in metrics if m.get("phase") == "apk-dispatch"]
    row = rows[0] if len(rows) == 1 else None
    if (row is None or row.get("case") != "valid" or (row.get("permission"), row.get("result"),
                                                      row.get("installer_shown")) != expected
            or row.get("transfer_reused") is not True or not _finite(row.get("dispatch_ms"))
            or (distribution == "Zapstore" and row.get("installer_settled") is not True)):
        violations.append("the platform dispatch of the recreated file is not the expected outcome")
    # Whatever the status, the screen must have been watched: an absent installer only counts if it was looked for.
    observed = None if row is None else row.get("installer_observed_ms")
    if row is not None and (not _finite(observed) or (not expected[2] and observed < OBSERVE_MIN_MS)):
        violations.append("the screen was not watched for an installer after the recreated dispatch")
    done = _one(metrics, "apk-recreate-complete")
    if done is None or done.get("self_update_enabled") is not (distribution == "Zapstore"):
        violations.append("the recreated process did not reach its checkpoint for this distribution")
    return payload


def _check_ledger(events, payload, violations):
    """One upload, exactly two acquisitions that never overlap, and the interrupted one never completed."""
    uploads = [e for e in events if e["kind"] == "upload"]
    if (payload is None or len(uploads) != 1 or uploads[0]["value"] != payload + CIPHERTEXT_OVERHEAD
            or sum(e["kind"] == "upload_complete" for e in events) != 1):
        violations.append("expected exactly one genuine upload of the package")
        return
    token = uploads[0]["fixture"]
    gets = [e for e in events if e["kind"] == "get"]
    if len(gets) != 2 or any(g["fixture"] != token for g in gets) or any(e["kind"] == "head" for e in events):
        violations.append("expected exactly two acquisitions of the package and no HEAD")
        return
    first, second = gets
    by_request = {}
    for e in events:
        by_request.setdefault(e.get("request"), []).append(e)
    first_events = by_request.get(first["seq"], [])
    second_events = by_request.get(second["seq"], [])
    held = [e for e in first_events if e["kind"] == "held"]
    interrupted = (len(held) == 1 and held[0]["value"] == HOLD_OFFSET
                   and any(e["kind"] == "disconnect" for e in first_events)
                   and not any(e["kind"] == "complete" for e in first_events)
                   and any(e["kind"] == "status" and e["value"] == 200 for e in first_events))
    if not interrupted:
        violations.append("the first acquisition was not held at its prefix and ended by the process dying")
    disconnect = next((e["seq"] for e in first_events if e["kind"] == "disconnect"), None)
    if disconnect is None or second["seq"] < disconnect:
        violations.append("the replacement acquisition started before the interrupted one had ended")
    offsets = [e["value"] for e in second_events if e["kind"] == "range_offset"]
    statuses = [e["value"] for e in second_events if e["kind"] == "status"]
    first_bytes = sum(e["value"] for e in first_events if e["kind"] == "body_bytes")
    second_bytes = sum(e["value"] for e in second_events if e["kind"] == "body_bytes")
    size = payload + CIPHERTEXT_OVERHEAD
    if (len(offsets) != 1 or not 0 <= offsets[0] <= first_bytes or len(statuses) != 1
            or statuses[0] not in (200, 206) or not any(e["kind"] == "complete" for e in second_events)
            or second_bytes != size - offsets[0] or first_bytes != HOLD_OFFSET):
        violations.append("the replacement acquisition did not deliver exactly the bytes it still needed")
    for control, count in (("hold_resumable_acquisition", 1), ("release_acquisition", 1)):
        if sum(e["kind"] == control for e in events) != count:
            violations.append(f"expected exactly {count} {control} control event")
    if any(e["kind"] in ("cancel_marker", "hold_acquisition", "payload_fetch") for e in events):
        violations.append("unexplained cancellation or payload evidence in a process-recreation run")


def check_recreation(metrics, events, distribution, process_ended_abruptly):
    """Fail closed on a missing proof, an overlapping or extra acquisition, or a file that is not the whole package."""
    result = {"scope": SCOPE, "performance_qualified": False, "installation_confirmed": False,
              "process_ended_abruptly": process_ended_abruptly is True}
    if (distribution not in ("Play", "Zapstore") or not isinstance(metrics, list)
            or any(not isinstance(m, dict) for m in metrics) or not isinstance(events, list)):
        return {**result, "passed": False, "violations": ["invalid input"]}
    violations = []
    if process_ended_abruptly is not True:
        violations.append("the first process did not end abruptly while its download was held")
    payload = _check_metrics(metrics, distribution, violations)
    _check_ledger(events, payload, violations)
    return {**result, "passed": not violations, "violations": violations, "distribution": distribution}
