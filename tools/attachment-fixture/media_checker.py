"""Check genuine media retention across an Android process boundary; claim no UI, physical or performance result."""

import math

MIB = 1024 * 1024
CIPHERTEXT_OVERHEAD = 16
SCOPE = "genuine-media-native-and-host-retention-across-process-restart"
# Message key, attachment index and media type, in the order the probe sends them.
EXPECTED = (
    ("single-image", 0, "image"), ("video-small", 0, "video"), ("video-9mib", 0, "video"),
    ("video-24mib", 0, "video"), ("album-3", 0, "image"), ("album-3", 1, "image"), ("album-3", 2, "video"),
)
EXACT_SIZES = {"video-9mib": 9 * MIB, "video-24mib": 24 * MIB}
MESSAGES = ("single-image", "video-small", "video-9mib", "video-24mib", "album-3")
ROLES = ("sent", "received")
# The 24 MiB send holds its host copy in a root-local cache to prove native retention comes first, so its default
# host cache is intentionally empty after restart; every other own send must hit the encrypted host cache.
HOLD_PATH_MESSAGE = "video-24mib"
# The memory cache admits only entries up to this size, so only a smaller attachment can be a memory hit at all.
MEMORY_ENTRY_MAX_BYTES = 8 * MIB


def _finite(value):
    """Accept only measured, non-negative, finite numbers; booleans are never measurements."""
    return (not isinstance(value, bool) and isinstance(value, (int, float))
            and not (isinstance(value, float) and not math.isfinite(value)) and value >= 0)


def _measures(metrics):
    """Index every sampled measurement by phase name, rejecting a phase that was sampled twice."""
    named, duplicates = {}, set()
    for metric in metrics:
        phase = metric.get("phase")
        if isinstance(phase, str) and "elapsed_ms" in metric:
            if phase in named:
                duplicates.add(phase)
            named[phase] = metric
    return named, duplicates


def _valid_measure(metric, payload=None):
    """A successful sample with finite latency and Java/native peaks, for the declared payload when known."""
    return (metric is not None and metric.get("success") is True
            and all(_finite(metric.get(k)) for k in ("elapsed_ms", "java_peak_bytes", "native_peak_bytes"))
            and isinstance(metric.get("payload_bytes"), int) and not isinstance(metric["payload_bytes"], bool)
            and metric["payload_bytes"] > 0 and (payload is None or metric["payload_bytes"] == payload))


def _check_prepare(prepare, violations):
    """Every attachment was sent genuinely, retained by the sender before any receiver read, then received once."""
    named, duplicates = _measures(prepare)
    if duplicates:
        violations.append("a prepare measurement was recorded twice")
    sizes = {}
    for message, index, _ in EXPECTED:
        sender = named.get(f"media-sender-retained-{message}-{index}")
        receiver = named.get(f"media-receiver-cold-{message}-{index}")
        if not _valid_measure(sender) or not _valid_measure(receiver, sender and sender.get("payload_bytes")):
            violations.append(f"missing exact prepare evidence for {message}#{index}")
            continue
        sizes[(message, index)] = sender["payload_bytes"]
        if EXACT_SIZES.get(message, sizes[(message, index)]) != sizes[(message, index)]:
            violations.append(f"{message} was not the declared size")
    sent = [m for m in prepare if m.get("phase") == "media-message-sent"]
    if sorted(m.get("message", "") for m in sent) != sorted(MESSAGES):
        violations.append("not every message was sent exactly once")
    stages = [m for m in prepare if m.get("phase") == "fixture-stage" and m.get("stage") == "media-prepared"]
    if len(stages) != 1:
        violations.append("the prepare process did not reach its checkpoint")
    published = {(m.get("message"), m.get("index")): m.get("published")
                 for m in prepare if m.get("phase") == "media-own-host-publication"}
    expected_published = {(m, i) for m, i, _ in EXPECTED if m != HOLD_PATH_MESSAGE}
    if set(published) != expected_published or any(v is not True for v in published.values()):
        violations.append("an own send did not publish its encrypted host copy")
    for phase in ("large-own-native-with-host-copy-held", "large-own-host-publication"):
        if not _valid_measure(named.get(phase), EXACT_SIZES["video-24mib"]):
            violations.append("missing own-copy qualification: " + phase)
    return sizes


def _check_layers(named, row, label, payload, violations):
    """Each local layer that could serve this attachment was timed on its own, and no layer it cannot serve was."""
    eligible = payload is not None and payload <= MEMORY_ENTRY_MAX_BYTES
    if row.get("memory_eligible") is not eligible:
        violations.append(f"wrong memory eligibility for {label}")
    for stage, expected in (("media-host-disk-read", row.get("host_disk_hit") is True),
                            ("media-memory-hit", eligible)):
        metric = named.get(f"{stage}-{label}")
        if expected and not _valid_measure(metric, payload):
            violations.append(f"missing exact {stage} for {label}")
        if not expected and metric is not None:
            violations.append(f"unexpected {stage} for {label}")


def _check_read(read, sizes, violations):
    """Both directions of every attachment stayed exact, resolved without acquisition and decoded a preview."""
    named, duplicates = _measures(read)
    if duplicates:
        violations.append("a read measurement was recorded twice")
    rows = {}
    for row in (m for m in read if m.get("phase") == "media-readback"):
        key = (row.get("message"), row.get("index"), row.get("role"))
        if key in rows:
            violations.append("a readback row was recorded twice")
        rows[key] = row
    for message, index, media in EXPECTED:
        for role in ROLES:
            label = f"{role}-{message}-{index}"
            payload = sizes.get((message, index))
            for stage in ("media-native-read", "media-resolver-read"):
                if not _valid_measure(named.get(f"{stage}-{label}"), payload):
                    violations.append(f"missing exact {stage} for {label}")
            row = rows.get((message, index, role))
            own_copy_expected = role == "sent" and message != HOLD_PATH_MESSAGE
            if row is not None and own_copy_expected and row.get("host_disk_hit") is not True:
                violations.append(f"the sender's encrypted host copy was lost across restart for {label}")
            if (row is None or row.get("exact") is not True or row.get("memory_hit") is not False
                    or row.get("preview_ok") is not True or not _finite(row.get("preview_ms"))
                    or not isinstance(row.get("host_disk_hit"), bool) or row.get("bytes") != payload):
                violations.append(f"missing exact readback for {label}")
            if row is not None:
                _check_layers(named, row, label, payload, violations)
    if len(rows) != len(EXPECTED) * len(ROLES):
        violations.append("unexpected readback rows")
    complete = [m for m in read if m.get("phase") == "media-readback-complete"]
    if len(complete) != 1 or complete[0].get("attachments") != len(EXPECTED):
        violations.append("the read process did not verify every attachment")


def _check_ledger(events, boundary, sizes, violations):
    """Server evidence is authoritative: each body was acquired once before restart and never afterwards."""
    if not isinstance(boundary, int) or isinstance(boundary, bool) or not 0 < boundary <= len(events):
        violations.append("missing process boundary in the ledger")
        return
    before, after = events[:boundary], events[boundary:]
    uploads = [e for e in events if e["kind"] == "upload"]
    expected_uploads = sorted(size + CIPHERTEXT_OVERHEAD for size in sizes.values())
    if len(sizes) != len(EXPECTED) or sorted(e["value"] for e in uploads) != expected_uploads \
            or sum(e["kind"] == "upload_complete" for e in events) != len(EXPECTED):
        violations.append("expected exactly one genuine upload per attachment")
        return
    gets = [e for e in before if e["kind"] == "get"]
    completed = {e["request"] for e in events if e["kind"] == "complete"}
    statuses = {e["request"]: e["value"] for e in events if e["kind"] == "status"}
    if len(gets) != len(EXPECTED) or any(statuses.get(g["seq"]) != 200 or g["seq"] not in completed for g in gets):
        violations.append("each attachment must be acquired once before restart and complete")
    if len({g["fixture"] for g in gets}) != len(EXPECTED):
        violations.append("an attachment was acquired more than once")
    written = sum(e["value"] for e in before if e["kind"] == "body_bytes")
    if written != sum(e["value"] for e in uploads):
        violations.append("ciphertext bytes written differ from bytes uploaded")
    if any(e["kind"] in ("get", "head") for e in after) or any(e["kind"] == "head" for e in before):
        violations.append("a retained read acquired over HTTP after restart")
    if [e for e in before if e["kind"] == "acquisition_unavailable"] == [] \
            or sum(e["kind"] == "acquisition_unavailable" for e in events) != 1:
        violations.append("acquisition was not denied exactly once before restart")


def _check_tiles(tiles, violations):
    """Every restored tile showed its media without a Download or Retry affordance and opened on one tap."""
    rows = {}
    for row in (m for m in tiles if m.get("phase") == "media-tile"):
        key = (row.get("message"), row.get("index"), row.get("role"))
        if key in rows:
            violations.append("a tile row was recorded twice")
        rows[key] = row
    for message, index, media in EXPECTED:
        for role in ROLES:
            row = rows.get((message, index, role))
            if (row is None or row.get("media") != media or row.get("shown") is not True
                    or not _finite(row.get("settle_ms"))):
                violations.append(f"missing or unshown tile for {role}-{message}-{index}")
            elif row.get("download_affordance_seen") is not False or row.get("retry_affordance_seen") is not False:
                violations.append(f"a retained {role}-{message}-{index} showed a Download or Retry affordance")
            elif row.get("one_tap_opened") is not True:
                violations.append(f"{role}-{message}-{index} did not open exactly once on one tap")
    if len(rows) != len(EXPECTED) * len(ROLES):
        violations.append("unexpected tile rows")


def check_media(prepare, read, events, boundary, tiles=None):
    """Fail closed on any missing proof, extra request, inexact byte, absent preview or missing process boundary."""
    check_tiles = tiles is not None
    tiles = [] if tiles is None else tiles
    if (not isinstance(prepare, list) or not isinstance(read, list) or not isinstance(tiles, list)
            or any(not isinstance(m, dict) for m in prepare + read + tiles)):
        return {"passed": False, "violations": ["invalid metric collection"], "scope": SCOPE,
                "attachments": 0, "performance_qualified": False}
    violations = []
    sizes = _check_prepare(prepare, violations)
    _check_read(read, sizes, violations)
    _check_ledger(events, boundary, sizes, violations)
    if check_tiles:
        _check_tiles(tiles, violations)
    host_hits = {f"{r.get('role')}-{r.get('message')}-{r.get('index')}": r.get("host_disk_hit")
                 for r in read if r.get("phase") == "media-readback"}
    return {"passed": not violations, "violations": violations, "scope": SCOPE,
            "attachments": len(sizes), "host_disk_hits": host_hits, "performance_qualified": False}
