"""Fail-closed judgement of the small text attachment forward fixture: every phase timed, every copy received once."""

import math
from statistics import median

SCOPE = "android-forward-small-text-attachment-phases"
PAYLOAD_BYTES = 1024
CIPHERTEXT_OVERHEAD = 16
REPETITIONS = 5
VARIANTS = ("uncached", "retained", "cached")
# Marker bases shared with the device probe: a setup step uses the base itself, the five samples use base + 1..5.
MARKERS = {"direct": 0, "uncached": 10, "retained": 20, "cached": 30}
# Only the retained source has a setup step of its own (the one acquisition); both source sends precede the markers.
SETUP_STAGES = ("retained",)
FINAL_MARKER = 40
# What each variant's source phase must look like: which local layers hold the bytes before the forward, whether the
# forward's own cache lookup hits, and whether the forward downloads the source again over HTTP.
SOURCE = {
    "uncached": {"memory": False, "host_disk": False, "native": False, "lookup_hit": False, "download": True},
    "retained": {"memory": False, "host_disk": False, "native": True, "lookup_hit": False, "download": True},
    "cached": {"memory": False, "host_disk": True, "native": True, "lookup_hit": True, "download": False},
}
# The issue's per-forward ceilings, applied on the loopback fixture as regression guards rather than device budgets.
CEILING_MS = {"uncached": 30_000, "retained": 15_000, "cached": 15_000}
# The issue's relation to a direct send of the same bytes; reported per variant, since loopback fixed costs dominate.
DIRECT_RATIO_TARGET = 2.0
PHASE_FIELDS = ("source_ready_ms", "upload_ms", "publish_ms")


def expected_uploads():
    """The author's source, the forwarder's own source, the direct sends and every forward upload once."""
    return 2 + REPETITIONS * (1 + len(VARIANTS))


def expected_gets():
    """Source downloads by the forwards that must download, the one acquisition that retains the source, and the
    author's receipt of every forwarded copy."""
    downloads = sum(REPETITIONS for variant in VARIANTS if SOURCE[variant]["download"])
    return downloads + 1 + REPETITIONS * len(VARIANTS)


def _finite(value):
    """A measured, non-negative, finite number; booleans are never measurements."""
    return (not isinstance(value, bool) and isinstance(value, (int, float))
            and not (isinstance(value, float) and not math.isfinite(value)) and value >= 0)


def _measures(metrics):
    """Index every sampled measurement row by phase name, rejecting a phase sampled twice."""
    named, duplicates = {}, set()
    for metric in metrics:
        phase = metric.get("phase")
        if isinstance(phase, str) and "elapsed_ms" in metric:
            if phase in named:
                duplicates.add(phase)
            named[phase] = metric
    return named, duplicates


def _valid_measure(metric):
    """A successful 1 KiB sample with finite latency and Java/native peaks."""
    return (metric is not None and metric.get("success") is True and metric.get("payload_bytes") == PAYLOAD_BYTES
            and all(_finite(metric.get(k)) for k in ("elapsed_ms", "java_peak_bytes", "native_peak_bytes")))


def _check_direct(metrics, named, violations):
    """Five direct sends of the same bytes through the shipping controller, each timed to its own return."""
    rows = {}
    for row in (m for m in metrics if m.get("phase") == "forward-direct"):
        if row.get("rep") in rows:
            violations.append("a direct send row was recorded twice")
        rows[row.get("rep")] = row
    sends = []
    for rep in range(1, REPETITIONS + 1):
        row = rows.get(rep)
        if row is None or row.get("payload_bytes") != PAYLOAD_BYTES or not _finite(row.get("send_ms")) \
                or not _finite(row.get("reference_ms")):
            violations.append(f"direct send {rep}: missing exact timing")
            continue
        if not _valid_measure(named.get(f"forward-direct-{rep}")):
            violations.append(f"direct send {rep}: missing measurement")
        sends.append(row["send_ms"])
    if set(rows) - set(range(1, REPETITIONS + 1)):
        violations.append("unexpected direct send rows")
    return sends


def _check_sample_source(row, label, contract, violations):
    """The local layers before the forward and the forward's own source phase match the variant's contract."""
    for field, key in (("source_memory_cached", "memory"), ("source_host_disk_cached", "host_disk"),
                       ("source_native_retained", "native"), ("source_lookup_hit", "lookup_hit")):
        if row.get(field) is not contract[key]:
            violations.append(f"{label}: {field} was not {contract[key]}")
    if contract["download"]:
        if not _finite(row.get("source_download_ms")) or row.get("source_download_result") != "success":
            violations.append(f"{label}: the source download was not timed to success")
    elif row.get("source_download_ms") is not None or row.get("source_download_result") is not None:
        violations.append(f"{label}: a cached source must not be downloaded")


def _check_sample(row, variant, rep, named, violations):
    """One forward: completed once, every phase timed, received exactly by the author, within its ceiling."""
    label = f"{variant} {rep}"
    if row.get("success") is not True or row.get("terminal") != "Completed" \
            or row.get("complete_result") != "success":
        violations.append(f"{label}: the forward did not complete")
    if row.get("payload_bytes") != PAYLOAD_BYTES or row.get("sent_messages") != 1 \
            or row.get("uploaded_attachments") != 1:
        violations.append(f"{label}: not exactly one message with one attachment")
    if row.get("delivered_exact") is not True or not _finite(row.get("delivered_ms")):
        violations.append(f"{label}: the destination did not receive the exact bytes")
    if not _finite(row.get("total_ms")) or row["total_ms"] > CEILING_MS[variant]:
        violations.append(f"{label}: total exceeds {CEILING_MS[variant]} ms or is not measured")
    elif _finite(row.get("delivered_ms")) and row["delivered_ms"] < row["total_ms"]:
        violations.append(f"{label}: delivery was observed before completion")
    for field in PHASE_FIELDS:
        if not _finite(row.get(field)):
            violations.append(f"{label}: {field} was not timed")
    if row.get("commit_lock_wait_ms") is not None and not _finite(row.get("commit_lock_wait_ms")):
        violations.append(f"{label}: commit lock wait is not a measurement")
    if row.get("upload_attempts") != 1 or row.get("publish_attempts") != 1 or row.get("convergence_attempts") != 0:
        violations.append(f"{label}: the forward needed a retry or convergence")
    lines = row.get("wnperf_lines")
    if not isinstance(lines, list) or not lines or any(not isinstance(line, str) for line in lines):
        violations.append(f"{label}: the local diagnostics lines are missing")
    if not _valid_measure(named.get(f"forward-{variant}-{rep}")):
        violations.append(f"{label}: missing measurement")
    _check_sample_source(row, label, SOURCE[variant], violations)


def _check_samples(metrics, named, violations):
    """Fifteen forwards, five per variant, each judged on its own."""
    rows = {}
    for row in (m for m in metrics if m.get("phase") == "forward-sample"):
        key = (row.get("variant"), row.get("rep"))
        if key in rows:
            violations.append(f"{key[0]} {key[1]}: a forward row was recorded twice")
        rows[key] = row
    for variant in VARIANTS:
        for rep in range(1, REPETITIONS + 1):
            row = rows.get((variant, rep))
            if row is None:
                violations.append(f"{variant} {rep}: missing forward row")
                continue
            if row.get("marker") != MARKERS[variant] + rep:
                violations.append(f"{variant} {rep}: wrong ledger marker")
            _check_sample(row, variant, rep, named, violations)
    if set(rows) - {(v, r) for v in VARIANTS for r in range(1, REPETITIONS + 1)}:
        violations.append("unexpected forward rows")
    return rows


def _check_timeline(metrics, named, violations):
    """The destination holds exactly one sent media message per direct send and per forward, all distinct."""
    rows = [m for m in metrics if m.get("phase") == "forward-destination-timeline"]
    expected = REPETITIONS * (1 + len(VARIANTS))
    if len(rows) != 1 or rows[0].get("sent_media_messages") != expected \
            or rows[0].get("distinct_ciphertexts") != expected or rows[0].get("direct") != REPETITIONS \
            or rows[0].get("forwards") != REPETITIONS * len(VARIANTS):
        violations.append("the destination timeline does not hold exactly one distinct copy per send")
    if not _valid_measure(named.get("forward-source-open")):
        violations.append("the source was not opened once into native retention")
    stages = [m for m in metrics if m.get("phase") == "fixture-stage" and m.get("stage") == "forward-complete"]
    if len(stages) != 1:
        violations.append("the probe did not reach its completion stage")


def _slices(events, violations):
    """Split the ledger at its markers; every marker the probe must post appears exactly once and in order."""
    required = [FINAL_MARKER] + [MARKERS[stage] for stage in SETUP_STAGES]
    for base in MARKERS.values():
        required.extend(base + rep for rep in range(1, REPETITIONS + 1))
    required.sort()
    markers = [(e["seq"], e["value"]) for e in events if e["kind"] == "marker"]
    if [value for _, value in markers] != required:
        violations.append("ledger markers are missing, repeated or out of order")
        return None
    slices = {}
    for index, (seq, value) in enumerate(markers):
        end = markers[index + 1][0] if index + 1 < len(markers) else None
        slices[value] = [e for e in events if e["seq"] > seq and (end is None or e["seq"] < end)]
    slices["setup"] = [e for e in events if e["seq"] < markers[0][0]]
    return slices


def _uploads(events):
    """Tokens of the uploads in a slice that also completed."""
    completed = {e["fixture"] for e in events if e["kind"] == "upload_complete"}
    return [e["fixture"] for e in events if e["kind"] == "upload" and e["fixture"] in completed]


def _gets(events, all_events):
    """Completed 200 GETs in a slice, by fixture token, with their body byte counts."""
    statuses = {e["request"]: e["value"] for e in all_events if e["kind"] == "status"}
    completed = {e["request"] for e in all_events if e["kind"] == "complete"}
    bodies = {}
    for e in all_events:
        if e["kind"] == "body_bytes":
            bodies[e["request"]] = bodies.get(e["request"], 0) + e["value"]
    return [(e["fixture"], statuses.get(e["seq"]), e["seq"] in completed, bodies.get(e["seq"], 0))
            for e in events if e["kind"] == "get"]


def _check_slice_gets(gets, label, expected, violations):
    """A slice's GETs are exactly the expected tokens, each a complete 200 with the whole ciphertext."""
    observed = sorted(token for token, _, _, _ in gets)
    if observed != sorted(expected):
        violations.append(f"{label}: unexpected acquisition pattern")
    for token, status, completed, body in gets:
        if status != 200 or not completed or body != PAYLOAD_BYTES + CIPHERTEXT_OVERHEAD:
            violations.append(f"{label}: an acquisition did not complete with the whole ciphertext")


def _check_ledger(events, violations):
    """Server evidence is authoritative: one upload per send, a source download only where the contract says so,
    and exactly one receipt of every forwarded copy."""
    slices = _slices(events, violations)
    if slices is None:
        return
    kinds = [e["kind"] for e in events]
    if kinds.count("upload") != expected_uploads() or kinds.count("upload_complete") != expected_uploads():
        violations.append("expected exactly one complete upload per send")
    if kinds.count("get") != expected_gets():
        violations.append("expected exactly the contracted number of acquisitions")
    if any(k in kinds for k in ("head", "upload_disconnect", "disconnect", "hold_timeout")):
        violations.append("an acquisition or upload did not end cleanly")
    if any(e["kind"] == "upload" and e["value"] != PAYLOAD_BYTES + CIPHERTEXT_OVERHEAD for e in events):
        violations.append("an upload did not carry the 1 KiB ciphertext")
    setup = _uploads(slices["setup"])
    if len(setup) != 2 or _gets(slices["setup"], events):
        violations.append("setup must upload the author's source, then the forwarder's own source, and acquire nothing")
        return
    source, own = setup
    _check_slice_gets(_gets(slices[MARKERS["retained"]], events), "retained setup", [source], violations)
    if _uploads(slices[MARKERS["retained"]]):
        violations.append("retained setup must not upload")
    for rep in range(1, REPETITIONS + 1):
        direct = slices[MARKERS["direct"] + rep]
        if len(_uploads(direct)) != 1 or _gets(direct, events):
            violations.append(f"direct send {rep}: expected one upload and no acquisition")
    for variant in VARIANTS:
        source_token = own if variant == "cached" else source
        for rep in range(1, REPETITIONS + 1):
            label = f"{variant} {rep}"
            events_of = slices[MARKERS[variant] + rep]
            uploads = _uploads(events_of)
            if len(uploads) != 1:
                violations.append(f"{label}: expected exactly one destination upload")
                continue
            expected = [uploads[0]] + ([source_token] if SOURCE[variant]["download"] else [])
            _check_slice_gets(_gets(events_of, events), label, expected, violations)
    trailing = slices[FINAL_MARKER]
    if _uploads(trailing) or _gets(trailing, events):
        violations.append("requests continued after the final marker")


def _summary(samples, sends):
    """Per-variant totals against the direct-send median; the ratio is reported, the ceilings are enforced."""
    direct = median(sends) if sends else None
    variants = {}
    for variant in VARIANTS:
        totals = [row["total_ms"] for (v, _), row in samples.items() if v == variant and _finite(row.get("total_ms"))]
        if not totals:
            continue
        ratio = (max(totals) / direct) if direct else None
        variants[variant] = {
            "samples": len(totals), "max_total_ms": max(totals), "median_total_ms": median(totals),
            "ceiling_ms": CEILING_MS[variant], "max_ratio_to_direct_median": ratio,
            "within_direct_ratio_target": None if ratio is None else ratio <= DIRECT_RATIO_TARGET,
        }
    return direct, variants


def check_forward(metrics, events):
    """Fail closed on a missing sample, phase, receipt, marker or unexpected request; report the direct-send ratio."""
    if not isinstance(metrics, list) or any(not isinstance(m, dict) for m in metrics):
        return {"passed": False, "violations": ["invalid metric collection"], "scope": SCOPE,
                "physical_device_qualified": False}
    violations = []
    named, duplicates = _measures(metrics)
    if duplicates:
        violations.append("a measurement was recorded twice")
    sends = _check_direct(metrics, named, violations)
    samples = _check_samples(metrics, named, violations)
    _check_timeline(metrics, named, violations)
    _check_ledger(events, violations)
    direct, variants = _summary(samples, sends)
    return {"passed": not violations, "violations": violations, "scope": SCOPE,
            "direct_send_median_ms": direct, "direct_ratio_target": DIRECT_RATIO_TARGET, "variants": variants,
            "physical_device_qualified": False}
