"""Phase qualification must fail when any independent progress, failure or recovery proof is absent."""

from copy import deepcopy
import unittest

from phases_checker import CIPHERTEXT_OVERHEAD, FAILING_BYTES, PAYLOAD_BYTES, check_phases

PACED = PAYLOAD_BYTES + CIPHERTEXT_OVERHEAD
FAILING = FAILING_BYTES + CIPHERTEXT_OVERHEAD


def group(phase, samples=1, low=0, high=0, total=None, attempt=1, retry_at=False, monotonic=True):
    """One collapsed run of identical native samples, as the device probe reports it."""
    return {"phase": phase, "attempt": attempt, "total": total, "retry_at_present": retry_at,
            "samples": samples, "received_min": low, "received_max": high, "monotonic": monotonic}


def metric(scenario, sequence, **extra):
    """A device scenario report; the checker never trusts a field the probe did not assert."""
    return {"phase": "native-phases", "scenario": scenario, "success": True, "plaintext_exact": True,
            "sequence": sequence, **extra}


def evidence(failure="RETRY_SCHEDULED"):
    """Construct the declared device and ledger shape; socket behavior has separate real HTTP tests."""
    known = metric("known-length", [
        group("QUEUED"),
        group("DOWNLOADING", samples=12, low=65536, high=PACED, total=PACED),
        group("VERIFYING_CIPHERTEXT"), group("DECRYPTING"), group("READY", low=PACED, high=PACED, total=PACED)],
        polled_phases=["NOT_REQUESTED", "DOWNLOADING", "VERIFYING_CIPHERTEXT", "DECRYPTING", "READY"])
    live = failure == "RETRY_SCHEDULED"
    failed = metric("failure-recovery", [
        group("QUEUED"), group("DOWNLOADING", total=FAILING), group(failure, retry_at=live),
        *([group("CANCELLED")] if live else []),
        group("QUEUED", attempt=2), group("DOWNLOADING", samples=2, low=1, high=FAILING, total=FAILING, attempt=2),
        group("READY")], polled_phases=["NOT_REQUESTED", "FAILED", "DOWNLOADING", "READY"],
        failure_phase=failure, retry_accepted=True, **({"cancel_acknowledged": True} if live else {}))
    metrics = [known, failed, {"phase": "native-phases-overall", "success": True, "payload_bytes": PAYLOAD_BYTES,
                               "elapsed_ms": 9000, "java_peak_bytes": 40 * 1024 * 1024,
                               "native_peak_bytes": 128 * 1024 * 1024}]
    rows = [(None, "upload", PACED, "upload-a"), (1, "upload_complete", 0, "upload-a"),
            (None, "upload", FAILING, "upload-b"), (3, "upload_complete", 0, "upload-b"),
            (None, "control", 0, None, "pace_acquisition"), (None, "control", 0, None, "acquisition_not_found"),
            (None, "get", 0, "upload-a"), (7, "status", 200, "upload-a"), (7, "body_bytes", PACED, "upload-a"),
            (7, "complete", 0, "upload-a"),
            (None, "get", 0, "upload-b"), (11, "status", 404, "upload-b"), (11, "complete", 0, "upload-b"),
            (None, "control", 0, None, "restore_acquisition"),
            (None, "get", 0, "upload-b"), (15, "status", 200, "upload-b"), (15, "body_bytes", FAILING, "upload-b"),
            (15, "complete", 0, "upload-b")]
    events = []
    for seq, row in enumerate(rows, 1):
        request, kind, value, fixture, *named = row
        events.append({"seq": seq, "request": request, "kind": named[0] if named else kind,
                       "value": value, "fixture": fixture})
    return metrics, events


class PhaseEvidenceTest(unittest.TestCase):
    """A closure claim needs real phases, a real failure and a single deliberate recovery."""

    def test_deferred_and_terminal_failure_recoveries_are_valid(self):
        """A live deferred attempt is cancelled before Retry, while a terminal failure needs no cancellation."""
        for failure in ("RETRY_SCHEDULED", "FAILED", "UNAVAILABLE", "RETRY_EXHAUSTED"):
            result = check_phases(*evidence(failure))
            self.assertTrue(result["passed"], (failure, result))
            self.assertEqual(failure, result["failure_phase"])
            self.assertFalse(result["performance_qualified"])
            self.assertTrue(result["observed_post_body_phases"]["DECRYPTING"])

    def test_missing_ledger_events_fail(self):
        """A missing upload, status, body count or control marker is material evidence loss."""
        metrics, events = evidence()
        for index, event in enumerate(events):
            with self.subTest(kind=event["kind"], index=index):
                self.assertFalse(check_phases(metrics, events[:index] + events[index + 1:])["passed"])

    def test_extra_request_or_head_fails(self):
        """One additional HTTP request cannot be hidden by exact final bytes."""
        metrics, events = evidence()
        for kind in ("get", "head"):
            extra = events + [{"seq": 99, "request": None, "kind": kind, "value": 0, "fixture": "upload-a"}]
            self.assertFalse(check_phases(metrics, extra)["passed"], kind)

    def test_backwards_bytes_or_phases_fail(self):
        """A progress bar that moves backwards, or a phase that repeats out of order, is untruthful."""
        metrics, events = evidence()
        altered = deepcopy(metrics)
        altered[0]["sequence"][1]["monotonic"] = False
        self.assertFalse(check_phases(altered, events)["passed"])
        altered = deepcopy(metrics)
        altered[0]["sequence"].insert(3, group("DOWNLOADING", samples=3, high=PACED, total=PACED, attempt=2))
        self.assertFalse(check_phases(altered, events)["passed"])
        altered = deepcopy(metrics)
        altered[0]["sequence"][1], altered[0]["sequence"][2] = altered[0]["sequence"][2], altered[0]["sequence"][1]
        self.assertFalse(check_phases(altered, events)["passed"])

    def test_known_length_must_report_the_true_size_and_real_updates(self):
        """A body phase needs several updates against the exact ciphertext length."""
        metrics, events = evidence()
        for mutate in (lambda g: g.update(total=PAYLOAD_BYTES), lambda g: g.update(samples=1),
                       lambda g: g.update(received_max=g["received_min"]),
                       lambda g: g.update(received_max=PACED + 1)):
            altered = deepcopy(metrics)
            mutate(altered[0]["sequence"][1])
            self.assertFalse(check_phases(altered, events)["passed"])

    def test_total_arriving_after_headers_is_one_body_phase(self):
        """An unknown size followed by the declared size is the same body, not a repeated phase."""
        metrics, events = evidence()
        altered = deepcopy(metrics)
        altered[0]["sequence"].insert(1, group("DOWNLOADING", samples=1, low=0, high=0, total=None))
        altered[0]["sequence"][2]["received_min"] = 0
        self.assertTrue(check_phases(altered, events)["passed"])

    def test_no_post_body_phase_observed_fails(self):
        """Neither the feed nor the authoritative poll showing verification or decryption is a missing proof."""
        metrics, events = evidence()
        altered = deepcopy(metrics)
        altered[0]["sequence"] = [g for g in altered[0]["sequence"] if g["phase"] not in
                                  ("VERIFYING_CIPHERTEXT", "DECRYPTING")]
        altered[0]["polled_phases"] = ["NOT_REQUESTED", "DOWNLOADING", "READY"]
        self.assertFalse(check_phases(altered, events)["passed"])

    def test_a_phase_only_the_authoritative_poll_caught_still_counts(self):
        """The subscription is latest-wins, so a short phase seen only by the read-only poll is real evidence."""
        metrics, events = evidence()
        altered = deepcopy(metrics)
        altered[0]["sequence"] = [g for g in altered[0]["sequence"] if g["phase"] != "DECRYPTING"]
        result = check_phases(altered, events)
        self.assertTrue(result["passed"], result)
        self.assertTrue(result["observed_post_body_phases"]["DECRYPTING"])

    def test_poll_that_moves_backwards_or_is_missing_fails(self):
        """The authoritative poll must itself be a forward-only sequence ending ready."""
        metrics, events = evidence()
        for polled in (["DOWNLOADING", "DECRYPTING", "VERIFYING_CIPHERTEXT", "READY"], ["DOWNLOADING", "DECRYPTING"],
                       ["DOWNLOADING", "DOWNLOADING", "READY"], [], None, ["BOGUS", "READY"]):
            altered = deepcopy(metrics)
            altered[0]["polled_phases"] = polled
            self.assertFalse(check_phases(altered, events)["passed"], polled)

    def test_failure_recovery_requires_each_independent_step(self):
        """No failure phase, an unaccepted Retry or an unacknowledged cancel cannot count as recovery."""
        metrics, events = evidence()
        for field, value in (("failure_phase", "DOWNLOADING"), ("retry_accepted", False),
                             ("cancel_acknowledged", False), ("plaintext_exact", False), ("success", False)):
            altered = deepcopy(metrics)
            altered[1][field] = value
            self.assertFalse(check_phases(altered, events)["passed"], field)
        altered = deepcopy(metrics)
        altered[1]["sequence"] = [g for g in altered[1]["sequence"] if g["phase"] != "CANCELLED"]
        self.assertFalse(check_phases(altered, events)["passed"])
        altered = deepcopy(metrics)
        altered[1]["sequence"] = altered[1]["sequence"][:-1]
        self.assertFalse(check_phases(altered, events)["passed"])
        terminal_metrics, terminal_events = evidence("FAILED")
        terminal_metrics[1]["cancel_acknowledged"] = True
        self.assertFalse(check_phases(terminal_metrics, terminal_events)["passed"])

    def test_invalid_measurements_and_collections_fail(self):
        """Keep timing and memory finite even though a performance budget is deferred."""
        metrics, events = evidence()
        for field in ("elapsed_ms", "java_peak_bytes", "native_peak_bytes"):
            for value in (None, True, -1, float("nan"), float("inf")):
                altered = deepcopy(metrics)
                altered[2][field] = value
                self.assertFalse(check_phases(altered, events)["passed"], (field, value))
        self.assertFalse(check_phases([], events)["passed"])
        self.assertFalse(check_phases(["not a metric"], events)["passed"])
        malformed = deepcopy(metrics)
        malformed[0]["sequence"] = [{"phase": 7}]
        self.assertFalse(check_phases(malformed, events)["passed"])


if __name__ == "__main__":
    unittest.main()
