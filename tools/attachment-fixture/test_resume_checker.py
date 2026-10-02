"""Transport qualification must fail when any independent integrity or resume proof is absent."""

from copy import deepcopy
import unittest

from resume_checker import check_resume, PAYLOAD_BYTES, PREFIX_BYTES


def evidence(changed=False):
    """Construct the declared ledger shape; socket behavior has separate real HTTP tests."""
    scenario = "changed-validator" if changed else "compatible"
    metrics = [{"phase": "transport-resume", "success": True, "scenario": scenario,
                "held_prefix_bytes": PREFIX_BYTES, "plaintext_exact": True,
                "partial_plaintext_unavailable": True, "deliberate_retry_used": False,
                "platform_job_stop_qualified": False, "android_process_restart_qualified": False},
               {"phase": "transport-resume-overall", "success": True,
                "payload_bytes": PAYLOAD_BYTES, "elapsed_ms": 3000,
                "java_peak_bytes": 32 * 1024 * 1024, "native_peak_bytes": 128 * 1024 * 1024}]
    rows = [(None, "upload", 0), (1, "upload_bytes", PAYLOAD_BYTES + 16), (1, "upload_complete", 0),
            (None, "get", 0), (4, "range_offset", 0), (4, "status", 200),
            (4, "body_bytes", PREFIX_BYTES), (4, "held", PREFIX_BYTES),
            (None, "interrupt_acquisition", int(changed)), (4, "disconnect", 0),
            (None, "get", 0), (11, "range_requested_offset", PREFIX_BYTES),
            (11, "if_range_match", int(not changed)), (11, "range_offset", 0 if changed else PREFIX_BYTES),
            (11, "status", 200 if changed else 206),
            (11, "body_bytes", PAYLOAD_BYTES + 16 - (0 if changed else PREFIX_BYTES)),
            (11, "complete", 0), (None, "acquisition_unavailable", 0)]
    events = [{"seq": seq, "request": request, "kind": kind, "value": value}
              for seq, (request, kind, value) in enumerate(rows, 1)]
    return metrics, events, scenario


class ResumeEvidenceTest(unittest.TestCase):
    """Missing, false or contradictory assertions cannot turn a transfer into a closure claim."""

    def test_compatible_and_changed_validator_have_distinct_valid_totals(self):
        """Only the incompatible representation may retransfer the held prefix."""
        for changed in (False, True):
            result = check_resume(*evidence(changed))
            self.assertTrue(result["passed"], result)
            self.assertFalse(result["performance_qualified"])
            self.assertFalse(result["platform_job_stop_qualified"])

    def test_every_missing_ledger_event_fails(self):
        """A missing terminal, upload, range, byte count or offline marker is material evidence loss."""
        metrics, events, scenario = evidence()
        for index, event in enumerate(events):
            with self.subTest(kind=event["kind"], index=index):
                self.assertFalse(check_resume(metrics, events[:index] + events[index + 1:], scenario)["passed"])

    def test_duplicate_attempt_and_partial_plaintext_fail(self):
        """One extra HTTP request or premature plaintext cannot be hidden by an exact final hash."""
        metrics, events, scenario = evidence()
        extra = events + [{"seq": 99, "request": None, "kind": "get", "value": 0}]
        self.assertFalse(check_resume(metrics, extra, scenario)["passed"])
        metrics[0]["partial_plaintext_unavailable"] = False
        self.assertFalse(check_resume(metrics, events, scenario)["passed"])

    def test_reset_budget_or_false_scope_claim_fails(self):
        """These tests exercise transport retry, not deliberate Retry, process death or Android scheduler stop."""
        for field in ("deliberate_retry_used", "platform_job_stop_qualified", "android_process_restart_qualified"):
            metrics, events, scenario = evidence()
            metrics[0][field] = True
            self.assertFalse(check_resume(metrics, events, scenario)["passed"])

    def test_invalid_metrics_and_changed_scenario_fail(self):
        """Keep timing/memory observations finite, even though a performance SLO is deferred."""
        metrics, events, scenario = evidence()
        for field in ("elapsed_ms", "java_peak_bytes", "native_peak_bytes"):
            for value in (None, True, -1, float("nan"), float("inf")):
                altered = deepcopy(metrics)
                altered[1][field] = value
                self.assertFalse(check_resume(altered, events, scenario)["passed"])
        self.assertFalse(check_resume(metrics, events, "changed-validator")["passed"])
        self.assertFalse(check_resume([], events, scenario)["passed"])


if __name__ == "__main__":
    unittest.main()
