"""Check genuine received-APK transfer and Android open outcomes; claim no installation, physical or UI result."""

import math

SCOPE = "genuine-received-apk-transfer-and-platform-open"
CIPHERTEXT_OVERHEAD = 16
CASES = ("valid", "generic", "conflict", "no-manifest", "truncated")
LARGE_CASE = "large"
NO_INSTALLER_PERMISSION = "no-installer-simulated"
HELD_PREFIX_BYTES = 1024
CANCEL_CONTROLS = ("hold_acquisition", "cancel_marker", "release_acquisition")
SOCKET_CLOSE_BUDGET_MS = 5_000
ORDINARY = ("Opened", "NoHandler")
# A dispatch that expects no installer must have been watched for at least this long, a little under the probe's window.
OBSERVE_MIN_MS = 900
# (case, permission) -> allowed results, and whether the system installer must reach the screen.
ZAPSTORE = {
    ("valid", "denied"): (("InstallPermissionRequired",), False),
    ("valid", "allowed"): (("Opened",), True),
    ("generic", "allowed"): (("Opened",), True),
    ("conflict", "allowed"): (ORDINARY, False),
    ("no-manifest", "any"): (("InvalidPackage",), False),
    ("truncated", "any"): (("InvalidPackage",), False),
}
PLAY = {
    ("valid", "n/a"): (("InstallUnsupported",), False),
    ("generic", "n/a"): (("InstallUnsupported",), False),
    ("conflict", "n/a"): (ORDINARY, False),
    ("no-manifest", "any"): (("InvalidPackage",), False),
    ("truncated", "any"): (("InvalidPackage",), False),
}
# Optional gap rows, selected explicitly by the runner and reported as separate evidence.
LARGE_ZAPSTORE = {(LARGE_CASE, "allowed"): (("Opened",), True)}
LARGE_PLAY = {(LARGE_CASE, "n/a"): (("InstallUnsupported",), False)}
NO_INSTALLER_ZAPSTORE = {("valid", NO_INSTALLER_PERMISSION): (("NoInstaller",), False)}


def cases_for(large=False):
    """The sent cases in wire order; the optional 30 to 31 MiB package is always last."""
    return CASES + ((LARGE_CASE,) if large else ())


def table_for(distribution, large=False, no_installer=False):
    """The expected platform outcome per (case, permission) for one distribution and the selected gaps."""
    if distribution == "Zapstore":
        table = dict(ZAPSTORE)
        if large:
            table.update(LARGE_ZAPSTORE)
        if no_installer:
            table.update(NO_INSTALLER_ZAPSTORE)
        return table
    table = dict(PLAY)
    if large:
        table.update(LARGE_PLAY)
    return table


def _finite(value):
    """Accept only measured, non-negative, finite numbers; booleans are never measurements."""
    return (not isinstance(value, bool) and isinstance(value, (int, float))
            and not (isinstance(value, float) and not math.isfinite(value)) and value >= 0)


def _check_transfer(metrics, violations, cases):
    """Every case was received exactly once, byte-exact, with finite measured latency and memory."""
    sizes = {}
    named = {m.get("phase"): m for m in metrics if isinstance(m.get("phase"), str) and "elapsed_ms" in m}
    for case in cases:
        measure = named.get(f"apk-transfer-{case}")
        rows = [m for m in metrics if m.get("phase") == "apk-received" and m.get("case") == case]
        if (measure is None or measure.get("success") is not True
                or not all(_finite(measure.get(k)) for k in ("elapsed_ms", "java_peak_bytes", "native_peak_bytes"))
                or len(rows) != 1 or rows[0].get("exact") is not True
                or rows[0].get("bytes") != measure.get("payload_bytes") or not rows[0].get("bytes")):
            violations.append(f"missing exact transfer evidence for {case}")
        else:
            sizes[case] = rows[0]["bytes"]
    return sizes


def _check_dispatch(metrics, distribution, violations, table, case_count):
    """Each platform dispatch matches the distribution's table; no case is missing, repeated or unexplained."""
    rows = {}
    for row in (m for m in metrics if m.get("phase") == "apk-dispatch"):
        key = (row.get("case"), row.get("permission"))
        if key in rows:
            violations.append("a dispatch was recorded twice")
        rows[key] = row
    if set(rows) != set(table):
        violations.append("unexpected or missing dispatch cases")
    for key, (allowed, installer) in table.items():
        row = rows.get(key)
        if (row is None or row.get("result") not in allowed or row.get("installer_shown") is not installer
                or row.get("transfer_reused") is not True or not _finite(row.get("dispatch_ms"))):
            violations.append(f"wrong platform outcome for {key[0]} ({key[1]})")
        # A package big enough to stage visibly must have been seen staging and seen finished, otherwise the wait that
        # keeps Back from stranding the installer's dialog proved nothing.
        if installer and key[0] == LARGE_CASE and row is not None and (
                row.get("installer_settled") is not True or row.get("installer_progress_seen") is not True
                or not _finite(row.get("installer_staging_ms")) or row["installer_staging_ms"] <= 0):
            violations.append("the installer's staging of the large package was not seen to finish")
        # Whatever the status, the screen must have been watched: an absent installer only counts if it was looked for.
        observed = None if row is None else row.get("installer_observed_ms")
        # A dispatch that saw an installer stops watching at once and is already a wrong outcome, so only an absent
        # installer needs the full window to be evidence.
        if row is not None and (not _finite(observed) or (
                not installer and row.get("installer_shown") is not True and observed < OBSERVE_MIN_MS)):
            violations.append(f"the screen was not watched for an installer after {key[0]} ({key[1]})")
    done = [m for m in metrics if m.get("phase") == "apk-complete"]
    if (len(done) != 1 or done[0].get("self_update_enabled") is not (distribution == "Zapstore")
            or done[0].get("cases") != case_count):
        violations.append("the run did not reach its checkpoint for this distribution")


def _check_cancellation(metrics, events, valid_token, violations):
    """The valid package was held, cancelled with a bounded socket close, then fetched once more by a deliberate retry."""
    rows = [m for m in metrics if m.get("phase") == "held-body-cancellation"]
    if (len(rows) != 1 or rows[0].get("success") is not True
            or rows[0].get("deliberate_retry_exact_bytes") is not True
            or rows[0].get("no_work_cancel_confirmed") is not True
            or not _finite(rows[0].get("ack_elapsed_ms")) or not _finite(rows[0].get("socket_close_elapsed_ms"))
            or rows[0]["socket_close_elapsed_ms"] > SOCKET_CLOSE_BUDGET_MS):
        violations.append("missing or unbounded cancellation evidence")
    for control in CANCEL_CONTROLS:
        if sum(e["kind"] == control for e in events) != 1:
            violations.append(f"expected exactly one {control} control event")
    attempts = [e for e in events if e["kind"] == "get" and e["fixture"] == valid_token]
    held = [e for e in events if e["kind"] == "held"]
    disconnected = {e["request"] for e in events if e["kind"] == "disconnect"}
    completed = {e["request"] for e in events if e["kind"] == "complete"}
    statuses = {e["request"]: e["value"] for e in events if e["kind"] == "status"}
    # The cancelled attempt was held after its headers, so it must carry a 200 of its own and no completion.
    if (len(attempts) != 2 or len(held) != 1 or held[0]["value"] != HELD_PREFIX_BYTES
            or held[0]["request"] != attempts[0]["seq"] or disconnected != {attempts[0]["seq"]}
            or statuses.get(attempts[0]["seq"]) != 200
            or attempts[0]["seq"] in completed or attempts[1]["seq"] not in completed):
        violations.append("the cancelled attempt and its single deliberate retry are not both evidenced")


def _check_ledger(metrics, events, sizes, violations, cases, cancel_retry, large):
    """The server saw each attachment acquired once, a cancelled attempt aside; a blocked dispatch never fetches again."""
    uploads = [e for e in events if e["kind"] == "upload"]
    expected = sorted(sizes[c] + CIPHERTEXT_OVERHEAD for c in cases if c in sizes)
    if len(sizes) != len(cases) or sorted(e["value"] for e in uploads) != expected \
            or sum(e["kind"] == "upload_complete" for e in events) != len(cases):
        violations.append("expected exactly one genuine upload per case")
        return
    # Cases are sent in wire order, so the first upload is the valid package.
    valid_token = uploads[0]["fixture"]
    gets = [e for e in events if e["kind"] == "get"]
    cancelled = [e for e in gets if cancel_retry and e["fixture"] == valid_token][:1]
    counted = [e for e in gets if e not in cancelled]
    completed = {e["request"] for e in events if e["kind"] == "complete"}
    statuses = {e["request"]: e["value"] for e in events if e["kind"] == "status"}
    if len(counted) != len(cases) or len({g["fixture"] for g in counted}) != len(cases) \
            or any(statuses.get(g["seq"]) != 200 or g["seq"] not in completed for g in counted):
        violations.append("each case must be acquired exactly once and complete")
    if any(e["kind"] == "head" for e in events):
        violations.append("unexpected HEAD request")
    written = sum(e["value"] for e in events if e["kind"] == "body_bytes")
    if written != sum(e["value"] for e in uploads) + (HELD_PREFIX_BYTES if cancel_retry else 0):
        violations.append("ciphertext bytes written differ from bytes uploaded")
    if cancel_retry:
        _check_cancellation(metrics, events, valid_token, violations)
    elif any(e["kind"] in ("held", "disconnect", "cancel_marker") for e in events):
        violations.append("unexplained interruption evidence without the cancel-retry selection")
    fetched = sum(e["kind"] == "payload_fetch" for e in events)
    if fetched != int(large) or sum(e["kind"] == "payload_complete" for e in events) != int(large):
        violations.append("the host payload must be fetched exactly once when and only when the large case is selected")


def check_apk(metrics, events, distribution, cancel_retry=False, large=False, no_installer=False):
    """Fail closed on a missing transfer, a wrong platform outcome, an extra request or a false distribution."""
    result = {"scope": SCOPE, "performance_qualified": False, "installation_confirmed": False,
              "cancel_retry": bool(cancel_retry), "large_apk": bool(large),
              "no_installer_simulated": bool(no_installer), "no_installer_platform_qualified": False}
    if (distribution not in ("Play", "Zapstore") or not isinstance(metrics, list)
            or any(not isinstance(m, dict) for m in metrics) or (no_installer and distribution != "Zapstore")):
        return {**result, "passed": False, "violations": ["invalid input"]}
    violations = []
    cases = cases_for(large)
    sizes = _check_transfer(metrics, violations, cases)
    _check_dispatch(metrics, distribution, violations, table_for(distribution, large, no_installer), len(cases))
    _check_ledger(metrics, events, sizes, violations, cases, cancel_retry, large)
    return {**result, "passed": not violations, "violations": violations, "distribution": distribution}
