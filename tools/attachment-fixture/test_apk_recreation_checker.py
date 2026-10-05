"""Process-recreation evidence must fail unless a real partial body ended with the process and one transfer finished."""

from copy import deepcopy
import unittest

from apk_recreation_checker import CIPHERTEXT_OVERHEAD, HOLD_OFFSET, check_recreation

PAYLOAD = 3 * 1024 * 1024
SIZE = PAYLOAD + CIPHERTEXT_OVERHEAD


def evidence(distribution="Zapstore", resumed_at=HOLD_OFFSET):
    """A complete, honest run: held at the prefix, the process died, and a ranged retry fetched only the rest."""
    zapstore = distribution == "Zapstore"
    metrics = [
        {"phase": "apk-recreate-held", "case": "valid", "received_bytes": HOLD_OFFSET - 4096,
         "total_ciphertext_bytes": SIZE},
        {"phase": "apk-recreate-found", "native_state": "DOWNLOADING", "attempt": 2},
        {"phase": "apk-transfer-recreated", "success": True, "payload_bytes": PAYLOAD, "elapsed_ms": 2000.0,
         "java_peak_bytes": 40_000_000, "native_peak_bytes": 90_000_000},
        {"phase": "apk-received", "case": "valid", "bytes": PAYLOAD, "exact": True},
        {"phase": "apk-dispatch", "case": "valid", "permission": "allowed" if zapstore else "n/a",
         "result": "Opened" if zapstore else "InstallUnsupported", "installer_shown": zapstore,
         "installer_observed_ms": 200 if zapstore else 1000,
         "installer_settled": zapstore, "installer_staging_ms": 200 if zapstore else 0, "dispatch_ms": 30.0,
         "transfer_reused": True},
        {"phase": "apk-recreate-complete", "self_update_enabled": zapstore},
    ]
    rows = [
        (1, None, "upload", SIZE), (2, 1, "upload_complete", 0), (3, None, "hold_resumable_acquisition", 0),
        (4, None, "get", 0), (5, 4, "range_offset", 0), (6, 4, "status", 200), (7, 4, "body_bytes", HOLD_OFFSET),
        (8, 4, "held", HOLD_OFFSET), (9, 4, "disconnect", 0), (10, None, "release_acquisition", 0),
        (11, None, "get", 0), (12, 11, "range_requested_offset", resumed_at), (13, 11, "if_range_match", 1),
        (14, 11, "range_offset", resumed_at), (15, 11, "status", 206 if resumed_at else 200),
        (16, 11, "body_bytes", SIZE - resumed_at), (17, 11, "complete", 0),
    ]
    events = [{"seq": s, "request": r, "kind": k, "value": v, "fixture": "upload-1", "at_ns": s} for s, r, k, v in rows]
    return metrics, events


def verdict(metrics, events, distribution="Zapstore", abrupt=True):
    """The checker's verdict for one run."""
    return check_recreation(metrics, events, distribution, abrupt)


class RecreationCheckerTest(unittest.TestCase):
    """Each independent proof is removed or falsified in turn and the run must fail closed."""

    def test_a_complete_run_passes_on_both_distributions_and_claims_no_installation(self):
        """Zapstore reaches the installer and Play refuses it, and neither claims an installation or performance."""
        for distribution in ("Zapstore", "Play"):
            result = verdict(*evidence(distribution), distribution)
            self.assertTrue(result["passed"], result["violations"])
            self.assertFalse(result["installation_confirmed"])
            self.assertFalse(result["performance_qualified"])

    def test_a_retry_that_restarts_from_zero_is_also_a_valid_recreation(self):
        """The checker records where the replacement resumed and requires only that it delivered exactly the rest."""
        result = verdict(*evidence(resumed_at=0))
        self.assertTrue(result["passed"], result["violations"])

    def test_a_process_that_did_not_end_abruptly_fails(self):
        """A held stage that exited cleanly, or was cancelled, is not process recreation."""
        self.assertFalse(verdict(*evidence(), abrupt=False)["passed"])
        self.assertFalse(verdict(*evidence(), abrupt=None)["passed"])

    def test_every_missing_metric_fails(self):
        """The held prefix, the native state found, the measured transfer, the exact file and the dispatch are all required."""
        metrics, events = evidence()
        for index in range(len(metrics)):
            altered = deepcopy(metrics)
            del altered[index]
            self.assertFalse(verdict(altered, events)["passed"], metrics[index]["phase"])

    def test_a_prefix_that_is_not_a_real_partial_body_fails(self):
        """Nothing received, a complete body, or a total that is not the upload's size cannot prove a mid-body death."""
        metrics, events = evidence()
        for received, total in ((0, SIZE), (SIZE, SIZE), (SIZE + 1, SIZE), (HOLD_OFFSET, SIZE + 1)):
            altered = deepcopy(metrics)
            altered[0]["received_bytes"], altered[0]["total_ciphertext_bytes"] = received, total
            self.assertFalse(verdict(altered, events)["passed"], (received, total))

    def test_a_file_that_is_not_proven_complete_fails(self):
        """The published file must be exact and match the measured size before any installer dispatch."""
        metrics, events = evidence()
        for field, value in (("exact", False), ("bytes", PAYLOAD - 1)):
            altered = deepcopy(metrics)
            altered[3][field] = value
            self.assertFalse(verdict(altered, events)["passed"], field)
        altered = deepcopy(metrics)
        altered[2]["success"] = False
        self.assertFalse(verdict(altered, events)["passed"])

    def test_a_wrong_platform_outcome_or_checkpoint_fails(self):
        """Zapstore must open and settle the installer, Play must refuse it, and the checkpoint names the build."""
        metrics, events = evidence()
        for field, value in (("result", "InvalidPackage"), ("installer_shown", False), ("installer_settled", False),
                             ("transfer_reused", False), ("permission", "denied")):
            altered = deepcopy(metrics)
            altered[4][field] = value
            self.assertFalse(verdict(altered, events)["passed"], field)
        altered = deepcopy(metrics)
        altered[5]["self_update_enabled"] = False
        self.assertFalse(verdict(altered, events)["passed"])
        play = evidence("Play")
        self.assertFalse(verdict(*play, "Zapstore")["passed"])
        self.assertFalse(verdict(*evidence(), "Play")["passed"])

    def test_the_dispatch_must_have_been_watched_for_an_installer(self):
        """A missing, non-numeric or short observation means an absent installer was never actually looked for."""
        for distribution in ("Zapstore", "Play"):
            metrics, events = evidence(distribution)
            for observed in (None, "1000", True, -1, float("nan")):
                altered = deepcopy(metrics)
                if observed is None:
                    del altered[4]["installer_observed_ms"]
                else:
                    altered[4]["installer_observed_ms"] = observed
                self.assertFalse(verdict(altered, events, distribution)["passed"], (distribution, observed))
        metrics, events = evidence("Play")
        metrics[4]["installer_observed_ms"] = 100
        self.assertFalse(verdict(metrics, events, "Play")["passed"])

    def test_the_interrupted_acquisition_must_be_held_and_never_complete(self):
        """The first GET carries the hold and a disconnect, and a completion would mean nothing was interrupted."""
        metrics, events = evidence()
        altered = deepcopy(events)
        altered.append({"seq": 20, "request": 4, "kind": "complete", "value": 0, "fixture": "upload-1", "at_ns": 20})
        self.assertFalse(verdict(metrics, altered)["passed"])
        altered = [e for e in events if e["kind"] != "disconnect"]
        self.assertFalse(verdict(metrics, altered)["passed"])
        altered = [e for e in events if e["kind"] != "held"]
        self.assertFalse(verdict(metrics, altered)["passed"])
        altered = deepcopy(events)
        next(e for e in altered if e["kind"] == "held")["value"] = HOLD_OFFSET - 1
        self.assertFalse(verdict(metrics, altered)["passed"])

    def test_the_replacement_may_not_overlap_the_interrupted_acquisition(self):
        """Two acquisitions alive at once would be a duplicate download, so the second must start after the first ended."""
        metrics, events = evidence()
        altered = deepcopy(events)
        for e in altered:
            if e["seq"] == 11:
                e["seq"] = 8
            if e["request"] == 11:
                e["request"] = 8
        self.assertFalse(verdict(metrics, altered)["passed"])

    def test_the_replacement_must_deliver_exactly_the_bytes_it_still_needed(self):
        """Extra, missing or unexplained body bytes after the resume point fail."""
        metrics, events = evidence()
        for delta in (-1, 1):
            altered = deepcopy(events)
            next(e for e in altered if e["request"] == 11 and e["kind"] == "body_bytes")["value"] += delta
            self.assertFalse(verdict(metrics, altered)["passed"], delta)
        altered = [e for e in events if not (e["request"] == 11 and e["kind"] == "complete")]
        self.assertFalse(verdict(metrics, altered)["passed"])
        altered = deepcopy(events)
        next(e for e in altered if e["kind"] == "range_offset" and e["request"] == 11)["value"] = HOLD_OFFSET + 1
        self.assertFalse(verdict(metrics, altered)["passed"])

    def test_extra_requests_and_unexplained_controls_fail(self):
        """A third GET, a HEAD, a cancellation, a payload fetch or a missing control event is a different scenario."""
        metrics, events = evidence()
        for kind in ("get", "head", "cancel_marker", "hold_acquisition", "payload_fetch"):
            altered = deepcopy(events)
            altered.append({"seq": 30, "request": None, "kind": kind, "value": 0, "fixture": "upload-1", "at_ns": 30})
            self.assertFalse(verdict(metrics, altered)["passed"], kind)
        for kind in ("hold_resumable_acquisition", "release_acquisition"):
            altered = [e for e in events if e["kind"] != kind]
            self.assertFalse(verdict(metrics, altered)["passed"], kind)

    def test_one_genuine_upload_is_required(self):
        """The upload must be the package itself plus the AEAD tag, once, and complete."""
        metrics, events = evidence()
        altered = deepcopy(events)
        altered[0]["value"] += 1
        self.assertFalse(verdict(metrics, altered)["passed"])
        altered = [e for e in events if e["kind"] != "upload_complete"]
        self.assertFalse(verdict(metrics, altered)["passed"])

    def test_malformed_input_fails_closed(self):
        """Non-dict metrics, a non-list ledger or an unknown distribution never raise or pass."""
        metrics, events = evidence()
        self.assertFalse(verdict(["not a metric"], events)["passed"])
        self.assertFalse(verdict(metrics, None)["passed"])
        self.assertFalse(verdict(metrics, events, "Other")["passed"])


if __name__ == "__main__":
    unittest.main()
