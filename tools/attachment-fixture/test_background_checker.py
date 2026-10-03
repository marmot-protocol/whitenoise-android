"""A foreground-only or rescheduled-without-progress transfer cannot pass the background fixture."""

from copy import deepcopy
import unittest

from background_checker import check_background, PAYLOAD_BYTES


def evidence(api=30):
    """Model separately reported Android assertions and independently committed host bytes."""
    metrics = [{"phase": "platform-background", "success": True, "activity_stopped": True,
                "plaintext_exact": True, "interactive_intent_retired": True,
                "actual_execution_class": "foreground-work" if api < 34 else "user-initiated-job",
                "platform_job_stop_qualified": False, "background_millis": 30000,
                "transfer_active_after_30_seconds": True, "screen_lock_qualified": False,
                "body_bytes_while_backgrounded": 1024 * 1024},
               {"phase": "platform-background-overall", "success": True, "payload_bytes": PAYLOAD_BYTES,
                "elapsed_ms": 35000, "java_peak_bytes": 100, "native_peak_bytes": 100}]
    rows = [(None, "upload", 0), (1, "upload_bytes", PAYLOAD_BYTES + 16), (1, "upload_complete", 0),
            (None, "get", 0), (4, "body_bytes", 2 * 1024 * 1024), (None, "background_start", 0),
            (4, "body_bytes", 1024 * 1024), (None, "background_end", 0),
            (4, "body_bytes", 1024 * 1024 + 16), (4, "complete", 0)]
    events = [{"seq": seq, "request": request, "kind": kind, "value": value, "at_ns": (0 if seq < 7 else 26_000_000_000 if seq == 7 else 31_000_000_000)}
              for seq, (request, kind, value) in enumerate(rows, 1)]
    return metrics, events, {"api": str(api), "abi": "arm64-v8a"}


class BackgroundEvidenceTest(unittest.TestCase):
    """Refuse incomplete background evidence and keep unrelated qualification explicitly false."""

    def test_both_platform_execution_classes_keep_scope(self):
        """API30 foreground work and API36 user-initiated jobs require different actual evidence."""
        for api in (30, 36):
            result = check_background(*evidence(api))
            self.assertTrue(result["passed"], result)
            self.assertFalse(result["platform_job_stop_qualified"])
            self.assertFalse(result["screen_lock_qualified"])
            self.assertFalse(result["performance_qualified"])

    def test_locked_classification_cannot_be_inferred_from_background_success(self):
        """Background-only evidence cannot qualify keyguard; the Android lock assertion is mandatory."""
        metrics, events, env = evidence()
        missing = check_background(metrics, events, env, locked=True)
        self.assertFalse(missing["passed"])
        self.assertFalse(missing["screen_lock_qualified"])
        metrics[0]["screen_lock_qualified"] = True
        result = check_background(metrics, events, env, locked=True)
        self.assertTrue(result["passed"], result)
        self.assertTrue(result["screen_lock_qualified"])
        self.assertFalse(check_background(metrics, events, env)["passed"])

    def test_every_missing_event_fails(self):
        """Successful client completion cannot compensate for missing external bytes or markers."""
        metrics, events, env = evidence()
        for index in range(len(events)):
            with self.subTest(index=index):
                self.assertFalse(check_background(metrics, events[:index] + events[index + 1:], env)["passed"])

    def test_foreground_activity_wrong_class_and_short_interval_fail(self):
        """No actual platform classification or insufficient background time may be accepted."""
        metrics, events, env = evidence()
        for field, value in (("activity_stopped", False), ("actual_execution_class", "ordinary-work"),
                             ("background_millis", 29999), ("background_millis", float("nan")),
                             ("platform_job_stop_qualified", True), ("body_bytes_while_backgrounded", 0),
                             ("transfer_active_after_30_seconds", False)):
            altered = deepcopy(metrics)
            altered[0][field] = value
            self.assertFalse(check_background(altered, events, env)["passed"])

    def test_early_completion_or_no_late_host_bytes_fails(self):
        """Thirty seconds behind Home cannot qualify an instantly completed foreground body."""
        metrics, events, env = evidence()
        early = deepcopy(events)
        early[6]["at_ns"] = 1_000_000_000
        self.assertFalse(check_background(metrics, early, env)["passed"])
        completed = deepcopy(events)
        completed.insert(7, {"seq": 7.5, "request": 4, "kind": "complete", "value": 0, "at_ns": 27_000_000_000})
        self.assertFalse(check_background(metrics, completed, env)["passed"])

    def test_extra_download_or_invalid_measurement_fails(self):
        """An extra acquisition cannot be hidden by a later successful retained result."""
        metrics, events, env = evidence()
        self.assertFalse(check_background(metrics, events + [{"seq": 99, "request": None, "kind": "get", "value": 0}], env)["passed"])
        metrics[1]["native_peak_bytes"] = -1
        self.assertFalse(check_background(metrics, events, env)["passed"])


if __name__ == "__main__":
    unittest.main()
