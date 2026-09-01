#!/usr/bin/env python3

import unittest
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).parent))
import stale_result_recurrence as srr


def record(
    number=100,
    created_at="2026-06-15T12:00:00Z",
    issue_type="Bug",
    state_reason="completed",
    title="Crash on open",
    body="Steps to reproduce.",
    is_pull_request=False,
):
    return {
        "number": number,
        "created_at": created_at,
        "state": "closed",
        "state_reason": state_reason,
        "type": issue_type,
        "labels": [],
        "is_pull_request": is_pull_request,
        "title": title,
        "body": body,
    }


class WindowInclusionTest(unittest.TestCase):
    def test_first_and_last_second_of_window_are_included(self):
        for created in ("2026-05-26T00:00:00Z", "2026-08-20T23:59:59Z"):
            result = srr.classify_record(record(created_at=created), None)
            self.assertIsNone(result["exclusion"], created)

    def test_issues_outside_window_are_excluded(self):
        for created in ("2026-05-25T23:59:59Z", "2026-08-21T00:00:00Z"):
            result = srr.classify_record(record(created_at=created), None)
            self.assertEqual(result["exclusion"], "excluded_out_of_window", created)

    def test_partial_and_full_months_are_labeled(self):
        summary = srr.aggregate(
            [
                srr.classify_record(record(number=1, created_at=c), None)
                for c in (
                    "2026-05-27T00:00:00Z",
                    "2026-06-10T00:00:00Z",
                    "2026-08-05T00:00:00Z",
                )
            ]
        )
        self.assertIn("partial", summary["months"]["2026-05"]["note"])
        self.assertIn("partial", summary["months"]["2026-08"]["note"])
        self.assertEqual(summary["months"]["2026-06"]["note"], "full month")


class ExclusionTest(unittest.TestCase):
    def test_pull_requests_are_excluded(self):
        result = srr.classify_record(record(is_pull_request=True), None)
        self.assertEqual(result["exclusion"], "excluded_pull_request")

    def test_non_defect_types_are_excluded(self):
        for issue_type in ("Task", "Feature", "Tracking", None):
            result = srr.classify_record(record(issue_type=issue_type), None)
            self.assertEqual(result["exclusion"], "excluded_non_defect")

    def test_bug_label_marks_a_defect_even_without_the_typed_bug_type(self):
        item = record(issue_type=None)
        item["labels"] = ["bug"]
        self.assertIsNone(srr.classify_record(item, None)["exclusion"])

    def test_duplicates_are_excluded_by_state_reason_and_body(self):
        by_state = srr.classify_record(record(state_reason="duplicate"), None)
        self.assertEqual(by_state["exclusion"], "excluded_duplicate")
        by_body = srr.classify_record(record(body="Duplicate of #90."), None)
        self.assertEqual(by_body["exclusion"], "excluded_duplicate")

    def test_duplicate_mention_mid_body_is_not_an_exclusion(self):
        result = srr.classify_record(
            record(body="Not a duplicate of #90; different path."), None
        )
        self.assertIsNone(result["exclusion"])

    def test_adjudication_can_exclude_audit_artifacts(self):
        result = srr.classify_record(
            record(),
            {"exclusion": "excluded_audit_artifact", "reason": "audit_created"},
        )
        self.assertEqual(result["exclusion"], "excluded_audit_artifact")
        self.assertTrue(result["adjudicated"])

    def test_adjudication_can_restore_a_duplicate_with_independent_content(self):
        result = srr.classify_record(
            record(state_reason="duplicate"),
            {"exclusion": None, "reason": "independent_defect_report"},
        )
        self.assertIsNone(result["exclusion"])


class StaleResultEvidenceTest(unittest.TestCase):
    def classify(self, body):
        return srr.classify_record(record(body=body), None)

    def test_strong_stale_result_language_classifies_as_stale(self):
        cases = {
            "stale_noun": "A stale callback repaints the timeline.",
            "late_completion": "The late completion still updates the list.",
            "obsolete_generation": "An obsolete generation result is applied.",
            "old_owner_result": "The previous account's messages are shown.",
            "wrong_owner_apply": "Results from a previous conversation render.",
            "applied_after_switch": "The reply is applied after the account switch.",
            "completes_after_leaving": "The load completes after leaving the screen.",
            "old_overwrites_new": "An older response overwrites the fresh draft.",
        }
        for rule, body in cases.items():
            result = self.classify(body)
            self.assertTrue(result["stale_result"], rule)
            self.assertIn(rule, result["rules"], rule)

    def test_broad_language_alone_is_not_stale(self):
        result = self.classify("The old crash is happening again on my device.")
        self.assertFalse(result["stale_result"])
        self.assertEqual(result["rules"], ["broad_language_only"])

    def test_plain_defect_is_not_stale(self):
        result = self.classify("Text clips under the send button.")
        self.assertFalse(result["stale_result"])
        self.assertEqual(result["rules"], [])


class AmbiguityTest(unittest.TestCase):
    def test_medium_signals_require_adjudication(self):
        body = "There is a race when the account switches during load."
        with self.assertRaises(srr.AmbiguityError) as caught:
            srr.classify_snapshot([record(body=body)], {})
        unresolved = caught.exception.unresolved
        self.assertEqual(unresolved[0]["number"], 100)
        self.assertIn("stale_result", unresolved[0]["ambiguous"])

    def test_adjudication_resolves_ambiguity_in_both_directions(self):
        body = "There is a race when the account switches during load."
        for verdict in (True, False):
            results = srr.classify_snapshot(
                [record(body=body)],
                {100: {"stale_result": verdict, "reason": "manual_review"}},
            )
            self.assertEqual(results[0]["stale_result"], verdict)
            self.assertTrue(results[0]["adjudicated"])

    def test_orphaned_adjudications_are_rejected(self):
        with self.assertRaises(ValueError):
            srr.classify_snapshot(
                [record()], {999: {"stale_result": True, "reason": "manual_review"}}
            )

    def test_duplicate_snapshot_numbers_are_rejected(self):
        with self.assertRaises(ValueError):
            srr.classify_snapshot([record(), record()], {})


class NamedRecurrenceTest(unittest.TestCase):
    def classify(self, body):
        return srr.classify_record(record(body=body), None)

    def test_named_prior_fix_near_reference_counts(self):
        result = self.classify("This is a regression of #1234 after the fix.")
        self.assertTrue(result["named_recurrence"])

    def test_reoccurrence_spelling_counts(self):
        result = self.classify("Still broken (reoccurrence of #811).")
        self.assertTrue(result["named_recurrence"])

    def test_hex_color_is_not_an_issue_reference(self):
        result = self.classify("The regression makes the surface #000000 black.")
        self.assertFalse(result["named_recurrence"])

    def test_recurrence_verb_without_reference_does_not_count(self):
        result = self.classify("The bug resurfaced after the last release.")
        self.assertFalse(result["named_recurrence"])

    def test_reference_without_recurrence_language_does_not_count(self):
        result = self.classify("Related reading: #1234 describes the flow.")
        self.assertFalse(result["named_recurrence"])

    def test_distant_reference_and_verb_are_ambiguous(self):
        body = "See #1234 for the feature context." + (" filler" * 60) + (
            " The crash regressed on the latest build."
        )
        with self.assertRaises(srr.AmbiguityError) as caught:
            srr.classify_snapshot([record(body=body)], {})
        self.assertIn("named_recurrence", caught.exception.unresolved[0]["ambiguous"])


class AdjudicationValidationTest(unittest.TestCase):
    def test_reason_is_required(self):
        with self.assertRaises(ValueError):
            srr.validate_adjudications({"100": {"stale_result": True}})

    def test_unknown_fields_are_rejected(self):
        with self.assertRaises(ValueError):
            srr.validate_adjudications({"100": {"reason": "x", "note": "free text"}})

    def test_unknown_exclusion_codes_are_rejected(self):
        with self.assertRaises(ValueError):
            srr.validate_adjudications(
                {"100": {"reason": "x", "exclusion": "excluded_because"}}
            )


class AggregationTest(unittest.TestCase):
    def test_counts_and_composite_named_recurrence(self):
        classifications = [
            {"number": 1, "created": "2026-06-01", "month": "2026-06",
             "exclusion": None, "stale_result": True, "named_recurrence": True,
             "rules": [], "adjudicated": False},
            {"number": 2, "created": "2026-06-02", "month": "2026-06",
             "exclusion": None, "stale_result": True, "named_recurrence": False,
             "rules": [], "adjudicated": False},
            {"number": 3, "created": "2026-06-03", "month": "2026-06",
             "exclusion": None, "stale_result": False, "named_recurrence": True,
             "rules": [], "adjudicated": False},
            {"number": 4, "created": "2026-06-04", "month": "2026-06",
             "exclusion": "excluded_duplicate", "stale_result": False,
             "named_recurrence": False, "rules": [], "adjudicated": False},
        ]
        totals = srr.aggregate(classifications)["totals"]
        self.assertEqual(totals["created"], 4)
        self.assertEqual(totals["eligible"], 3)
        self.assertEqual(totals["stale_result"], 2)
        self.assertEqual(totals["stale_named_recurrence"], 1)
        self.assertEqual(totals["exclusions"]["excluded_duplicate"], 1)

    def test_report_renders_partial_month_warning(self):
        text = srr.render_report(srr.aggregate([
            {"number": 1, "created": "2026-05-27", "month": "2026-05",
             "exclusion": None, "stale_result": False, "named_recurrence": False,
             "rules": [], "adjudicated": False},
        ]))
        self.assertIn("partial: 2026-05-26 through 2026-05-31", text)
        self.assertIn("must not be compared", text)


class FetchTest(unittest.TestCase):
    def search_item(self, number):
        return {
            "number": number,
            "created_at": "2026-06-15T12:00:00Z",
            "state": "open",
            "state_reason": None,
            "type": {"name": "Bug"},
            "labels": [{"name": "bug"}],
            "title": "t",
            "body": "b",
        }

    def test_pagination_walks_every_page(self):
        pages = {
            1: {"total_count": 150, "items": [self.search_item(n) for n in range(100)]},
            2: {"total_count": 150,
                "items": [self.search_item(n) for n in range(100, 150)]},
        }

        def fake(_api, path):
            page = int(path.split("&page=")[1].split("&")[0])
            return pages[page]

        items = srr.fetch_window(fake, srr.WINDOW_START, srr.WINDOW_END)
        self.assertEqual(len(items), 150)

    def test_truncated_pagination_fails_loudly(self):
        def fake(_api, path):
            page = int(path.split("&page=")[1].split("&")[0])
            if page == 1:
                return {"total_count": 150,
                        "items": [self.search_item(n) for n in range(100)]}
            return {"total_count": 150, "items": []}

        with self.assertRaises(RuntimeError):
            srr.fetch_window(fake, srr.WINDOW_START, srr.WINDOW_END)

    def test_windows_over_the_result_cap_are_bisected(self):
        calls = []

        def fake(_api, path):
            query = path.split("q=")[1].split("&")[0]
            window = query.split("created:")[1]
            calls.append(window)
            start, end = window.split("..")
            full = (
                srr.parse_timestamp(start) == srr.WINDOW_START
                and srr.parse_timestamp(end) == srr.WINDOW_END
            )
            if full:
                return {"total_count": 1500, "items": []}
            return {"total_count": 1, "items": [self.search_item(len(calls))]}

        items = srr.fetch_window(fake, srr.WINDOW_START, srr.WINDOW_END)
        self.assertGreaterEqual(len(set(calls)), 3)
        self.assertEqual(len(items), 2)

    def test_snapshot_record_flags_pull_requests(self):
        item = self.search_item(7)
        item["pull_request"] = {"url": "x"}
        self.assertTrue(srr.snapshot_record(item)["is_pull_request"])
        self.assertFalse(srr.snapshot_record(self.search_item(8))["is_pull_request"])


if __name__ == "__main__":
    unittest.main()
