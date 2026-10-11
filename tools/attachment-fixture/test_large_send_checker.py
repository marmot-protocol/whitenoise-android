"""Checker contracts: every scenario fact and the fixture's own ledger must agree before the run qualifies."""

import copy
import unittest

from large_send_checker import JAVA_GROWTH_LIMIT, MIB, NATIVE_GROWTH_LIMIT, TAG_BYTES, check_large_send

LARGE = 512 * MIB - TAG_BYTES
CANCEL = 256 * MIB
RETRY = 64 * MIB


def passing_metrics():
    """Rows a fully qualified run reports."""
    return [
        {"scenario": "large-send", "bytes": LARGE, "ms": 60_000, "sha256_matches": True,
         "java_baseline_bytes": 10 * MIB, "java_peak_bytes": 16 * MIB,
         "native_baseline_bytes": 70 * MIB, "native_peak_bytes": 90 * MIB,
         "phases": ["PREPARING", "ENCRYPTING", "UPLOADING", "SENDING"], "monotonic": True,
         "final_phase": "SENDING", "snapshot_deleted": True},
        {"scenario": "cancel", "bytes": CANCEL, "cancelled_while_working": True, "published": False,
         "snapshot_deleted": True},
        {"scenario": "retry", "bytes": RETRY, "first_attempt_unpublished": True, "kept_for_retry": True,
         "sha256_matches": True, "snapshot_deleted": True},
    ]


def ledger(*uploads, after=0):
    """Ledger rows for uploads given as (ciphertext size, completed), numbered after the first [after] rows."""
    events = []
    for size, completed in uploads:
        seq = after + len(events) + 1
        events.append({"seq": seq, "request": None, "kind": "upload", "value": size})
        events.append({"seq": seq + 1, "request": seq, "kind": "upload_complete" if completed else "upload_disconnect",
                       "value": 0})
    return events


PASSING_LEDGER = ledger((LARGE + TAG_BYTES, True), (RETRY + TAG_BYTES, True))


class LargeSendCheckerTest(unittest.TestCase):
    """Each fact the device reports can, on its own, fail the run."""

    def test_a_complete_run_passes(self):
        """All scenario facts true and one completed upload each for the large and retried files."""
        result = check_large_send(passing_metrics(), PASSING_LEDGER)
        self.assertEqual([], result["violations"])
        self.assertTrue(result["at_ceiling"])

    def test_an_unmeasured_heap_or_a_repeated_row_fails(self):
        """A sampler that never ran, or a scenario reported twice, does not qualify."""
        metrics = passing_metrics()
        metrics[0]["java_baseline_bytes"] = metrics[0]["java_peak_bytes"] = 0
        self.assertFalse(check_large_send(metrics, PASSING_LEDGER)["passed"])
        self.assertFalse(check_large_send(passing_metrics() + passing_metrics()[:1], PASSING_LEDGER)["passed"])

    def test_a_lowered_run_is_not_at_the_ceiling(self):
        """A run lowered for a small emulator may pass, but it is reported as below the ceiling."""
        metrics = passing_metrics()
        metrics[0]["bytes"] = 300 * MIB
        ledger_rows = ledger((300 * MIB + TAG_BYTES, True), (RETRY + TAG_BYTES, True))
        result = check_large_send(metrics, ledger_rows)
        self.assertTrue(result["passed"])
        self.assertFalse(result["at_ceiling"])

    def test_heap_growth_beyond_the_limits_fails(self):
        """Java growth past 32 MiB, or native growth past 64 MiB, means the file reached the heap."""
        for side, limit in (("java", JAVA_GROWTH_LIMIT), ("native", NATIVE_GROWTH_LIMIT)):
            metrics = passing_metrics()
            metrics[0][f"{side}_peak_bytes"] = metrics[0][f"{side}_baseline_bytes"] + limit + 1
            self.assertFalse(check_large_send(metrics, PASSING_LEDGER)["passed"], side)

    def test_missing_or_contrary_scenario_facts_fail(self):
        """Every boolean fact must be exactly true, and a missing row or measurement fails closed."""
        for index, row in enumerate(passing_metrics()):
            for fact in [key for key, value in row.items() if value is True]:
                metrics = passing_metrics()
                metrics[index][fact] = "true"
                self.assertFalse(check_large_send(metrics, PASSING_LEDGER)["passed"], (row["scenario"], fact))
            self.assertFalse(check_large_send(passing_metrics()[:index] + passing_metrics()[index + 1:],
                                              PASSING_LEDGER)["passed"])
        metrics = passing_metrics()
        metrics[0]["java_peak_bytes"] = True
        self.assertFalse(check_large_send(metrics, PASSING_LEDGER)["passed"])

    def test_progress_must_show_every_step_and_end_sending(self):
        """A run that skipped a step, or never reached sending, does not qualify."""
        for phases, final in ((["PREPARING", "UPLOADING", "SENDING"], "SENDING"),
                              (["PREPARING", "ENCRYPTING", "UPLOADING"], "UPLOADING")):
            metrics = passing_metrics()
            metrics[0]["phases"], metrics[0]["final_phase"] = phases, final
            self.assertFalse(check_large_send(metrics, PASSING_LEDGER)["passed"], phases)

    def test_a_published_cancel_fails(self):
        """The cancelled send must never reach the group."""
        metrics = passing_metrics()
        metrics[1]["published"] = True
        self.assertFalse(check_large_send(metrics, PASSING_LEDGER)["passed"])

    def test_the_ledger_must_agree_with_the_device(self):
        """A completed cancelled upload, a second large upload or a missing retried upload all fail."""
        bad_ledgers = [
            PASSING_LEDGER + ledger((CANCEL + TAG_BYTES, True), after=len(PASSING_LEDGER)),
            PASSING_LEDGER + ledger((LARGE + TAG_BYTES, True), after=len(PASSING_LEDGER)),
            ledger((LARGE + TAG_BYTES, True)),
        ]
        for events in bad_ledgers:
            self.assertFalse(check_large_send(passing_metrics(), copy.deepcopy(events))["passed"])
        interrupted = PASSING_LEDGER + ledger((CANCEL + TAG_BYTES, False), after=len(PASSING_LEDGER))
        self.assertTrue(check_large_send(passing_metrics(), interrupted)["passed"], "a disconnected cancel is fine")


if __name__ == "__main__":
    unittest.main()
