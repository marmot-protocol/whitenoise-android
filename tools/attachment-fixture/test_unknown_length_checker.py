"""Unknown-length qualification cannot pass with missing or fabricated evidence."""

from copy import deepcopy
import unittest

from unknown_length_checker import check_unknown_length, PAYLOAD_BYTES


def evidence():
    """Model the required receipt; transport framing is exercised by the separate socket test."""
    metrics = [{"phase": "unknown-length", "success": True, "total_unknown": True,
                "fraction_unknown": True, "partial_plaintext_unavailable": True, "plaintext_exact": True,
                "platform_progress_semantics_qualified": True,
                "observed_received_bytes": 1024 * 1024, "retained_reads": 3},
               {"phase": "unknown-length-overall", "success": True, "payload_bytes": PAYLOAD_BYTES,
                "elapsed_ms": 5000, "java_peak_bytes": 0, "native_peak_bytes": 0}]
    rows = [(None, "upload", 0), (1, "upload_bytes", PAYLOAD_BYTES + 16), (1, "upload_complete", 0),
            (None, "hold_unknown_acquisition", 0), (None, "get", 0), (5, "range_offset", 0),
            (5, "unknown_content_length", 0), (5, "status", 200), (5, "body_bytes", 16384),
            (5, "disconnect", 0), (None, "get", 0), (11, "range_offset", 0),
            (11, "unknown_content_length", 0), (11, "status", 200), (11, "body_bytes", 2 * 1024 * 1024),
            (11, "held", 2 * 1024 * 1024), (None, "release_acquisition", 0),
            (11, "body_bytes", 2 * 1024 * 1024 + 16), (11, "complete", 0),
            (None, "acquisition_unavailable", 0)]
    events = [{"seq": seq, "request": request, "kind": kind, "value": value}
              for seq, (request, kind, value) in enumerate(rows, 1)]
    return metrics, events


class UnknownLengthEvidenceTest(unittest.TestCase):
    """Fail closed when any required observation, byte counter or terminal is absent."""

    def test_complete_evidence_preserves_scope(self):
        """Functional qualification cannot silently qualify performance or manual UI flows."""
        result = check_unknown_length(*evidence())
        self.assertTrue(result["passed"], result)
        self.assertFalse(result["performance_qualified"])
        self.assertFalse(result["manual_ui_qualified"])

    def test_every_missing_event_fails(self):
        """Even a passing native assertion must retain the independently observed HTTP evidence."""
        metrics, events = evidence()
        for index in range(len(events)):
            if events[index]["kind"] == "body_bytes" and events[index]["request"] == 5:
                continue  # A header-only disconnect may beat the server's first body write.
            with self.subTest(index=index):
                self.assertFalse(check_unknown_length(metrics, events[:index] + events[index + 1:])["passed"])

    def test_probe_allowance_is_bounded_and_request_correlated(self):
        """Four paced chunks tolerate socket scheduling; a fifth is not a header-only probe."""
        metrics, events = evidence()
        probe_chunk = next(e for e in events if e["kind"] == "body_bytes" and e["request"] == 5)
        for count, passed in ((0, True), (4, True), (5, False)):
            altered = [e for e in events if e is not probe_chunk]
            at = next(i for i, e in enumerate(altered) if e["kind"] == "disconnect")
            altered[at:at] = [dict(probe_chunk) for _ in range(count)]
            requests = {e["seq"]: index for index, e in enumerate(altered, 1) if e["kind"] in ("get", "upload")}
            altered = [dict(e, seq=index, request=requests.get(e["request"])) for index, e in enumerate(altered, 1)]
            self.assertEqual(passed, check_unknown_length(metrics, altered)["passed"])
        for kind in ("unknown_content_length", "status", "range_offset", "body_bytes", "held", "complete"):
            altered = deepcopy(events)
            next(e for e in altered if e["kind"] == kind and e["request"] == 11)["request"] = 999
            self.assertFalse(check_unknown_length(metrics, altered)["passed"], kind)

    def test_duplicate_full_download_or_late_probe_fails(self):
        """A second completed download or GET after transfer completion cannot masquerade as a probe."""
        metrics, events = evidence()
        for mutation in ("completed_probe", "late_probe", "late_refetch", "head", "range", "probe_held"):
            altered = deepcopy(events)
            if mutation == "completed_probe":
                next(e for e in altered if e["kind"] == "disconnect")["kind"] = "complete"
                next(e for e in altered if e["kind"] == "body_bytes" and e["request"] == 5)["value"] = PAYLOAD_BYTES + 16
            elif mutation == "late_probe":
                probe = [e for e in altered if e["seq"] == 5 or e["request"] == 5]
                altered = [e for e in altered if e not in probe]
                altered[-1:-1] = probe
                requests = {e["seq"]: index for index, e in enumerate(altered, 1) if e["kind"] in ("get", "upload")}
                altered = [dict(e, seq=index, request=requests.get(e["request"])) for index, e in enumerate(altered, 1)]
            elif mutation == "late_refetch":
                altered.append({"seq": 30, "request": None, "kind": "get", "value": 0})
            else:
                kind = {"head": "head", "range": "range_requested_offset", "probe_held": "held"}[mutation]
                altered.append({"seq": 30, "request": 5, "kind": kind, "value": 0})
            self.assertFalse(check_unknown_length(metrics, altered)["passed"], mutation)

    def test_false_native_assertions_and_unobserved_progress_fail(self):
        """A known total, fraction or premature lease must fail qualification."""
        for field in ("success", "total_unknown", "fraction_unknown", "partial_plaintext_unavailable", "plaintext_exact", "platform_progress_semantics_qualified"):
            metrics, events = evidence()
            metrics[0][field] = False
            self.assertFalse(check_unknown_length(metrics, events)["passed"])
        for value in (None, True, 0, PAYLOAD_BYTES):
            metrics, events = evidence()
            metrics[0]["observed_received_bytes"] = value
            self.assertFalse(check_unknown_length(metrics, events)["passed"])

    def test_nonfinite_metrics_and_repeated_acquisition_fail(self):
        """Preserve useful finite observations and expose any extra transfer."""
        metrics, events = evidence()
        for field in ("elapsed_ms", "java_peak_bytes", "native_peak_bytes"):
            altered = deepcopy(metrics)
            altered[1][field] = float("nan")
            self.assertFalse(check_unknown_length(altered, events)["passed"])
        self.assertFalse(check_unknown_length(metrics, events + [{"seq": 99, "request": None, "kind": "get", "value": 0}])["passed"])


if __name__ == "__main__":
    unittest.main()
