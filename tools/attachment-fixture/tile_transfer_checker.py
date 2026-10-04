"""Fail-closed judgement of the real-tile transfer fixture: each scenario must show the step a reader needs to see."""

import math
import re

SCOPE = "real-media-tile-transfer-progress-cancel-retry"
SCENARIOS = {
    "known-cancel-again": ("image", ("idle_download_seen", "cancel_offered", "cancelled_seen", "quiet_after_cancel",
                                     "restarted", "completed")),
    "failed-then-retried": ("video", ("idle_download_seen", "failed_seen", "completed")),
    "unknown-length": ("image", ("idle_download_seen", "no_total", "completed")),
}
KNOWN_BYTES = re.compile(r"^(\d+(?:\.\d+)?) ([KMG]?B) of (\d+(?:\.\d+)?) ([KMG]?B)$")
UNKNOWN_BYTES = re.compile(r"^(\d+(?:\.\d+)?) ([KMG]?B) received$")
UNITS = {"B": 1, "KB": 1024, "MB": 1024 ** 2, "GB": 1024 ** 3}
CANCEL_ACK_LIMIT_MS = 30_000


def _bytes(value, unit):
    """A rendered size in bytes."""
    return float(value) * UNITS[unit]


def _finite(value):
    """A measured, non-negative, finite number; booleans are never measurements."""
    return (not isinstance(value, bool) and isinstance(value, (int, float))
            and not (isinstance(value, float) and not math.isfinite(value)) and value >= 0)


def _check_row(name, media, required, row, violations):
    """One scenario row: its media kind, every required fact true, and a believable byte text."""
    if row is None:
        violations.append(f"{name}: missing scenario row")
        return
    if row.get("media") != media:
        violations.append(f"{name}: expected a {media} tile")
    for field in required:
        if row.get(field) is not True:
            violations.append(f"{name}: {field} was not observed")
    text = row.get("bytes_text")
    if name == "known-cancel-again":
        match = KNOWN_BYTES.match(text) if isinstance(text, str) else None
        if not match or not 0 < _bytes(match[1], match[2]) < _bytes(match[3], match[4]):
            violations.append(f"{name}: expected real bytes below a known total")
        if not _finite(row.get("cancel_ack_ms")) or row["cancel_ack_ms"] > CANCEL_ACK_LIMIT_MS:
            violations.append(f"{name}: cancel acknowledgement was not timely")
    if name == "unknown-length" and not (isinstance(text, str) and UNKNOWN_BYTES.match(text)):
        violations.append(f"{name}: expected received bytes without a total")


def _check_ledger(events, violations):
    """The server confirms three genuine uploads, a 404 for the failed one and a disconnect for the cancelled one."""
    kinds = [e["kind"] for e in events]
    if kinds.count("upload") != 3 or kinds.count("upload_complete") != 3:
        violations.append("expected exactly three complete uploads")
    if kinds.count("complete") < 3:
        violations.append("every attachment must finish at least one acquisition")
    if not any(e["kind"] == "status" and e["value"] == 404 for e in events):
        violations.append("the failed scenario never saw a 404")
    if "disconnect" not in kinds:
        violations.append("the cancelled body never ended with a disconnect")


def check_tile_transfer(metrics, events):
    """Fail closed on a missing scenario, an unobserved step, a duplicated row or a ledger that disagrees."""
    if not isinstance(metrics, list) or any(not isinstance(m, dict) for m in metrics):
        return {"passed": False, "violations": ["invalid metric collection"], "scope": SCOPE,
                "performance_qualified": False}
    violations = []
    rows = {}
    for row in (m for m in metrics if m.get("phase") == "tile-transfer"):
        if row.get("scenario") in rows:
            violations.append(f"{row.get('scenario')}: a scenario row was recorded twice")
        rows[row.get("scenario")] = row
    for name, (media, required) in SCENARIOS.items():
        _check_row(name, media, required, rows.get(name), violations)
    if set(rows) - set(SCENARIOS):
        violations.append("unexpected scenario rows")
    _check_ledger(events, violations)
    return {"passed": not violations, "violations": violations, "scope": SCOPE, "performance_qualified": False}
