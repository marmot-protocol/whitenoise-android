"""The tile transfer checker must fail when any step a reader needs to see, or any request the server saw, is absent."""

from copy import deepcopy
import unittest

from tile_transfer_checker import check_tile_transfer


def rows():
    """One complete set of scenario rows, as the device test reports them."""
    return [
        {"phase": "tile-transfer", "scenario": "known-cancel-again", "media": "image", "idle_download_seen": True,
         "bytes_text": "2.0 MB of 3.0 MB", "cancel_offered": True, "cancelled_seen": True, "cancel_ack_ms": 420,
         "quiet_after_cancel": True, "restarted": True, "completed": True},
        {"phase": "tile-transfer", "scenario": "failed-then-retried", "media": "video", "idle_download_seen": True,
         "failed_seen": True, "completed": True},
        {"phase": "tile-transfer", "scenario": "unknown-length", "media": "image", "idle_download_seen": True,
         "bytes_text": "2.0 MB received", "no_total": True, "completed": True},
    ]


def ledger():
    """Three genuine uploads, a 404, a disconnect and three finished acquisitions."""
    kinds = [("upload", 1), ("upload_complete", 0)] * 3 + [("get", 0), ("status", 404), ("complete", 0)]
    kinds += [("get", 0), ("status", 200), ("disconnect", 0), ("get", 0), ("status", 206), ("complete", 0),
              ("get", 0), ("status", 200), ("complete", 0)]
    return [{"seq": index, "request": None, "kind": kind, "value": value} for index, (kind, value) in enumerate(kinds, 1)]


class TileTransferCheckerTest(unittest.TestCase):
    """A closure claim needs every scenario observed on a real tile and the server to agree."""

    def test_complete_evidence_passes_and_never_claims_performance(self):
        """All three scenarios and the ledger pass, and no performance qualification is made."""
        result = check_tile_transfer(rows(), ledger())
        self.assertTrue(result["passed"], result)
        self.assertFalse(result["performance_qualified"])

    def test_every_required_fact_is_required(self):
        """Flipping any single required fact in any scenario fails the check."""
        for index, row in enumerate(rows()):
            for field, value in row.items():
                if value is True:
                    altered = rows()
                    altered[index][field] = False
                    with self.subTest(scenario=row["scenario"], field=field):
                        self.assertFalse(check_tile_transfer(altered, ledger())["passed"])

    def test_a_missing_duplicate_or_extra_row_fails(self):
        """Each scenario appears exactly once and no other scenario is accepted."""
        for index in range(3):
            altered = rows()
            del altered[index]
            self.assertFalse(check_tile_transfer(altered, ledger())["passed"])
        self.assertFalse(check_tile_transfer(rows() + [rows()[0]], ledger())["passed"])
        self.assertFalse(check_tile_transfer(rows() + [dict(rows()[0], scenario="other")], ledger())["passed"])

    def test_bytes_must_be_real_and_below_a_known_total(self):
        """A reader-visible byte text must show progress below the total, and an unknown length must show no total."""
        for text in ("0.0 MB of 3.0 MB", "3.0 MB of 3.0 MB", "4.0 MB of 3.0 MB", "bytes", "", None):
            altered = rows()
            altered[0]["bytes_text"] = text
            with self.subTest(text=text):
                self.assertFalse(check_tile_transfer(altered, ledger())["passed"])
        for text in ("2.0 MB of 3.0 MB", "received", None):
            altered = rows()
            altered[2]["bytes_text"] = text
            with self.subTest(text=text):
                self.assertFalse(check_tile_transfer(altered, ledger())["passed"])

    def test_the_wrong_media_kind_or_a_late_cancel_fails(self):
        """The scenarios are bound to their tile kind, and a cancel acknowledgement must be timely."""
        altered = rows()
        altered[0]["media"] = "video"
        self.assertFalse(check_tile_transfer(altered, ledger())["passed"])
        for value in (None, -1, True, 31_000, float("nan")):
            altered = rows()
            altered[0]["cancel_ack_ms"] = value
            with self.subTest(value=value):
                self.assertFalse(check_tile_transfer(altered, ledger())["passed"])

    def test_the_ledger_must_confirm_uploads_a_404_a_disconnect_and_finished_bodies(self):
        """Removing any server-side proof fails the check."""
        for kind in ("upload", "upload_complete", "disconnect", "complete"):
            events = [e for e in ledger() if e["kind"] != kind]
            with self.subTest(kind=kind):
                self.assertFalse(check_tile_transfer(rows(), events)["passed"])
        no_404 = deepcopy(ledger())
        for event in no_404:
            if event["kind"] == "status":
                event["value"] = 200
        self.assertFalse(check_tile_transfer(rows(), no_404)["passed"])

    def test_invalid_metric_collections_fail_closed(self):
        """A malformed collection is never a pass."""
        for bad in (None, "rows", [1], [{"phase": "tile-transfer"}]):
            with self.subTest(bad=bad):
                self.assertFalse(check_tile_transfer(bad, ledger())["passed"])


if __name__ == "__main__":
    unittest.main()
