"""Regression tests for the independently enforced native cancellation evidence gate."""

import copy
import unittest

from cancellation_checker import check_cancellation

ENVIRONMENT = {"api": "30", "abi": "arm64-v8a"}


class CancellationCheckerTest(unittest.TestCase):
    """Successful callbacks cannot conceal an open socket, repeated requests or missing exact-byte retry."""

    def setUp(self):
        """Use distinct request and terminal event identities with a full quiet interval."""
        self.metrics = [
            {"phase": "held-body-cancellation", "success": True, "ack_elapsed_ms": 10,
             "socket_close_elapsed_ms": 20, "quiet_seconds": 30, "ordinary_terminal_joins": 10, "active_joins": 10,
             "deliberate_retry_exact_bytes": True},
            {"phase": "held-body-cancellation-overall", "success": True, "elapsed_ms": 31000,
             "java_peak_bytes": 10_000_000, "native_peak_bytes": 80_000_000},
        ]
        self.events = []
        for kind, request, value, at_ns in (
            ("upload", None, 1040, 0), ("upload_bytes", 1, 1040, 0), ("upload_complete", 1, 0, 0),
            ("get", None, 0, 1_000_000), ("body_bytes", 4, 1024, 2_000_000),
            ("held", 4, 1024, 3_000_000), ("cancel_marker", None, 0, 4_000_000),
            ("disconnect", 4, 0, 24_000_000), ("release_acquisition", None, 0, 30_024_000_000),
            ("get", None, 0, 30_025_000_000), ("body_bytes", 10, 1040, 30_026_000_000),
            ("complete", 10, 0, 30_027_000_000),
        ):
            self.events.append({"seq": len(self.events) + 1, "kind": kind, "request": request,
                                "value": value, "at_ns": at_ns})

    def check(self, metrics=None, events=None):
        """Exercise the same checker called by the Android fixture runner."""
        return check_cancellation(self.metrics if metrics is None else metrics,
                                  self.events if events is None else events, ENVIRONMENT, ENVIRONMENT)

    def test_complete_independent_evidence_passes(self):
        self.assertTrue(self.check()["passed"])

    def test_slow_ack_or_socket_close_fails(self):
        for field in ("ack_elapsed_ms", "socket_close_elapsed_ms"):
            with self.subTest(field=field):
                metrics = copy.deepcopy(self.metrics)
                metrics[0][field] = 5001
                self.assertFalse(self.check(metrics=metrics)["passed"])

    def test_memory_ceiling_is_enforced(self):
        self.metrics[1]["native_peak_bytes"] = 128 * 1024**2 + 1
        self.assertFalse(self.check()["passed"])

    def test_callback_without_server_disconnect_fails(self):
        events = [e for e in self.events if e["kind"] != "disconnect"]
        self.assertFalse(self.check(events=events)["passed"])

    def test_hidden_request_during_quiet_interval_fails(self):
        self.events.append({"seq": 13, "kind": "get", "request": None, "value": 0, "at_ns": 100_000_000})
        self.assertFalse(self.check()["passed"])

    def test_claimed_quiet_interval_needs_server_time(self):
        self.events[8]["at_ns"] = 29_024_000_000
        self.assertFalse(self.check()["passed"])

    def test_missing_join_proof_cannot_qualify(self):
        """A passed cancel/retry alone cannot claim active and terminal joins avoided acquisition."""
        for key in ("active_joins", "ordinary_terminal_joins"):
            with self.subTest(key=key):
                metrics = copy.deepcopy(self.metrics)
                del metrics[0][key]
                self.assertFalse(self.check(metrics=metrics)["passed"])

    def test_wrong_completed_request_cannot_pass(self):
        self.events[-1]["request"] = 4
        self.assertFalse(self.check()["passed"])

    def test_missing_or_invalid_measurement_fails(self):
        for value in (None, True, float("nan"), float("inf"), -1):
            with self.subTest(value=value):
                metrics = copy.deepcopy(self.metrics)
                metrics[0]["ack_elapsed_ms"] = value
                self.assertFalse(self.check(metrics=metrics)["passed"])


if __name__ == "__main__":
    unittest.main()
