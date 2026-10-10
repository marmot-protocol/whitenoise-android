"""Fail-closed judgement of the large file-backed send fixture: bounded heap, exact bytes, recoverable cancel and retry."""

import math

SCOPE = "android-large-file-backed-send"
MIB = 1024 ** 2
TAG_BYTES = 16
# The send streams from private files, so its heap must not grow with a file that is hundreds of MiB.
JAVA_GROWTH_LIMIT = 32 * MIB
NATIVE_GROWTH_LIMIT = 64 * MIB
REQUIRED_PHASES = ("PREPARING", "ENCRYPTING", "UPLOADING")
FACTS = {
    "large-send": ("sha256_matches", "monotonic", "snapshot_deleted"),
    "cancel": ("cancelled_while_working", "snapshot_deleted"),
    "retry": ("first_attempt_unpublished", "kept_for_retry", "sha256_matches", "snapshot_deleted"),
}


def _measurement(value):
    """A measured, non-negative, finite integer; booleans are never measurements."""
    return (not isinstance(value, bool) and isinstance(value, (int, float))
            and not (isinstance(value, float) and not math.isfinite(value)) and value >= 0)


def _completed_upload_sizes(events):
    """The declared size of every upload the fixture received in full, from its own ledger."""
    completed = {event.get("request") for event in events if event.get("kind") == "upload_complete"}
    return [event["value"] for event in events if event.get("kind") == "upload" and event.get("seq") in completed]


def _check_large_send(row, violations):
    """Heap growth stays bounded, progress names every step in order and the message ends sending."""
    for side, limit in (("java", JAVA_GROWTH_LIMIT), ("native", NATIVE_GROWTH_LIMIT)):
        baseline, peak = row.get(f"{side}_baseline_bytes"), row.get(f"{side}_peak_bytes")
        if not (_measurement(baseline) and _measurement(peak)):
            violations.append(f"large-send: {side} heap was not measured")
        elif peak - baseline > limit:
            violations.append(f"large-send: {side} heap grew by more than {limit // MIB} MiB")
    phases = row.get("phases")
    if not isinstance(phases, list) or any(phase not in phases for phase in REQUIRED_PHASES):
        violations.append("large-send: progress did not show every step")
    if row.get("final_phase") != "SENDING":
        violations.append("large-send: progress did not reach sending")


def check_large_send(metrics, events):
    """Judge every scenario row and the fixture ledger; any missing or contrary fact fails the run."""
    violations = []
    rows = {}
    for row in metrics if isinstance(metrics, list) else []:
        if isinstance(row, dict) and row.get("scenario") in FACTS:
            rows[row["scenario"]] = row
    for scenario, facts in FACTS.items():
        row = rows.get(scenario)
        if row is None:
            violations.append(f"{scenario}: missing scenario row")
            continue
        if not (_measurement(row.get("bytes")) and row["bytes"] > 0):
            violations.append(f"{scenario}: missing file size")
        violations.extend(f"{scenario}: {fact} was not observed" for fact in facts if row.get(fact) is not True)
    if "large-send" in rows:
        _check_large_send(rows["large-send"], violations)
    if rows.get("cancel", {}).get("published") is not False:
        violations.append("cancel: the cancelled send was published")
    sizes = _completed_upload_sizes(events if isinstance(events, list) else [])
    for scenario, expected in (("large-send", 1), ("retry", 1), ("cancel", 0)):
        size = rows.get(scenario, {}).get("bytes")
        if _measurement(size) and sizes.count(size + TAG_BYTES) != expected:
            violations.append(f"{scenario}: expected {expected} completed upload(s) of its ciphertext")
    return {"scope": SCOPE, "passed": not violations, "violations": violations}
