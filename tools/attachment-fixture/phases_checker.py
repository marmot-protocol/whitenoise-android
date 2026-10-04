"""Check genuine native phase evidence from the production progress observer; claim no UI or performance result."""

import math

PAYLOAD_BYTES = 32 * 1024 * 1024
FAILING_BYTES = 1024 * 1024
CIPHERTEXT_OVERHEAD = 16
START_PHASES = ("NOT_REQUESTED", "QUEUED", "DOWNLOADING")
POST_BODY_ORDER = ("VERIFYING_CIPHERTEXT", "DECRYPTING", "VERIFYING_PLAINTEXT")
FAILURE_PHASES = ("RETRY_SCHEDULED", "FAILED", "UNAVAILABLE", "RETRY_EXHAUSTED")
LIVE_FAILURE_PHASES = ("RETRY_SCHEDULED",)
SCOPE = "packaged-native-phase-observer"
RANK = {phase: index for index, phase in enumerate(
    ("NOT_REQUESTED", "QUEUED", "DOWNLOADING", *POST_BODY_ORDER, "READY"))}


def _finite(value):
    """Accept only measured, non-negative, finite numbers; booleans are never measurements."""
    return (not isinstance(value, bool) and isinstance(value, (int, float))
            and not (isinstance(value, float) and not math.isfinite(value)) and value >= 0)


def _scenario(metrics, name):
    """Return the single metric for one scenario, or None when it is missing or duplicated."""
    found = [m for m in metrics if m.get("phase") == "native-phases" and m.get("scenario") == name]
    return found[0] if len(found) == 1 else None


def _valid_sequence(metric):
    """Return the ordered phase groups only when every group is well formed."""
    sequence = metric.get("sequence")
    if not isinstance(sequence, list) or not sequence:
        return None
    for group in sequence:
        if not isinstance(group, dict) or not isinstance(group.get("phase"), str):
            return None
        if not all(_finite(group.get(k)) for k in ("samples", "received_min", "received_max", "attempt")):
            return None
        if group["received_min"] > group["received_max"] or group.get("monotonic") is not True:
            return None
    return sequence


def _collapse(sequence):
    """Merge consecutive groups of one phase and attempt, as a total that arrives after headers is not a new phase."""
    merged = []
    for group in sequence:
        last = merged[-1] if merged else None
        if last and last["phase"] == group["phase"] and last["attempt"] == group["attempt"]:
            totals = {t for t in (last.get("total"), group.get("total")) if t is not None}
            last["total"] = totals.pop() if len(totals) == 1 else (None if not totals else -1)
            last["monotonic"] = last["monotonic"] and group["received_min"] >= last["received_max"]
            last["samples"] += group["samples"]
            last["received_max"] = max(last["received_max"], group["received_max"])
            last["received_min"] = min(last["received_min"], group["received_min"])
        else:
            merged.append(dict(group))
    return merged


def _check_known_length(metric, violations):
    """A paced known-length body reports monotonic bytes against its true ciphertext size, then only forward phases."""
    sequence = _valid_sequence(metric) if metric else None
    if sequence is None or metric.get("success") is not True or metric.get("plaintext_exact") is not True:
        violations.append("missing exact known-length phase evidence")
        return {}
    sequence = _collapse(sequence)
    if not all(g["monotonic"] for g in sequence):
        violations.append("body bytes moved backwards within one attempt")
    phases = [g["phase"] for g in sequence]
    if phases[0] not in START_PHASES or phases[-1] != "READY":
        violations.append("known-length transfer must start before the body and end ready")
    ranks = [RANK.get(p) for p in phases]
    if None in ranks or ranks != sorted(ranks) or len(set(phases)) != len(phases):
        violations.append("known-length phases moved backwards or repeated")
    body = [g for g in sequence if g["phase"] == "DOWNLOADING"]
    total = PAYLOAD_BYTES + CIPHERTEXT_OVERHEAD
    if len(body) != 1 or body[0].get("total") != total or body[0]["samples"] < 3:
        violations.append("expected one body phase with several updates against the exact ciphertext size")
    elif body[0]["received_max"] > total or body[0]["received_max"] <= body[0]["received_min"]:
        violations.append("body bytes did not advance within the declared size")
    polled = metric.get("polled_phases")
    ranks = [RANK.get(p) for p in polled] if isinstance(polled, list) else None
    if not ranks or None in ranks or ranks != sorted(ranks) or len(set(polled)) != len(polled) or polled[-1] != "READY":
        violations.append("authoritative snapshot poll moved backwards, repeated a phase or never reached ready")
        polled = []
    return {p: p in phases or p in polled for p in POST_BODY_ORDER}


def _check_failure(metric, violations):
    """A permanent miss reaches a real failure phase, is recovered by exactly one deliberate Retry, and ends ready."""
    sequence = _valid_sequence(metric) if metric else None
    if sequence is None or metric.get("success") is not True or metric.get("plaintext_exact") is not True:
        violations.append("missing exact failure-recovery evidence")
        return None
    phases = [g["phase"] for g in sequence]
    failure = metric.get("failure_phase")
    if failure not in FAILURE_PHASES or failure not in phases:
        violations.append("no real failure phase was observed")
        return None
    if metric.get("retry_accepted") is not True:
        violations.append("deliberate Retry was not accepted exactly once")
    if failure in LIVE_FAILURE_PHASES:
        if metric.get("cancel_acknowledged") is not True or "CANCELLED" not in phases:
            violations.append("a live deferred attempt requires acknowledged cancellation before Retry")
    elif "cancel_acknowledged" in metric:
        violations.append("a terminal failure must not need cancellation")
    if phases[-1] != "READY" or phases.index(failure) > len(phases) - 2:
        violations.append("recovery must publish forward progress after the failure and end ready")
    return failure


def _check_ledger(events, violations):
    """Server evidence is authoritative: one upload each, a counted permanent miss, one successful body each."""
    uploads = [e for e in events if e["kind"] == "upload"]
    sizes = sorted(e["value"] for e in uploads)
    expected = sorted((PAYLOAD_BYTES + CIPHERTEXT_OVERHEAD, FAILING_BYTES + CIPHERTEXT_OVERHEAD))
    if sizes != expected or sum(e["kind"] == "upload_complete" for e in events) != 2:
        violations.append("expected exactly the two genuine uploads")
        return
    if any(e["kind"] == "head" for e in events):
        violations.append("unexpected HEAD request")
    statuses = {}
    for get in (e for e in events if e["kind"] == "get"):
        statuses.setdefault(get["fixture"], []).append(
            next((e["value"] for e in events if e["kind"] == "status" and e["request"] == get["seq"]), None))
    uploaded = {e["fixture"]: e["value"] for e in uploads}
    paced = [t for t, size in uploaded.items() if size == PAYLOAD_BYTES + CIPHERTEXT_OVERHEAD]
    failing = [t for t, size in uploaded.items() if size == FAILING_BYTES + CIPHERTEXT_OVERHEAD]
    if len(paced) != 1 or len(failing) != 1:
        violations.append("could not identify the paced and failing fixtures")
        return
    if statuses.get(paced[0]) != [200]:
        violations.append("the paced body must be requested exactly once and succeed")
    attempts = statuses.get(failing[0], [])
    if len(attempts) < 2 or attempts[-1] != 200 or any(s != 404 for s in attempts[:-1]):
        violations.append("the failing body must record permanent misses followed by exactly one success")
    completed = {e["request"] for e in events if e["kind"] == "complete"}
    if not {e["seq"] for e in events if e["kind"] == "get"} <= completed:
        violations.append("every request needs an independently committed terminal outcome")
    for fixture, size in ((paced[0], PAYLOAD_BYTES), (failing[0], FAILING_BYTES)):
        written = sum(e["value"] for e in events if e["kind"] == "body_bytes" and e["fixture"] == fixture)
        if written != size + CIPHERTEXT_OVERHEAD:
            violations.append("incorrect ciphertext bytes written for a fixture")
    for kind in ("pace_acquisition", "acquisition_not_found", "restore_acquisition"):
        if sum(e["kind"] == kind for e in events) != 1:
            violations.append("incorrect " + kind)


def _check_measurement(metrics, violations):
    """The sampled Java/native peaks and latency must be present, finite and for the declared payload."""
    samples = [m for m in metrics if m.get("phase") == "native-phases-overall"]
    if len(samples) != 1:
        violations.append("missing measured native phase sample")
        return
    sample = samples[0]
    for field in ("elapsed_ms", "java_peak_bytes", "native_peak_bytes"):
        if not _finite(sample.get(field)):
            violations.append("invalid measured " + field)
    if sample.get("success") is not True or sample.get("payload_bytes") != PAYLOAD_BYTES:
        violations.append("native phase run did not complete the declared payload")


def check_phases(metrics, events):
    """Fail closed on any missing transition, backwards byte count, hidden retry or unexplained request."""
    if not isinstance(metrics, list) or any(not isinstance(m, dict) for m in metrics):
        return {"passed": False, "violations": ["invalid metric collection"], "scope": SCOPE,
                "observed_post_body_phases": {}, "failure_phase": None, "performance_qualified": False}
    violations = []
    observed = _check_known_length(_scenario(metrics, "known-length"), violations)
    if observed and not any(observed.values()):
        violations.append("no verifying or decrypting phase was observed on the real feed")
    failure = _check_failure(_scenario(metrics, "failure-recovery"), violations)
    _check_ledger(events, violations)
    _check_measurement(metrics, violations)
    return {"passed": not violations, "violations": violations, "scope": SCOPE,
            "observed_post_body_phases": observed, "failure_phase": failure, "performance_qualified": False}
