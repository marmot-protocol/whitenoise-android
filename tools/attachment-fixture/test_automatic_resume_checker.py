"""Missing scheduler, retry-ownership or independent checkpoint evidence must fail qualification."""

from copy import deepcopy
import unittest
from automatic_resume_checker import check_automatic_resume, PAYLOAD_BYTES, PREFIX_BYTES


def evidence():
    """Separate Android assertions from the independent compatible-prefix HTTP ledger."""
    metrics = [{"phase": "automatic-platform-resume", "success": True, "same_work_resumed": True,
                "actual_platform_stop": True, "ordinary_work": True, "interactive_intent": False,
                "deliberate_retry_used": False, "plaintext_exact": True, "partial_plaintext_unavailable": True,
                "diagnostics_private": True, "activity_stopped": True, "android_api": 30,
                "worker_stop_reason": "unavailable", "worker_run_attempt": 0, "android_process_restart_qualified": False},
               {"phase": "automatic-platform-resume-overall", "success": True, "payload_bytes": PAYLOAD_BYTES,
                "elapsed_ms": 35000, "java_peak_bytes": 100, "native_peak_bytes": 100}]
    rows = [(None, "upload", 0), (1, "upload_bytes", PAYLOAD_BYTES+16), (1, "upload_complete", 0),
            (None, "get", 0), (4, "range_offset", 0), (4, "status", 200), (4, "body_bytes", PREFIX_BYTES),
            (4, "held", PREFIX_BYTES), (None, "platform_stop_marker", 0), (None, "interrupt_acquisition", 0),
            (4, "disconnect", 0), (None, "get", 0), (12, "range_requested_offset", PREFIX_BYTES),
            (12, "if_range_match", 1), (12, "range_offset", PREFIX_BYTES), (12, "status", 206),
            (12, "body_bytes", 1024*1024), (12, "held", 3*1024*1024), (None, "release_acquisition", 0),
            (12, "body_bytes", 1024*1024+16), (12, "complete", 0)]
    return metrics, [{"seq": s, "request": r, "kind": k, "value": v} for s, (r,k,v) in enumerate(rows,1)]


class AutomaticResumeEvidenceTest(unittest.TestCase):
    """Transport success cannot stand in for genuine stopped and resumed ordinary platform work."""

    def test_scoped_success_keeps_process_and_performance_unqualified(self):
        """The host checker grants only the exact ordinary-job/transport combination proved."""
        for api in (30, 36):
            metrics, events = evidence()
            metrics[0]["android_api"] = api
            metrics[0]["worker_stop_reason"] = "unavailable" if api < 31 else "3"
            result = check_automatic_resume(metrics, events)
            self.assertTrue(result["passed"], result)
            self.assertTrue(result["platform_job_stop_qualified"])
            self.assertFalse(result["android_process_restart_qualified"])
            self.assertFalse(result["performance_qualified"])

    def test_a_probe_failure_row_fails_and_names_its_step_without_free_text(self):
        """The probe's own failure row is surfaced as a violation, and only a closed step and exception name pass through."""
        metrics, events = evidence()
        metrics.append({"phase": "automatic-platform-resume-failure", "step": "resume-request",
                        "exception": "TimeoutCancellationException", "work_states": ["RUNNING", "ENQUEUED"],
                        "ledger_kinds": {"get": 1}, "ms_since_stop": 30000})
        result = check_automatic_resume(metrics, events)
        self.assertFalse(result["passed"])
        self.assertIn("the probe failed at step resume-request (TimeoutCancellationException)", result["violations"])
        metrics[-1]["step"] = "secret: some free text with a path /data/x"
        result = check_automatic_resume(metrics, events)
        self.assertTrue(any("step unknown" in v for v in result["violations"]))
        self.assertFalse(any("secret" in v or "/data" in v for v in result["violations"]))

    def test_optional_resume_timing_fields_do_not_change_a_passing_verdict(self):
        """The measured stop-to-running and running-to-request times are diagnostics, never a qualification input."""
        metrics, events = evidence()
        metrics[0].update({"resume_running_ms": 27000, "resume_request_ms": 7})
        self.assertTrue(check_automatic_resume(metrics, events)["passed"])

    def test_each_missing_event_fails(self):
        """No marker, byte, hold, range or terminal event can be dropped from provenance."""
        metrics, events = evidence()
        for i in range(len(events)):
            with self.subTest(i=i):
                result = check_automatic_resume(metrics, events[:i]+events[i+1:])
                self.assertFalse(result["passed"])
                self.assertFalse(result["platform_job_stop_qualified"])

    def test_every_native_assertion_is_required(self):
        """Deliberate Retry, interactive priority or process-death overclaims fail closed."""
        metrics, events = evidence()
        for field, value in list(metrics[0].items())[1:]:
            changed = deepcopy(metrics)
            changed[0][field] = not value
            self.assertFalse(check_automatic_resume(changed, events)["passed"], field)

    def test_extra_get_or_nonfinite_measurement_fails(self):
        """A duplicate request and invalid memory observation remain visible even after successful completion."""
        metrics, events = evidence()
        self.assertFalse(check_automatic_resume(metrics, events+[{"seq":99,"kind":"get","request":None,"value":0}])["passed"])
        metrics[1]["native_peak_bytes"] = float('nan')
        self.assertFalse(check_automatic_resume(metrics, events)["passed"])


if __name__ == '__main__':
    unittest.main()
