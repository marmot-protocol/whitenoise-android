"""Require independent native acknowledgement and server-side cancellation evidence."""


from budget_checker import LIMITS


def check_cancellation(metrics, events, environment, expected_environment):
    """Reject missing evidence, slow cancellation, hidden repeats and incomplete deliberate retry."""
    violations = []
    proof = [m for m in metrics if m.get("phase") == "held-body-cancellation"]
    measured = [m for m in metrics if m.get("phase") == "held-body-cancellation-overall"]
    if environment != expected_environment:
        violations.append("device API/ABI does not match budget profile")
    if len(proof) != 1 or len(measured) != 1:
        violations.append("expected one cancellation proof and one measured run")
    else:
        p, m = proof[0], measured[0]
        if p.get("success") is not True or p.get("deliberate_retry_exact_bytes") is not True:
            violations.append("cancellation and exact-byte retry must both pass")
        if p.get("no_work_cancel_confirmed") is not True:
            violations.append("missing confirmed canonical pre-admission cancellation")
        for key in ("ack_elapsed_ms", "socket_close_elapsed_ms"):
            value = p.get(key)
            if not isinstance(value, (int, float)) or isinstance(value, bool) or not 0 <= value <= 5000:
                violations.append(f"{key}: missing or outside 5000 ms budget")
        if p.get("quiet_seconds") != 30 or p.get("ordinary_terminal_joins") != 10 or p.get("active_joins") != 10:
            violations.append("missing 30-second quiet interval, ten active joins or ten terminal joins")
        for key, limit in (("elapsed_ms", 60000), ("java_peak_bytes", LIMITS["java_peak_bytes"]), ("native_peak_bytes", LIMITS["native_peak_bytes"])):
            value = m.get(key)
            if not isinstance(value, (int, float)) or isinstance(value, bool) or not 0 <= value <= limit:
                violations.append(f"{key}: missing or over budget {limit}")
        if m.get("success") is not True:
            violations.append("measured cancellation run failed")
    grouped = {}
    for event in events:
        grouped.setdefault(event["kind"], []).append(event)
    for kind, count in (("upload", 1), ("upload_complete", 1), ("get", 2), ("head", 0),
                        ("held", 1), ("disconnect", 1), ("complete", 1), ("cancel_marker", 1),
                        ("release_acquisition", 1)):
        if len(grouped.get(kind, [])) != count:
            violations.append(f"{kind}: expected {count} durable events")
    if not violations:
        marker = grouped["cancel_marker"][0]
        disconnected = grouped["disconnect"][0]
        released = grouped["release_acquisition"][0]
        first, retry = grouped["get"]
        if disconnected["request"] != first["seq"] or grouped["complete"][0]["request"] != retry["seq"]:
            violations.append("first request must disconnect and only deliberate retry may complete")
        if not first["seq"] < marker["seq"] < disconnected["seq"] < released["seq"] < retry["seq"]:
            violations.append("cancellation, release and retry ordering was not preserved")
        if not 0 <= disconnected["at_ns"] - marker["at_ns"] <= 5_000_000_000:
            violations.append("server disconnect exceeded the cancellation budget")
        if released["at_ns"] - disconnected["at_ns"] < 30_000_000_000:
            violations.append("server ledger does not prove the quiet interval")
        uploaded = sum(e["value"] for e in grouped.get("upload_bytes", []))
        partial = sum(e["value"] for e in grouped.get("body_bytes", []) if e["request"] == first["seq"])
        retried = sum(e["value"] for e in grouped.get("body_bytes", []) if e["request"] == retry["seq"])
        if not 0 < partial < uploaded or not 0 < retried <= uploaded:
            violations.append("missing partial body or retry bytes exceed the uploaded ciphertext")
    return {"passed": not violations, "violations": violations}
