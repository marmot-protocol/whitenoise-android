"""Checker contracts for the background receipt fixture: every claim is proven by both the device and the ledger."""

import copy
import unittest

from background_receipt_checker import check_background_receipt

ROW = {
    "phase": "background-receipt",
    "success": True,
    "background": {"attachments": 4, "activity_stopped": True, "conversation_opened": False,
                   "durable_work_succeeded": 4, "interactive_intent": False, "plaintext_exact": True},
    "returned": {"local_reads": 4, "new_http_gets": 0},
    "disabled_type": {"enabled_fetched": 1, "disabled_queued": False, "disabled_fetched": False},
    "stopped_recreated": {"actual_platform_stop": True, "runtime_recreated": True, "same_work_succeeded": True,
                          "plaintext_exact": True},
}


def ledger():
    """A passing ledger: four background fetches, one enabled and one never-fetched disabled slot, a resumed file."""
    events, seq = [], 0

    def add(fixture, kind):
        nonlocal seq
        seq += 1
        events.append({"seq": seq, "request": None, "fixture": fixture, "kind": kind, "value": None, "at_ns": seq})

    for token in ("a", "b", "c", "d"):
        add(token, "upload")
    add("control", "background_start")
    for token in ("a", "b", "c", "d"):
        add(token, "get")
    add("control", "background_end")
    add("e", "upload")
    add("f", "upload")
    add("e", "get")
    add("g", "upload")
    add("g", "get")
    add("control", "platform_stop_marker")
    add("g", "get")
    return events


class BackgroundReceiptCheckerTest(unittest.TestCase):
    """Each violation the fixture exists to catch is rejected; the honest run passes."""

    def test_an_honest_run_passes(self):
        """Four background fetches, nothing refetched, the disabled type untouched and a resumed stop pass."""
        self.assertTrue(check_background_receipt([ROW], ledger())["passed"])

    def test_a_refetch_after_return_fails(self):
        """Opening the app must use retained data; a later GET of a background attachment is a violation."""
        events = ledger()
        events.append({"seq": 99, "request": None, "fixture": "a", "kind": "get", "value": None, "at_ns": 99})
        self.assertFalse(check_background_receipt([ROW], events)["passed"])

    def test_a_fetched_disabled_type_fails(self):
        """Every upload fetched means the disabled type was downloaded."""
        events = ledger()
        events.append({"seq": 99, "request": None, "fixture": "f", "kind": "get", "value": None, "at_ns": 99})
        self.assertFalse(check_background_receipt([ROW], events)["passed"])

    def test_a_missing_background_fetch_fails(self):
        """An attachment that was not fetched while backgrounded is not background scheduling."""
        events = [e for e in ledger() if not (e["fixture"] == "d" and e["kind"] == "get")]
        self.assertFalse(check_background_receipt([ROW], events)["passed"])

    def test_a_stop_that_never_resumed_fails(self):
        """The stopped file must be fetched again after the platform stop."""
        events = ledger()[:-1]
        self.assertFalse(check_background_receipt([ROW], events)["passed"])

    def test_any_unproven_device_fact_fails(self):
        """A false or missing device claim fails closed, as does a duplicated or missing row."""
        for section, field in (("background", "activity_stopped"), ("background", "conversation_opened"),
                               ("returned", "new_http_gets"), ("disabled_type", "disabled_fetched"),
                               ("stopped_recreated", "runtime_recreated")):
            row = copy.deepcopy(ROW)
            row[section][field] = not row[section][field] if isinstance(row[section][field], bool) else 1
            self.assertFalse(check_background_receipt([row], ledger())["passed"], field)
        self.assertFalse(check_background_receipt([ROW, ROW], ledger())["passed"])
        self.assertFalse(check_background_receipt([], ledger())["passed"])
        self.assertFalse(check_background_receipt("not a list", ledger())["passed"])


if __name__ == "__main__":
    unittest.main()
