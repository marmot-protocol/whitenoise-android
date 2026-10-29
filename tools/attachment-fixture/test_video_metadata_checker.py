"""Checker contracts: every route fact, both histories, the stated boundary and the fixture ledger must agree."""

import copy
import unittest

from video_metadata_checker import (
    ACCEPTED, BOUNDARY, CIPHERTEXT_OVERHEAD, IN_MEMORY_LIMIT, KEPT, MIB, REFUSED, check_video_metadata,
)

SIZES = {
    "visual-content-uri-in-memory": 5_120,
    "visual-content-uri-file-backed": 40 * MIB,
    "files-content-uri-octet-in-memory": 5_301,
    "files-content-uri-octet-file-backed": 36 * MIB,
    "share-file-importer-in-memory": 6_200,
    "prepared-draft-injection-visual": 4_900,
    "prepared-draft-injection-files": 5_700,
}
REFUSED_SIZES = {
    "refused-visual-content-uri-avi": 512,
    "refused-files-content-uri-avi": 512,
    "refused-files-content-uri-trailer-in-memory": 5_302,
    "refused-files-content-uri-trailer-file-backed": 34 * MIB + 1,
}


def digest(seed):
    """A well-formed SHA-256 hex digest that differs per [seed]."""
    return f"{seed:064x}"


def accepted_row(route, index):
    """The facts one accepted route reports in a fully qualified run."""
    entry, _, location = ACCEPTED[route]
    scrubbed = digest(1000 + index)
    row = {"phase": "video-metadata-route", "route": route, "entry": entry, "reported_mime": "video/mp4",
           "input_bytes": SIZES[route], "received_bytes": SIZES[route], "input_sha256": digest(index),
           "expected_sha256": scrubbed, "published_sha256": scrubbed, "received_sha256": scrubbed,
           "private_strings": 1 if location else 6, "input_private_strings": 1 if location else 6,
           "received_private_strings": 0, "location_positive": location, "input_location": location,
           "received_location": False, "sample_count": 25, "staged_left_after_send": 0,
           "share_imported_private_copy": entry == "share-file-importer"}
    row.update({fact: True for fact in KEPT})
    return row


def refused_row(route, index):
    """The facts one refused route reports in a fully qualified run."""
    entry, notice = REFUSED[route]
    return {"phase": "video-metadata-refusal", "route": route, "entry": entry, "reported_mime": "video/x-msvideo",
            "input_bytes": REFUSED_SIZES[route], "input_sha256": digest(500 + index), "rejected": True,
            "notice": notice, "notice_matches": True, "staged_left": 0, "temp_left": 0, "published": False}


def passing_metrics():
    """Rows a fully qualified run reports, in the order the device sends them."""
    rows = [{"phase": "video-metadata-boundary", **{key: False for key in BOUNDARY}}]
    rows += [accepted_row(route, index) for index, route in enumerate(ACCEPTED)]
    rows += [refused_row(route, index) for index, route in enumerate(REFUSED)]
    rows.append({"phase": "video-metadata-history", "accepted_published": len(ACCEPTED),
                 "accepted_received": len(ACCEPTED), "refused_published": 0, "refused_received": 0,
                 "unexpected_published": 0})
    return rows


def ledger(sizes):
    """One complete upload per plaintext size, as the fixture server records it."""
    events = []
    for size in sizes:
        seq = len(events) + 1
        events.append({"seq": seq, "request": None, "kind": "upload", "value": size + CIPHERTEXT_OVERHEAD})
        events.append({"seq": seq + 1, "request": seq, "kind": "upload_complete", "value": 0})
    return events


def passing_ledger():
    """The ledger of a fully qualified run."""
    return ledger(SIZES.values())


def route(metrics, name):
    """The row [name] reported."""
    return next(row for row in metrics if row.get("route") == name)


class VideoMetadataCheckerTest(unittest.TestCase):
    """Every fact the device and fixture report is required, and any contrary or missing one fails the run."""

    def test_a_fully_qualified_run_passes(self):
        """The complete report and ledger pass with no violation."""
        result = check_video_metadata(passing_metrics(), passing_ledger())
        self.assertEqual([], result["violations"])
        self.assertTrue(result["passed"])

    def test_every_large_route_must_exceed_the_in_memory_limit_and_every_small_one_fit_it(self):
        """Only a route above the album budget proves the staged-file path, so the sizes must select each path."""
        self.assertTrue(all((SIZES[name] > IN_MEMORY_LIMIT) == spec[1] for name, spec in ACCEPTED.items()))
        metrics = passing_metrics()
        route(metrics, "visual-content-uri-file-backed").update(input_bytes=MIB, received_bytes=MIB)
        sizes = dict(SIZES, **{"visual-content-uri-file-backed": MIB})
        self.assertIn("visual-content-uri-file-backed: size does not select the file-backed path",
                      check_video_metadata(metrics, ledger(sizes.values()))["violations"])

    def test_received_bytes_must_equal_the_scrubbed_source_and_differ_from_the_original(self):
        """A received copy that is the original, or anything but the scrubbed source, fails."""
        for change, message in (({"received_sha256": digest(1)}, "differs from the scrubbed source"),
                                ({"published_sha256": digest(2)}, "differs from the scrubbed source"),
                                ({"input_sha256": digest(1000)}, "is the unscrubbed source"),
                                ({"received_sha256": "not-a-digest"}, "missing digest")):
            with self.subTest(change=change):
                metrics = passing_metrics()
                route(metrics, "visual-content-uri-in-memory").update(change)
                violations = check_video_metadata(metrics, passing_ledger())["violations"]
                self.assertTrue(any(message in violation for violation in violations), violations)

    def test_private_strings_location_and_kept_media_are_each_required(self):
        """A remaining private string or location, a source without them, or any lost media fact fails."""
        changes = [{"received_private_strings": 1}, {"input_private_strings": 0}, {"received_location": True},
                   {"input_location": False}, {"location_positive": False}, {"sample_count": 0},
                   {"staged_left_after_send": 1}, {"share_imported_private_copy": True}]
        changes += [{fact: False} for fact in KEPT]
        for change in changes:
            with self.subTest(change=change):
                metrics = passing_metrics()
                route(metrics, "visual-content-uri-in-memory").update(change)
                self.assertFalse(check_video_metadata(metrics, passing_ledger())["passed"])

    def test_the_share_route_must_report_its_private_copy(self):
        """The share route proves it went through the importer's private copy."""
        metrics = passing_metrics()
        route(metrics, "share-file-importer-in-memory")["share_imported_private_copy"] = False
        self.assertIn("share-file-importer-in-memory: share import receipt does not match the entry",
                      check_video_metadata(metrics, passing_ledger())["violations"])

    def test_a_refused_route_must_show_its_notice_publish_nothing_and_leave_nothing(self):
        """Acceptance, a different notice, a published item or a leftover copy each fail the run."""
        for change in ({"rejected": False}, {"notice": "toast_couldnt_process_video"}, {"notice_matches": False},
                       {"staged_left": 1}, {"temp_left": 1}, {"published": True}, {"entry": "share-file-importer"}):
            with self.subTest(change=change):
                metrics = passing_metrics()
                route(metrics, "refused-files-content-uri-trailer-file-backed").update(change)
                self.assertFalse(check_video_metadata(metrics, passing_ledger())["passed"])

    def test_missing_repeated_and_unknown_routes_fail(self):
        """Every route reports once, and a route this checker does not know is never silently ignored."""
        metrics = [row for row in passing_metrics() if row.get("route") != "prepared-draft-injection-files"]
        self.assertIn("prepared-draft-injection-files: missing route row",
                      check_video_metadata(metrics, passing_ledger())["violations"])
        metrics = passing_metrics() + [copy.deepcopy(route(passing_metrics(), "refused-visual-content-uri-avi"))]
        self.assertIn("refused-visual-content-uri-avi: reported more than once",
                      check_video_metadata(metrics, passing_ledger())["violations"])
        metrics = passing_metrics() + [dict(accepted_row("visual-content-uri-in-memory", 0), route="photo-picker-ui")]
        self.assertFalse(check_video_metadata(metrics, passing_ledger())["passed"])

    def test_both_histories_must_hold_every_accepted_route_and_no_refused_one(self):
        """A refused route in either history, or an accepted one missing, fails."""
        for key, value in (("refused_published", 1), ("refused_received", 1), ("accepted_received", 6),
                           ("unexpected_published", 1)):
            with self.subTest(key=key):
                metrics = passing_metrics()
                next(row for row in metrics if row["phase"] == "video-metadata-history")[key] = value
                self.assertFalse(check_video_metadata(metrics, passing_ledger())["passed"])

    def test_the_boundary_must_state_every_uncovered_entry_point(self):
        """A report that claims the system picker, the share sheet or a restored draft was driven fails."""
        for key in BOUNDARY:
            with self.subTest(key=key):
                metrics = passing_metrics()
                metrics[0][key] = True
                self.assertIn("boundary: the uncovered entry points were not stated",
                              check_video_metadata(metrics, passing_ledger())["violations"])

    def test_the_ledger_must_hold_one_complete_upload_per_accepted_route_only(self):
        """An extra, missing or incomplete upload, or one of a refused route's size, fails."""
        refused = REFUSED_SIZES["refused-files-content-uri-trailer-in-memory"]
        for events in (ledger(list(SIZES.values())[1:]), ledger(list(SIZES.values()) + [refused]),
                       [event for event in passing_ledger() if event["seq"] != 2], []):
            with self.subTest(count=len(events)):
                self.assertFalse(check_video_metadata(passing_metrics(), events)["passed"])
        violations = check_video_metadata(passing_metrics(), ledger(list(SIZES.values()) + [refused]))["violations"]
        self.assertIn("ledger: a refused route's ciphertext was uploaded", violations)

    def test_garbage_input_fails_closed(self):
        """Missing metrics or ledger never pass."""
        for metrics, events in ((None, None), ([], []), ("rows", {"kind": "upload"})):
            with self.subTest(metrics=metrics):
                self.assertFalse(check_video_metadata(metrics, events)["passed"])


if __name__ == "__main__":
    unittest.main()
