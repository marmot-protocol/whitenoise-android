"""Fail-closed judgement of the #3376 video-metadata fixture: every prepared route reaches the receiver scrubbed,
every unreadable route is refused before anything is uploaded, and the fixture's own ledger agrees."""

import math
import re

SCOPE = "android-video-metadata-send-receive"
MIB = 1024 ** 2
CIPHERTEXT_OVERHEAD = 16
# The send's in-memory album budget. A route larger than this can only have been sent from a staged private file.
IN_MEMORY_LIMIT = 32 * MIB
# Route name: (entry, sent from a staged file, the platform retriever reads a location from the source).
ACCEPTED = {
    "visual-content-uri-in-memory": ("visual-content-uri", False, True),
    "visual-content-uri-file-backed": ("visual-content-uri", True, True),
    "files-content-uri-octet-in-memory": ("files-content-uri", False, False),
    "files-content-uri-octet-file-backed": ("files-content-uri", True, False),
    "share-file-importer-in-memory": ("share-file-importer", False, False),
    "prepared-draft-injection-visual": ("prepared-draft-injection-visual", False, False),
    "prepared-draft-injection-files": ("prepared-draft-injection-files", False, False),
}
# Route name: (entry, the notice the sender must present).
REFUSED = {
    "refused-visual-content-uri-avi": ("visual-content-uri", "toast_video_metadata_not_removable"),
    "refused-files-content-uri-avi": ("files-content-uri", "media_file_video_metadata_not_removable"),
    "refused-files-content-uri-avi-octet": ("files-content-uri", "media_file_video_metadata_not_removable"),
    "refused-files-content-uri-trailer-in-memory": ("files-content-uri", "media_file_video_metadata_not_removable"),
    "refused-files-content-uri-trailer-file-backed": ("files-content-uri", "media_file_video_metadata_not_removable"),
}
# What this lane does not drive. The device must state each one as not covered.
BOUNDARY = ("system_photo_picker_ui", "system_files_ui", "system_share_sheet_ui", "force_stop_restored_draft",
            "physical_device")
# Platform facts every received video must keep.
KEPT = ("received_date_empty", "duration_kept", "rotation_kept", "dimensions_kept", "frame_decoded", "samples_kept")
SHA256 = re.compile(r"[0-9a-f]{64}")


def _count(value):
    """A non-negative integer count. Booleans are never counts."""
    return isinstance(value, int) and not isinstance(value, bool) and value >= 0


def _size(value):
    """A measured, positive, finite size."""
    return (not isinstance(value, bool) and isinstance(value, (int, float))
            and not (isinstance(value, float) and not math.isfinite(value)) and value > 0)


def _rows(metrics, phase, expected, violations):
    """The one row each expected route reported in [phase]. Repeats, strangers and gaps are violations."""
    rows = {}
    for row in metrics:
        if isinstance(row, dict) and row.get("phase") == phase:
            route = row.get("route")
            if route not in expected:
                violations.append(f"{phase}: unexpected route {route!r}")
            elif route in rows:
                violations.append(f"{route}: reported more than once")
            else:
                rows[route] = row
    violations.extend(f"{route}: missing route row" for route in expected if route not in rows)
    return rows


def _check_accepted(route, row, violations):
    """The received plaintext is exactly the scrubbed source, lost every private field and kept the media."""
    entry, file_backed, location = ACCEPTED[route]
    if row.get("entry") != entry:
        violations.append(f"{route}: entered through {row.get('entry')!r}, not {entry}")
    digests = [row.get(key) for key in ("input_sha256", "expected_sha256", "published_sha256", "received_sha256")]
    if not all(isinstance(value, str) and SHA256.fullmatch(value) for value in digests):
        violations.append(f"{route}: missing digest")
    elif not digests[1] == digests[2] == digests[3]:
        violations.append(f"{route}: received plaintext differs from the scrubbed source")
    elif digests[0] == digests[3]:
        violations.append(f"{route}: received plaintext is the unscrubbed source")
    size = row.get("input_bytes")
    if not _size(size) or row.get("received_bytes") != size:
        violations.append(f"{route}: received size differs from the source")
    elif (size > IN_MEMORY_LIMIT) != file_backed:
        violations.append(f"{route}: size does not select the {'file-backed' if file_backed else 'in-memory'} path")
    private = row.get("private_strings")
    if not (_count(private) and (private > 0 or location) and row.get("input_private_strings") == private):
        violations.append(f"{route}: the source did not carry its private strings")
    if row.get("received_private_strings") != 0:
        violations.append(f"{route}: received plaintext still carries private strings")
    if location and not (row.get("location_positive") is True and row.get("input_location") is True):
        violations.append(f"{route}: the source carried no location the platform reads")
    if row.get("received_location") is not False:
        violations.append(f"{route}: the platform still reads a location")
    violations.extend(f"{route}: {fact} was not observed" for fact in KEPT if row.get(fact) is not True)
    if not (_count(row.get("sample_count")) and row["sample_count"] > 0):
        violations.append(f"{route}: no media samples were compared")
    if row.get("staged_left_after_send") != 0:
        violations.append(f"{route}: a staged snapshot outlived the send")
    if row.get("share_imported_private_copy") is not (entry == "share-file-importer"):
        violations.append(f"{route}: share import receipt does not match the entry")


def _check_refused(route, row, violations):
    """The sender refused with its notice, published nothing and left no copy behind."""
    entry, notice = REFUSED[route]
    if row.get("entry") != entry:
        violations.append(f"{route}: entered through {row.get('entry')!r}, not {entry}")
    if not _size(row.get("input_bytes")) or not (isinstance(row.get("input_sha256"), str)
                                                 and SHA256.fullmatch(row["input_sha256"])):
        violations.append(f"{route}: missing source size or digest")
    if row.get("rejected") is not True:
        violations.append(f"{route}: the sender accepted it")
    if row.get("notice") != notice or row.get("notice_matches") is not True:
        violations.append(f"{route}: expected the {notice} notice")
    if row.get("staged_left") != 0 or row.get("temp_left") != 0:
        violations.append(f"{route}: a private copy was left behind")
    if row.get("published") is not False:
        violations.append(f"{route}: it was published")


def _check_history(metrics, violations):
    """Both histories hold every accepted route and no refused one."""
    rows = [row for row in metrics if isinstance(row, dict) and row.get("phase") == "video-metadata-history"]
    if len(rows) != 1:
        violations.append("history: expected exactly one history row")
        return
    row = rows[0]
    for key, expected in (("accepted_published", len(ACCEPTED)), ("accepted_received", len(ACCEPTED)),
                          ("refused_published", 0), ("refused_received", 0), ("unexpected_published", 0)):
        if row.get(key) != expected:
            violations.append(f"history: {key} was {row.get(key)!r}, expected {expected}")


def _check_boundary(metrics, violations):
    """The device states what this lane does not drive, so no report can claim it."""
    rows = [row for row in metrics if isinstance(row, dict) and row.get("phase") == "video-metadata-boundary"]
    if len(rows) != 1 or any(rows[0].get(key) is not False for key in BOUNDARY):
        violations.append("boundary: the uncovered entry points were not stated")


def _check_ledger(accepted, refused, events, violations):
    """The fixture saw exactly one complete upload per accepted route, of its size, and nothing of a refused one."""
    uploads = [event for event in events if event.get("kind") == "upload"]
    completed = {event.get("request") for event in events if event.get("kind") == "upload_complete"}
    sizes = sorted(event.get("value") for event in uploads if event.get("seq") in completed)
    expected = sorted(row.get("input_bytes", 0) + CIPHERTEXT_OVERHEAD for row in accepted.values()
                      if _size(row.get("input_bytes")))
    if len(uploads) != len(ACCEPTED) or sizes != expected:
        violations.append("ledger: expected exactly one complete upload of each accepted route and nothing else")
    refused_sizes = {row.get("input_bytes", 0) + CIPHERTEXT_OVERHEAD for row in refused.values()
                     if _size(row.get("input_bytes"))}
    if any(event.get("value") in refused_sizes for event in uploads):
        violations.append("ledger: a refused route's ciphertext was uploaded")


def check_video_metadata(metrics, events):
    """Judge every route row, both histories, the stated boundary and the fixture ledger. Any gap fails the run."""
    violations = []
    metrics = metrics if isinstance(metrics, list) else []
    events = events if isinstance(events, list) else []
    accepted = _rows(metrics, "video-metadata-route", ACCEPTED, violations)
    refused = _rows(metrics, "video-metadata-refusal", REFUSED, violations)
    for route, row in accepted.items():
        _check_accepted(route, row, violations)
    for route, row in refused.items():
        _check_refused(route, row, violations)
    _check_history(metrics, violations)
    _check_boundary(metrics, violations)
    _check_ledger(accepted, refused, events, violations)
    return {"scope": SCOPE, "passed": not violations, "violations": violations}
