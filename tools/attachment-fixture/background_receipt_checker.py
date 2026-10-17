"""Fail-closed judgement of the background receipt fixture (#3308): received media of every enabled type downloads
with the Activity stopped, opening the app fetches nothing again, a disabled type is never fetched, a type held by
the network waits for an allowed one, and a job that Android stopped finishes in a new Android process."""

SCOPE = "received-media-background-scheduling"
BACKGROUND_ATTACHMENTS = 4
DISABLED_UPLOADS = 2
UPLOADS = BACKGROUND_ATTACHMENTS + DISABLED_UPLOADS + 1 + 1
DISABLED_START, DISABLED_END = 10, 11
NETWORK_START, NETWORK_RESTORED = 20, 21

MAIN_FACTS = (
    (("success",), True),
    (("background", "attachments"), BACKGROUND_ATTACHMENTS),
    (("background", "activity_stopped"), True),
    (("background", "visible_activities"), 0),
    (("background", "durable_work_succeeded"), BACKGROUND_ATTACHMENTS),
    (("background", "interactive_intent"), False),
    (("background", "plaintext_exact"), True),
    (("returned", "local_reads"), BACKGROUND_ATTACHMENTS),
    (("returned", "new_http_gets"), 0),
    (("disabled_type", "enabled_fetched"), 1),
    (("disabled_type", "disabled_queued"), False),
    (("disabled_type", "disabled_fetched"), False),
    (("network_held", "queued_without_wifi"), True),
    (("network_held", "constraint"), "unmetered"),
    (("network_held", "hold_reason"), "network"),
    (("network_held", "same_work_succeeded"), True),
    (("network_held", "plaintext_exact"), True),
    (("network_held", "visible_activities"), 0),
    (("stopped", "actual_platform_stop"), True),
    (("stopped", "held_before_stop"), True),
    (("stopped", "work_enqueued_after_stop"), True),
)

RESUMED_FACTS = (
    (("process_restarted",), True),
    (("app_state_started_before_bind",), False),
    (("work_waiting_before_bind",), True),
    (("same_work_succeeded",), True),
    (("plaintext_exact",), True),
    (("interactive_intent",), False),
    (("visible_activities",), 0),
)


def _require(row, path, expected, violations):
    """One closed fact in the device report must hold exactly, a boolean never standing in for a count."""
    value = row
    for key in path:
        value = value.get(key) if isinstance(value, dict) else None
    if value != expected or isinstance(value, bool) != isinstance(expected, bool):
        violations.append(f"{'.'.join(path)}: expected {expected!r}")


def _single(seqs):
    """The one sequence number in [seqs], or None when it is missing or repeated."""
    return seqs[0] if len(seqs) == 1 else None


def _marker(events, kind):
    """The sequence number of the single control marker of [kind]."""
    return _single([e["seq"] for e in events if e["kind"] == kind])


def _numbered(events, number):
    """The sequence number of the single numbered marker."""
    return _single([e["seq"] for e in events if e["kind"] == "marker" and e["value"] == number])


def _uploads(events, after, before):
    """Fixtures uploaded strictly between two ledger positions, in order."""
    return [e["fixture"] for e in events if e["kind"] == "upload" and after < e["seq"] < before]


def _full_bodies(events):
    """Request ids whose body was served in full: a 200 or 206 status and then completion."""
    served = {e["request"] for e in events if e["kind"] == "status" and e["value"] in (200, 206)}
    return {e["request"] for e in events if e["kind"] == "complete" and e["request"] in served}


def _gets(events, fixture, after=0, before=float("inf")):
    """Request ids of the GETs for [fixture] strictly between two ledger positions."""
    return [e["seq"] for e in events if e["kind"] == "get" and e["fixture"] == fixture and after < e["seq"] < before]


def _check_background(events, full, start, end, violations):
    """Each album attachment is fetched in full while backgrounded, transport retries allowed, and never again."""
    album = _uploads(events, start, end)
    if len(album) != BACKGROUND_ATTACHMENTS or len(set(album)) != BACKGROUND_ATTACHMENTS:
        violations.append("the background album must upload exactly four attachments inside its window")
        return
    for fixture in album:
        if sum(seq in full for seq in _gets(events, fixture, start, end)) != 1:
            violations.append("each background attachment must be fetched in full exactly once while backgrounded")
        if _gets(events, fixture, after=end):
            violations.append("an attachment was fetched again after the app returned")


def _check_disabled(events, full, start, end, violations):
    """Of the two attachments sent with video off, the image is fetched and the video never is, anywhere."""
    pair = _uploads(events, start, end)
    if len(pair) != DISABLED_UPLOADS:
        violations.append("the disabled-type case must upload exactly two attachments")
        return
    fetched = [f for f in pair if any(seq in full for seq in _gets(events, f, start, end))]
    untouched = [f for f in pair if not _gets(events, f)]
    if len(fetched) != 1 or len(untouched) != 1:
        violations.append("exactly the enabled attachment must be fetched and the disabled one never")
        return
    # The ledger carries no content identity, only sizes: the enabled image is a small JPEG and the disabled video
    # is padded to megabytes, so the fetched upload must be the smaller one or the identities were swapped.
    sizes = {e["fixture"]: e["value"] for e in events if e["kind"] == "upload" and start < e["seq"] < end}
    fetched_size, untouched_size = sizes.get(fetched[0]), sizes.get(untouched[0])
    if not isinstance(fetched_size, int) or not isinstance(untouched_size, int) or fetched_size >= untouched_size:
        violations.append("the fetched attachment must be the small enabled image, not the padded disabled video")


def _check_network(events, full, start, restored, stop, violations):
    """The network-held video is never fetched before Wi-Fi returns, and is fetched in full afterwards."""
    held = _uploads(events, start, restored)
    if len(held) != 1:
        violations.append("the network-held case must upload exactly one attachment")
        return
    if _gets(events, held[0], before=restored):
        violations.append("the network-held attachment was fetched before Wi-Fi returned")
    if not any(seq in full for seq in _gets(events, held[0], restored, stop)):
        violations.append("the network-held attachment was not fetched in full after Wi-Fi returned")


def _check_stop(events, full, restored, stop, violations):
    """Exactly the held file is held, and a GET for that same file after the stop serves it in full."""
    held = sorted({e["fixture"] for e in events if e["kind"] == "held"})
    if len(held) != 1:
        violations.append("exactly one attachment, the stopped file, must be held")
        return
    if held[0] not in _uploads(events, restored, stop):
        violations.append("the held attachment is not the file sent for the stop case")
    if not any(seq in full for seq in _gets(events, held[0], after=stop)):
        violations.append("the stopped attachment was not fetched in full after the platform stop")


def _check_ledger(events, violations):
    """The server, not the device, confirms what was fetched and when."""
    if sum(e["kind"] == "upload" for e in events) != UPLOADS:
        violations.append(f"expected exactly {UPLOADS} uploads")
    marks = [
        _marker(events, "background_start"), _marker(events, "background_end"),
        _numbered(events, DISABLED_START), _numbered(events, DISABLED_END),
        _numbered(events, NETWORK_START), _numbered(events, NETWORK_RESTORED),
        _marker(events, "platform_stop_marker"),
    ]
    if None in marks or marks != sorted(marks):
        violations.append("case markers missing, repeated or out of order")
        return
    start, end, disabled_start, disabled_end, network_start, restored, stop = marks
    full = _full_bodies(events)
    _check_background(events, full, start, end, violations)
    _check_disabled(events, full, disabled_start, disabled_end, violations)
    _check_network(events, full, network_start, restored, stop, violations)
    _check_stop(events, full, restored, stop, violations)


def _check_rows(metrics, phase, facts, violations):
    """Exactly one row of [phase], holding every closed fact."""
    rows = [m for m in metrics if m.get("phase") == phase]
    if len(rows) != 1:
        violations.append(f"expected exactly one {phase} row")
        return
    for path, expected in facts:
        _require(rows[0], path, expected, violations)


def check_background_receipt(metrics, events):
    """Fail closed on a missing or duplicated report row, any unproven fact, or a ledger that disagrees."""
    if not isinstance(metrics, list) or any(not isinstance(m, dict) for m in metrics):
        return {"passed": False, "violations": ["invalid metric collection"], "scope": SCOPE,
                "performance_qualified": False}
    violations = []
    _check_rows(metrics, "background-receipt", MAIN_FACTS, violations)
    _check_rows(metrics, "background-receipt-held", (), violations)
    _check_rows(metrics, "background-receipt-resumed", RESUMED_FACTS, violations)
    _check_ledger(events, violations)
    return {"passed": not violations, "violations": violations, "scope": SCOPE, "performance_qualified": False}
