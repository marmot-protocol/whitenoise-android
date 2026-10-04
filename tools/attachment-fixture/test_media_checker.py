"""Media retention qualification must fail when any independent byte, preview, request or process proof is absent."""

from copy import deepcopy
import unittest

from media_checker import CIPHERTEXT_OVERHEAD, EXPECTED, MIB, ROLES, check_media

SIZES = {("single-image", 0): 301_234, ("video-small", 0): 19_832, ("video-9mib", 0): 9 * MIB,
         ("video-24mib", 0): 24 * MIB, ("album-3", 0): 310_001, ("album-3", 1): 305_002, ("album-3", 2): 19_832}


def measure(phase, payload):
    """One sampled stage with finite latency and peaks, as the device probe reports it."""
    return {"phase": phase, "success": True, "payload_bytes": payload, "elapsed_ms": 12.5,
            "java_peak_bytes": 40 * MIB, "native_peak_bytes": 120 * MIB}


def evidence():
    """Construct the declared device and ledger shape; socket behavior has separate real HTTP tests."""
    prepare, read = [], []
    for (message, index, media), size in zip(EXPECTED, SIZES.values()):
        prepare += [measure(f"media-sender-retained-{message}-{index}", size),
                    measure(f"media-receiver-cold-{message}-{index}", size)]
        for role in ROLES:
            read += [measure(f"media-native-read-{role}-{message}-{index}", size),
                     measure(f"media-resolver-read-{role}-{message}-{index}", size),
                     {"phase": "media-readback", "message": message, "index": index, "role": role,
                      "kind": "album" if message == "album-3" else media, "bytes": size, "exact": True,
                      "memory_hit": False, "host_disk_hit": role == "sent" and message != "video-24mib",
                      "preview_ok": True, "preview_ms": 3.5}]
    prepare += [{"phase": "media-message-sent", "message": m, "attachments": 3 if m == "album-3" else 1}
                for m in ("single-image", "video-small", "video-9mib", "video-24mib", "album-3")]
    prepare += [{"phase": "media-own-host-publication", "message": m, "index": i, "published": True}
                for m, i, _ in EXPECTED if m != "video-24mib"]
    prepare += [measure("large-own-native-with-host-copy-held", 24 * MIB),
                measure("large-own-host-publication", 24 * MIB),
                {"phase": "fixture-stage", "stage": "media-prepared"}]
    read.append({"phase": "media-readback-complete", "attachments": len(EXPECTED)})
    rows = []
    for number, size in enumerate(SIZES.values(), 1):
        rows += [(None, "upload", size + CIPHERTEXT_OVERHEAD, f"upload-{number}"),
                 (len(rows) + 1, "upload_complete", 0, f"upload-{number}")]
    rows.append((None, "acquisition_unavailable", 0, "control"))
    gets = []
    for number, size in enumerate(SIZES.values(), 1):
        start = len(rows) + 1
        rows += [(None, "get", 0, f"upload-{number}"), (start, "status", 200, f"upload-{number}"),
                 (start, "body_bytes", size + CIPHERTEXT_OVERHEAD, f"upload-{number}"),
                 (start, "complete", 0, f"upload-{number}")]
        gets.append(start)
    events = [{"seq": seq, "request": request, "kind": kind, "value": value, "fixture": fixture}
              for seq, (request, kind, value, fixture) in enumerate(rows, 1)]
    return prepare, read, events, len(events)


class MediaEvidenceTest(unittest.TestCase):
    """A closure claim needs genuine sends, exact bytes in both directions, a decoded preview and zero acquisition."""

    def test_complete_evidence_passes_and_never_claims_performance(self):
        """The checker records which attachments hit the host disk cache but qualifies no performance budget."""
        result = check_media(*evidence())
        self.assertTrue(result["passed"], result)
        self.assertFalse(result["performance_qualified"])
        self.assertEqual(7, result["attachments"])

    def test_every_missing_ledger_event_fails(self):
        """A missing upload, status, body count or terminal marker is material evidence loss."""
        prepare, read, events, boundary = evidence()
        for index, event in enumerate(events):
            with self.subTest(kind=event["kind"], index=index):
                shorter = events[:index] + events[index + 1:]
                self.assertFalse(check_media(prepare, read, shorter, min(boundary, len(shorter)))["passed"])

    def test_every_missing_metric_fails(self):
        """Removing any one sampled stage or readback row from either process invalidates the run."""
        prepare, read, events, boundary = evidence()
        for index in range(len(prepare)):
            self.assertFalse(check_media(prepare[:index] + prepare[index + 1:], read, events, boundary)["passed"], index)
        for index in range(len(read)):
            self.assertFalse(check_media(prepare, read[:index] + read[index + 1:], events, boundary)["passed"], index)

    def test_any_acquisition_after_the_boundary_fails(self):
        """A retained read that touched HTTP after the restart is a regression whatever bytes it returned."""
        prepare, read, events, boundary = evidence()
        for kind in ("get", "head"):
            extra = events + [{"seq": 999, "request": None, "kind": kind, "value": 0, "fixture": "upload-1"}]
            self.assertFalse(check_media(prepare, read, extra, boundary)["passed"], kind)

    def test_a_second_acquisition_before_the_boundary_fails(self):
        """Each attachment is acquired exactly once; a duplicate download hides a retention loss."""
        prepare, read, events, boundary = evidence()
        extra = events[:boundary] + [{"seq": 998, "request": None, "kind": "get", "value": 0, "fixture": "upload-1"}]
        self.assertFalse(check_media(prepare, read, extra, len(extra))["passed"])

    def test_inexact_or_unpreviewed_readback_fails(self):
        """Exact bytes, no memory hit and a decoded preview are each required for both directions."""
        prepare, read, events, boundary = evidence()
        for field, value in (("exact", False), ("memory_hit", True), ("preview_ok", False),
                             ("preview_ms", float("nan")), ("host_disk_hit", "yes"), ("bytes", 1)):
            altered = deepcopy(read)
            row = next(m for m in altered if m.get("phase") == "media-readback")
            row[field] = value
            self.assertFalse(check_media(prepare, altered, events, boundary)["passed"], field)

    def test_a_lost_or_unpublished_own_host_copy_fails(self):
        """An own send must publish its encrypted host copy and still hit it after restart."""
        prepare, read, events, boundary = evidence()
        altered = deepcopy(prepare)
        next(m for m in altered if m.get("phase") == "media-own-host-publication")["published"] = False
        self.assertFalse(check_media(altered, read, events, boundary)["passed"])
        altered = [m for m in prepare if not (m.get("phase") == "media-own-host-publication" and m["message"] == "album-3")]
        self.assertFalse(check_media(altered, read, events, boundary)["passed"])
        lost = deepcopy(read)
        row = next(m for m in lost if m.get("phase") == "media-readback" and m["role"] == "sent"
                   and m["message"] == "video-9mib")
        row["host_disk_hit"] = False
        self.assertFalse(check_media(prepare, lost, events, boundary)["passed"])

    def test_received_copies_need_no_host_disk_hit_but_must_stay_exact(self):
        """Received media is served from native retention, so its host cache state is informational only."""
        prepare, read, events, boundary = evidence()
        altered = deepcopy(read)
        for row in altered:
            if row.get("phase") == "media-readback" and row["role"] == "received":
                row["host_disk_hit"] = True
        self.assertTrue(check_media(prepare, altered, events, boundary)["passed"])

    def test_invalid_measurements_fail(self):
        """Latency and Java/native peaks must be finite measurements for the declared payload."""
        prepare, read, events, boundary = evidence()
        for field in ("elapsed_ms", "java_peak_bytes", "native_peak_bytes"):
            for value in (None, True, -1, float("nan"), float("inf")):
                altered = deepcopy(read)
                next(m for m in altered if m.get("phase", "").startswith("media-native-read"))[field] = value
                self.assertFalse(check_media(prepare, altered, events, boundary)["passed"], (field, value))
        altered = deepcopy(read)
        next(m for m in altered if m.get("phase", "").startswith("media-native-read"))["payload_bytes"] += 1
        self.assertFalse(check_media(prepare, altered, events, boundary)["passed"])

    def test_boundary_and_declared_sizes_are_required(self):
        """Without a process boundary, or with a wrong large-video size, evidence cannot qualify."""
        prepare, read, events, boundary = evidence()
        for bad in (None, True, 0, len(events) + 1, "x"):
            self.assertFalse(check_media(prepare, read, events, bad)["passed"], bad)
        altered = deepcopy(prepare)
        for metric in altered:
            if metric["phase"].endswith("video-9mib-0"):
                metric["payload_bytes"] -= 1
        self.assertFalse(check_media(altered, read, events, boundary)["passed"])

    def test_duplicate_samples_and_malformed_collections_fail(self):
        """A stage sampled twice, or a non-dict metric, is rejected rather than silently preferred."""
        prepare, read, events, boundary = evidence()
        self.assertFalse(check_media(prepare + [prepare[0]], read, events, boundary)["passed"])
        self.assertFalse(check_media(prepare, read + [read[0]], events, boundary)["passed"])
        self.assertFalse(check_media(["not a metric"], read, events, boundary)["passed"])
        self.assertFalse(check_media(prepare, None, events, boundary)["passed"])

    def test_a_readback_row_missing_its_identity_fails_closed_instead_of_raising(self):
        """A malformed readback row is a violation, so the runner still writes the report for the failed attempt."""
        prepare, read, events, boundary = evidence()
        for field in ("role", "message", "index"):
            altered = deepcopy(read)
            row = next(m for m in altered if m.get("phase") == "media-readback")
            del row[field]
            result = check_media(prepare, altered, events, boundary)
            self.assertFalse(result["passed"], field)
            self.assertTrue(result["violations"], field)


if __name__ == "__main__":
    unittest.main()
