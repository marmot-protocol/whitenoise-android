"""Checker contracts for the background receipt fixture: every claim is proven by both the device and the ledger."""

import copy
import unittest

from background_receipt_checker import check_background_receipt

MAIN = {
    "phase": "background-receipt",
    "success": True,
    "background": {"attachments": 4, "activity_stopped": True, "visible_activities": 0,
                   "durable_work_succeeded": 4, "interactive_intent": False, "plaintext_exact": True},
    "returned": {"local_reads": 4, "new_http_gets": 0},
    "disabled_type": {"enabled_fetched": 1, "disabled_queued": False, "disabled_fetched": False},
    "network_held": {"queued_without_wifi": True, "constraint": "unmetered", "hold_reason": "network",
                     "same_work_succeeded": True, "plaintext_exact": True, "visible_activities": 0},
    "stopped": {"actual_platform_stop": True, "held_before_stop": True, "work_enqueued_after_stop": True},
}
HELD = {"phase": "background-receipt-held"}
RESUMED = {
    "phase": "background-receipt-resumed",
    "process_restarted": True,
    "app_state_started_before_bind": False,
    "work_waiting_before_bind": True,
    "same_work_succeeded": True,
    "plaintext_exact": True,
    "interactive_intent": False,
    "visible_activities": 0,
}


def rows():
    """The three device rows of an honest two-process run."""
    return [copy.deepcopy(MAIN), copy.deepcopy(HELD), copy.deepcopy(RESUMED)]


class Ledger:
    """Builds fixture-server events with the fields the checker reads."""

    def __init__(self):
        """Starts an empty ledger."""
        self.events = []

    def add(self, fixture, kind, value=None, request=None):
        """Appends one event and returns its sequence number."""
        seq = len(self.events) + 1
        self.events.append({"seq": seq, "request": request, "fixture": fixture, "kind": kind, "value": value,
                            "at_ns": seq})
        return seq

    def control(self, kind, value=None):
        """Appends one control marker."""
        return self.add("control", kind, value)

    def fetch(self, fixture, served=True):
        """Appends one GET, served in full or dropped part-way, and returns its request id."""
        request = self.add(fixture, "get")
        self.add(fixture, "status", 206 if served else 200, request)
        self.add(fixture, "complete" if served else "disconnect", request=request)
        return request


def ledger(retry_first_background_fetch=False):
    """A passing ledger: four background fetches, one fetched and one untouched disabled slot, a network-held
    video fetched after Wi-Fi returns, and a held file fetched in full after the platform stop."""
    book = Ledger()
    book.control("background_start")
    for token in ("a", "b", "c", "d"):
        book.add(token, "upload")
    if retry_first_background_fetch:
        book.fetch("a", served=False)
    for token in ("a", "b", "c", "d"):
        book.fetch(token)
    book.control("background_end")
    book.control("marker", 10)
    book.add("e", "upload", 64 * 1024)
    book.add("f", "upload", 2 * 1024 * 1024)
    book.fetch("e")
    book.control("marker", 11)
    book.control("marker", 20)
    book.add("v", "upload")
    book.control("marker", 21)
    book.fetch("v")
    book.add("g", "upload")
    held = book.add("g", "get")
    book.add("g", "held", 2 * 1024 * 1024, held)
    book.control("platform_stop_marker")
    book.add("g", "disconnect", request=held)
    book.fetch("g")
    return book.events


def without(events, predicate):
    """The ledger with every event matching [predicate] removed."""
    return [e for e in events if not predicate(e)]


def appended(events, fixture, kind, value=None):
    """The ledger with one more event at its end."""
    seq = len(events) + 1
    return events + [{"seq": seq, "request": None, "fixture": fixture, "kind": kind, "value": value, "at_ns": seq}]


class BackgroundReceiptCheckerTest(unittest.TestCase):
    """Each violation the fixture exists to catch is rejected; the honest run passes."""

    def test_an_honest_run_passes(self):
        """Every case proven by the device rows and the ledger passes."""
        result = check_background_receipt(rows(), ledger())
        self.assertTrue(result["passed"], result["violations"])

    def test_a_transport_retry_in_the_background_window_is_tolerated(self):
        """A dropped attempt before the full body is a retry, not a second download."""
        result = check_background_receipt(rows(), ledger(retry_first_background_fetch=True))
        self.assertTrue(result["passed"], result["violations"])

    def test_a_refetch_after_return_fails(self):
        """Opening the app must use retained data; a later GET of a background attachment is a violation."""
        self.assertFalse(check_background_receipt(rows(), appended(ledger(), "a", "get"))["passed"])

    def test_a_fetched_disabled_type_fails(self):
        """The slot sent while its type was off must never be fetched, even much later."""
        self.assertFalse(check_background_receipt(rows(), appended(ledger(), "f", "get"))["passed"])

    def test_swapped_disabled_identities_fail(self):
        """Fetching the padded video while leaving the small image untouched is the disabled type downloading."""
        events = [dict(e, value=2 * 1024 * 1024) if e["kind"] == "upload" and e["fixture"] == "e"
                  else dict(e, value=64 * 1024) if e["kind"] == "upload" and e["fixture"] == "f" else e
                  for e in ledger()]
        self.assertFalse(check_background_receipt(rows(), events)["passed"])

    def test_return_local_reads_must_all_be_proven(self):
        """The no-fetch-on-return claim needs every attachment read back; missing, zero or wrong counts fail."""
        for value in (None, 0, 3, True):
            report = rows()
            if value is None:
                del report[0]["returned"]["local_reads"]
            else:
                report[0]["returned"]["local_reads"] = value
            self.assertFalse(check_background_receipt(report, ledger())["passed"], value)

    def test_a_network_held_fetch_before_wifi_returns_fails(self):
        """The Wi-Fi-only video must not be fetched while Wi-Fi is off."""
        events = ledger()
        restored = next(e["seq"] for e in events if e["kind"] == "marker" and e["value"] == 21)
        early = {"seq": restored - 0.5, "request": None, "fixture": "v", "kind": "get", "value": None, "at_ns": 0}
        self.assertFalse(check_background_receipt(rows(), sorted(events + [early], key=lambda e: e["seq"]))["passed"])

    def test_a_missing_background_fetch_fails(self):
        """An attachment that was not fetched while backgrounded is not background scheduling."""
        events = without(ledger(), lambda e: e["fixture"] == "d" and e["kind"] == "get")
        self.assertFalse(check_background_receipt(rows(), events)["passed"])

    def test_a_stop_that_never_finished_in_the_next_process_fails(self):
        """The stopped file must be served in full after the platform stop, not merely requested."""
        events = ledger()
        last_complete = max(e["seq"] for e in events if e["fixture"] == "g" and e["kind"] == "complete")
        result = check_background_receipt(rows(), without(events, lambda e: e["seq"] == last_complete))
        self.assertFalse(result["passed"])

    def test_missing_or_repeated_markers_fail(self):
        """Every case boundary must be present exactly once and in order."""
        self.assertFalse(check_background_receipt(rows(), without(ledger(), lambda e: e["value"] == 20))["passed"])
        self.assertFalse(check_background_receipt(rows(), appended(ledger(), "control", "marker", 10))["passed"])

    def test_any_unproven_device_fact_fails(self):
        """A false or missing device claim fails closed, as does a duplicated or missing row."""
        for section, field in (("background", "activity_stopped"), ("background", "visible_activities"),
                               ("returned", "new_http_gets"), ("disabled_type", "disabled_fetched"),
                               ("network_held", "queued_without_wifi"), ("stopped", "actual_platform_stop")):
            report = rows()
            value = report[0][section][field]
            report[0][section][field] = (not value) if isinstance(value, bool) else 1
            self.assertFalse(check_background_receipt(report, ledger())["passed"], field)
        for field in ("process_restarted", "app_state_started_before_bind", "work_waiting_before_bind"):
            report = rows()
            report[2][field] = not report[2][field]
            self.assertFalse(check_background_receipt(report, ledger())["passed"], field)
        self.assertFalse(check_background_receipt(rows() + [copy.deepcopy(MAIN)], ledger())["passed"])
        self.assertFalse(check_background_receipt(rows()[:2], ledger())["passed"])
        self.assertFalse(check_background_receipt([], ledger())["passed"])
        self.assertFalse(check_background_receipt("not a list", ledger())["passed"])

    def test_a_boolean_never_stands_in_for_a_count(self):
        """A visible-activity count reported as False is not the count zero."""
        report = rows()
        report[2]["visible_activities"] = False
        self.assertFalse(check_background_receipt(report, ledger())["passed"])


if __name__ == "__main__":
    unittest.main()
