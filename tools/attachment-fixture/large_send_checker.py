"""Fail-closed judgement of the large file-backed send fixture: bounded heap, exact bytes, recoverable cancel and retry."""

import math

SCOPE = "android-large-file-backed-send"
MIB = 1024 ** 2
TAG_BYTES = 16
# The send streams from private files, so its heap must not grow with a file that is hundreds of MiB.
JAVA_GROWTH_LIMIT = 32 * MIB
NATIVE_GROWTH_LIMIT = 64 * MIB
REQUIRED_PHASES = ("PREPARING", "ENCRYPTING", "UPLOADING")
# The order a send moves through its steps, which the shown progress must follow.
PHASE_ORDER = REQUIRED_PHASES + ("SENDING",)
# The per-file ceiling: 512 MiB of ciphertext less its 16-byte tag.
CEILING_BYTES = 512 * MIB - TAG_BYTES
# Every scenario must say its file was picked from a `content://` source, so a stale test APK that staged its
# files directly cannot pass.
FACTS = {
    "large-send": ("picked_from_content_uri", "sha256_matches", "monotonic", "snapshot_deleted"),
    "cancel": ("picked_from_content_uri", "cancelled_while_working", "snapshot_deleted"),
    "retry": ("picked_from_content_uri", "first_attempt_unpublished", "kept_for_retry", "sha256_matches",
              "snapshot_deleted"),
}


def _measurement(value):
    """A measured, non-negative, finite number. Booleans are never measurements."""
    return (not isinstance(value, bool) and isinstance(value, (int, float))
            and not (isinstance(value, float) and not math.isfinite(value)) and value >= 0)


def _completed_upload_sizes(events):
    """The declared size of every upload the fixture received in full, from its own ledger."""
    completed = {event.get("request") for event in events if event.get("kind") == "upload_complete"}
    return [event["value"] for event in events if event.get("kind") == "upload" and event.get("seq") in completed]


def _received_upload_bytes(events, size):
    """Body bytes the fixture received for uploads that declared [size], completed or not."""
    requests = {event.get("seq") for event in events if event.get("kind") == "upload" and event.get("value") == size}
    return sum(event.get("value") or 0 for event in events
               if event.get("kind") == "upload_bytes" and event.get("request") in requests)


def _check_large_send(row, violations):
    """Heap growth stays bounded, progress names every step and the message ends sending."""
    for side, limit in (("java", JAVA_GROWTH_LIMIT), ("native", NATIVE_GROWTH_LIMIT)):
        baseline, peak = row.get(f"{side}_baseline_bytes"), row.get(f"{side}_peak_bytes")
        # A sampler that never ran reports nothing above the baseline, which must not pass as bounded growth.
        if not (_measurement(baseline) and _measurement(peak) and 0 < baseline <= peak):
            violations.append(f"large-send: {side} heap was not measured")
        elif peak - baseline > limit:
            violations.append(f"large-send: {side} heap grew by more than {limit // MIB} MiB")
    phases = row.get("phases")
    if not isinstance(phases, list) or any(phase not in phases for phase in REQUIRED_PHASES):
        violations.append("large-send: progress did not show every step")
    elif [phase for phase in phases if phase in PHASE_ORDER] != sorted(
        (phase for phase in phases if phase in PHASE_ORDER), key=PHASE_ORDER.index
    ):
        violations.append("large-send: progress showed its steps out of order")
    if row.get("final_phase") != "SENDING":
        violations.append("large-send: progress did not reach sending")


def check_large_send(metrics, events):
    """Judge every scenario row and the fixture ledger. Any missing or contrary fact fails the run."""
    violations = []
    rows = {}
    for row in metrics if isinstance(metrics, list) else []:
        if isinstance(row, dict) and row.get("scenario") in FACTS:
            if row["scenario"] in rows:
                violations.append(f"{row['scenario']}: reported more than once")
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
    events = events if isinstance(events, list) else []
    sizes = _completed_upload_sizes(events)
    for scenario, expected in (("large-send", 1), ("retry", 1), ("cancel", 0)):
        size = rows.get(scenario, {}).get("bytes")
        if _measurement(size) and sizes.count(size + TAG_BYTES) != expected:
            violations.append(f"{scenario}: expected {expected} completed upload(s) of its ciphertext")
    # The device reports progress, but only the fixture's own ledger shows that the cancel cut off an upload
    # that was really sending bytes, rather than stopping before any byte left.
    cancel_size = rows.get("cancel", {}).get("bytes")
    if _measurement(cancel_size) and _received_upload_bytes(events, cancel_size + TAG_BYTES) <= 0:
        violations.append("cancel: the fixture received no bytes of the cancelled upload")
    # A run lowered for a small emulator can pass, but it does not qualify the ceiling itself.
    at_ceiling = rows.get("large-send", {}).get("bytes") == CEILING_BYTES
    return {"scope": SCOPE, "passed": not violations, "violations": violations, "at_ceiling": at_ceiling}
