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
            (None, "hold_unknown_acquisition", 0), (None, "get", 0), (5, "unknown_content_length", 0),
            (5, "status", 200), (5, "body_bytes", 2 * 1024 * 1024), (5, "held", 2 * 1024 * 1024),
            (None, "release_acquisition", 0), (5, "body_bytes", 2 * 1024 * 1024 + 16),
            (5, "complete", 0), (None, "acquisition_unavailable", 0)]
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
            with self.subTest(index=index):
                self.assertFalse(check_unknown_length(metrics, events[:index] + events[index + 1:])["passed"])

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
