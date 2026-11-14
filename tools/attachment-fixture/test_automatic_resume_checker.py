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
                "elapsed_ms": 35000, "java_peak_bytes": 100, "native_peak_bytes": 100},
               {"phase": "automatic-resume-work-query", "state": "SUCCEEDED", "same_work_id": True,
                "initial_run_attempt": 1, "run_attempt": 2}]
    rows = [(None, "upload", 0), (1, "upload_bytes", PAYLOAD_BYTES+16), (1, "upload_complete", 0),
            (None, "get", 0), (4, "range_offset", 0), (4, "status", 200), (4, "body_bytes", PREFIX_BYTES),
            (4, "held", PREFIX_BYTES), (None, "platform_stop_marker", 0), (None, "interrupt_acquisition", 0),
            (4, "disconnect", 0), (None, "get", 0), (12, "range_requested_offset", PREFIX_BYTES),
            (12, "if_range_match", 1), (12, "range_offset", PREFIX_BYTES), (12, "status", 206),
            (12, "body_bytes", 1024*1024), (12, "held", 3*1024*1024), (None, "release_acquisition", 0),
            (12, "body_bytes", 1024*1024+16), (12, "complete", 0)]
    return metrics, [{"seq": s, "request": r, "kind": k, "value": v} for s, (r,k,v) in enumerate(rows,1)]


def with_aborted_setup(events):
    """Keep request provenance while inserting a real pre-stop setup loss."""
    shifted = deepcopy(events)
    for event in shifted:
        if event["seq"] >= 4:
            event["seq"] += 5
        if event["request"] is not None and event["request"] >= 4:
            event["request"] += 5
    setup = [(None, "get", 0), (4, "range_offset", 0), (4, "status", 200),
             (4, "body_bytes", 442368), (4, "disconnect", 0)]
    return shifted[:3] + [{"seq": i, "request": r, "kind": k, "value": v}
                          for i, (r, k, v) in enumerate(setup, 4)] + shifted[3:]


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

    def test_aborted_setup_is_reported_and_selected_pair_still_qualifies(self):
        metrics, events = evidence()
        result = check_automatic_resume(metrics, with_aborted_setup(events))
        self.assertTrue(result["passed"], result)
        self.assertEqual(1, result["setup_acquisition_count"])
        self.assertEqual(442368, result["setup_body_bytes"])
        self.assertEqual(3, result["ledger_acquisition_count"])
        self.assertEqual(PAYLOAD_BYTES + 16 + 442368, result["ledger_body_bytes"])
        self.assertFalse(result["performance_qualified"])

    def test_selected_recovery_boundaries_reject_corrupt_evidence_even_with_setup(self):
        metrics, original = evidence()
        original = with_aborted_setup(original)
        def change(kind, field, value, request=None):
            rows = deepcopy(original)
            row = next(e for e in rows if e["kind"] == kind and (request is None or e["request"] == request))
            row[field] = value
            return rows
        cases = {
            "multiple post-stop acquisitions": original + [{"seq": 99, "request": None, "kind": "get", "value": 0}],
            "stale initial hold": change("disconnect", "seq", 12, 9),
            "stale resumed hold": change("held", "seq", 13, 17),
            "wrong checkpoint": change("range_requested_offset", "value", PREFIX_BYTES - 1),
            "pre-stop Range event": change("range_requested_offset", "seq", 13),
            "wrong validator": change("if_range_match", "value", 0),
            "unmatched validator": change("if_range_match", "request", 4),
            "early resumed disconnect": change("complete", "kind", "disconnect"),
            "early resumed complete": change("complete", "seq", 23),
            "early release": change("release_acquisition", "seq", 22),
            "initial byte mismatch": change("body_bytes", "value", PREFIX_BYTES - 1, 9),
            "resumed byte mismatch": change("body_bytes", "value", 1024 * 1024 - 1, 17),
            "wrong live hold": change("held", "value", PREFIX_BYTES, 17),
            "unclosed setup": change("disconnect", "kind", "complete", 4),
        }
        compensated = deepcopy(original)
        next(e for e in compensated if e["kind"] == "body_bytes" and e["request"] == 9)["value"] -= 1
        next(e for e in compensated if e["kind"] == "body_bytes" and e["request"] == 17)["value"] += 1
        cases["compensated selected byte totals"] = compensated
        for label, events in cases.items():
            with self.subTest(label=label):
                self.assertFalse(check_automatic_resume(metrics, events)["passed"], label)

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
        """Native request and Android success times are independent diagnostics, never a qualification input."""
        metrics, events = evidence()
        metrics[0].update({"resume_work_succeeded_ms": 35000, "resume_request_after_stop_ms": 15000})
        self.assertTrue(check_automatic_resume(metrics, events)["passed"])

    def test_native_body_can_resume_while_android_work_is_still_queued(self):
        """An earlier native observation cannot override the required eventual database proof."""
        metrics, events = evidence()
        metrics.append({"phase": "native-resume-observed", "state": "DOWNLOADING", "work_state": "ENQUEUED"})
        self.assertTrue(check_automatic_resume(metrics, events)["passed"])

    def test_final_database_proof_must_be_unique_and_present(self):
        metrics, events = evidence()
        self.assertFalse(check_automatic_resume(metrics[:2], events)["passed"])
        self.assertFalse(check_automatic_resume(metrics + [deepcopy(metrics[2])], events)["passed"])

    def test_wrong_work_or_non_successful_final_state_fails(self):
        for state in ("ENQUEUED", "RUNNING", "FAILED", "CANCELLED"):
            metrics, events = evidence()
            metrics[2]["state"] = state
            self.assertFalse(check_automatic_resume(metrics, events)["passed"], state)
        metrics, events = evidence()
        metrics[2]["same_work_id"] = False
        self.assertFalse(check_automatic_resume(metrics, events)["passed"])

    def test_attempt_count_must_advance_without_boolean_aliases(self):
        for initial, current in ((1, 1), (1, 0), (-1, 2), (True, 2), (False, 2), (0, True), (0, False), (1, 2.0)):
            metrics, events = evidence()
            metrics[2].update(initial_run_attempt=initial, run_attempt=current)
            self.assertFalse(check_automatic_resume(metrics, events)["passed"], (initial, current))

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
