"""Check genuine received-APK transfer and Android open outcomes; claim no installation, physical or UI result."""

import math

SCOPE = "genuine-received-apk-transfer-and-platform-open"
CIPHERTEXT_OVERHEAD = 16
CASES = ("valid", "generic", "conflict", "no-manifest", "truncated")
ORDINARY = ("Opened", "NoHandler")
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


def _finite(value):
    """Accept only measured, non-negative, finite numbers; booleans are never measurements."""
    return (not isinstance(value, bool) and isinstance(value, (int, float))
            and not (isinstance(value, float) and not math.isfinite(value)) and value >= 0)


def _check_transfer(metrics, violations):
    """Every case was received exactly once, byte-exact, with finite measured latency and memory."""
    sizes = {}
    named = {m.get("phase"): m for m in metrics if isinstance(m.get("phase"), str) and "elapsed_ms" in m}
    for case in CASES:
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


def _check_dispatch(metrics, distribution, violations):
    """Each platform dispatch matches the distribution's table; no case is missing, repeated or unexplained."""
    table = ZAPSTORE if distribution == "Zapstore" else PLAY
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
    done = [m for m in metrics if m.get("phase") == "apk-complete"]
    if (len(done) != 1 or done[0].get("self_update_enabled") is not (distribution == "Zapstore")
            or done[0].get("cases") != len(CASES)):
        violations.append("the run did not reach its checkpoint for this distribution")


def _check_ledger(events, sizes, violations):
    """The server saw each attachment acquired once; a denied or invalid dispatch never causes another transfer."""
    uploads = sorted(e["value"] for e in events if e["kind"] == "upload")
    expected = sorted(sizes[c] + CIPHERTEXT_OVERHEAD for c in CASES if c in sizes)
    if len(sizes) != len(CASES) or uploads != expected \
            or sum(e["kind"] == "upload_complete" for e in events) != len(CASES):
        violations.append("expected exactly one genuine upload per case")
        return
    gets = [e for e in events if e["kind"] == "get"]
    completed = {e["request"] for e in events if e["kind"] == "complete"}
    statuses = {e["request"]: e["value"] for e in events if e["kind"] == "status"}
    if len(gets) != len(CASES) or len({g["fixture"] for g in gets}) != len(CASES) \
            or any(statuses.get(g["seq"]) != 200 or g["seq"] not in completed for g in gets):
        violations.append("each case must be acquired exactly once and complete")
    if any(e["kind"] == "head" for e in events):
        violations.append("unexpected HEAD request")
    written = sum(e["value"] for e in events if e["kind"] == "body_bytes")
    if written != sum(e["value"] for e in events if e["kind"] == "upload"):
        violations.append("ciphertext bytes written differ from bytes uploaded")


def check_apk(metrics, events, distribution):
    """Fail closed on a missing transfer, a wrong platform outcome, an extra request or a false distribution."""
    if (distribution not in ("Play", "Zapstore") or not isinstance(metrics, list)
            or any(not isinstance(m, dict) for m in metrics)):
        return {"passed": False, "violations": ["invalid input"], "scope": SCOPE, "performance_qualified": False,
                "installation_confirmed": False}
    violations = []
    sizes = _check_transfer(metrics, violations)
    _check_dispatch(metrics, distribution, violations)
    _check_ledger(events, sizes, violations)
    return {"passed": not violations, "violations": violations, "scope": SCOPE,
            "distribution": distribution, "performance_qualified": False, "installation_confirmed": False}
