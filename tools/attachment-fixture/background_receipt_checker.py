"""Fail-closed judgement of the background receipt fixture (#3308): received media of every enabled type downloads
with the Activity stopped, opening the app fetches nothing again, a disabled type is never fetched, and a job that
Android stopped resumes in a recreated runtime."""

SCOPE = "received-media-background-scheduling"
BACKGROUND_ATTACHMENTS = 4
UPLOADS = BACKGROUND_ATTACHMENTS + 2 + 1


def _require(row, path, expected, violations):
    """One closed fact in the device report must hold exactly."""
    value = row
    for key in path:
        value = value.get(key) if isinstance(value, dict) else None
    if value != expected:
        violations.append(f"{'.'.join(path)}: expected {expected!r}")


def _gets_by_fixture(events):
    """Count acquisitions per synthetic fixture token, in ledger order."""
    counts = {}
    for event in events:
        if event["kind"] == "get":
            counts[event["fixture"]] = counts.get(event["fixture"], 0) + 1
    return counts


def _marker(events, kind):
    """The sequence number of the single marker of [kind], or None when it is missing or repeated."""
    marks = [e["seq"] for e in events if e["kind"] == kind]
    return marks[0] if len(marks) == 1 else None


def _check_ledger(events, violations):
    """The server, not the device, confirms what was fetched and when."""
    if sum(e["kind"] == "upload" for e in events) != UPLOADS:
        violations.append(f"expected exactly {UPLOADS} uploads")
    start, end = _marker(events, "background_start"), _marker(events, "background_end")
    stop = _marker(events, "platform_stop_marker")
    if start is None or end is None or not start < end:
        violations.append("background markers missing or out of order")
        return
    background = [e for e in events if e["kind"] == "get" and start < e["seq"] < end]
    fetched = {e["fixture"] for e in background}
    if len(background) != BACKGROUND_ATTACHMENTS or len(fetched) != BACKGROUND_ATTACHMENTS:
        violations.append("each background attachment must be fetched exactly once while backgrounded")
    if any(e["kind"] == "get" and e["seq"] > end and e["fixture"] in fetched for e in events):
        violations.append("an attachment was fetched again after the app returned")
    counts = _gets_by_fixture(events)
    uploaded = {e["fixture"] for e in events if e["kind"] == "upload"}
    if len(uploaded - set(counts)) != 1:
        violations.append("exactly one uploaded attachment, the disabled type, must never be fetched")
    if stop is None:
        violations.append("the platform stop marker is missing")
    elif not any(n >= 2 for f, n in counts.items() if any(e["fixture"] == f and e["seq"] > stop for e in events)):
        violations.append("the stopped attachment never resumed with a second acquisition")


def check_background_receipt(metrics, events):
    """Fail closed on a missing or duplicated report row, any unproven fact, or a ledger that disagrees."""
    if not isinstance(metrics, list) or any(not isinstance(m, dict) for m in metrics):
        return {"passed": False, "violations": ["invalid metric collection"], "scope": SCOPE,
                "performance_qualified": False}
    violations = []
    rows = [m for m in metrics if m.get("phase") == "background-receipt"]
    if len(rows) != 1:
        violations.append("expected exactly one background-receipt row")
    else:
        row = rows[0]
        for path, expected in (
            (("success",), True),
            (("background", "attachments"), BACKGROUND_ATTACHMENTS),
            (("background", "activity_stopped"), True),
            (("background", "conversation_opened"), False),
            (("background", "durable_work_succeeded"), BACKGROUND_ATTACHMENTS),
            (("background", "interactive_intent"), False),
            (("background", "plaintext_exact"), True),
            (("returned", "new_http_gets"), 0),
            (("disabled_type", "enabled_fetched"), 1),
            (("disabled_type", "disabled_queued"), False),
            (("disabled_type", "disabled_fetched"), False),
            (("stopped_recreated", "actual_platform_stop"), True),
            (("stopped_recreated", "runtime_recreated"), True),
            (("stopped_recreated", "same_work_succeeded"), True),
            (("stopped_recreated", "plaintext_exact"), True),
        ):
            _require(row, path, expected, violations)
    _check_ledger(events, violations)
    return {"passed": not violations, "violations": violations, "scope": SCOPE, "performance_qualified": False}
