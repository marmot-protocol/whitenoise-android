"""APK qualification must fail when any transfer, platform outcome or request-count proof is absent."""

from copy import deepcopy
import unittest

from apk_checker import (CASES, CIPHERTEXT_OVERHEAD, HELD_PREFIX_BYTES, LARGE_CASE, NO_INSTALLER_PERMISSION,
                         cases_for, check_apk, table_for)

SIZES = {"valid": 8_000_001, "generic": 8_000_001, "conflict": 8_000_001, "no-manifest": 4_222,
         "truncated": 4_000_001, LARGE_CASE: 32_440_320}
MODES = (
    ("Play", {}), ("Zapstore", {}),
    ("Play", {"cancel_retry": True}), ("Zapstore", {"cancel_retry": True}),
    ("Play", {"large": True}), ("Zapstore", {"large": True}),
    ("Zapstore", {"no_installer": True}),
    ("Zapstore", {"cancel_retry": True, "large": True, "no_installer": True}),
)


def cancellation_row():
    """The closed row the shared held-body cancellation probe emits after a bounded cancel and exact retry."""
    return {"phase": "held-body-cancellation", "success": True, "ack_elapsed_ms": 3.0,
            "socket_close_elapsed_ms": 40.0, "quiet_seconds": 30, "ordinary_terminal_joins": 10, "active_joins": 10,
            "no_work_cancel_confirmed": True, "deliberate_retry_exact_bytes": True}


def evidence(distribution, cancel_retry=False, large=False, no_installer=False):
    """Construct the declared device and ledger shape; the platform behavior itself is qualified on a device."""
    cases = cases_for(large)
    metrics = []
    for case in cases:
        metrics += [{"phase": f"apk-transfer-{case}", "success": True, "payload_bytes": SIZES[case], "elapsed_ms": 40.0,
                     "java_peak_bytes": 30 * 2**20, "native_peak_bytes": 90 * 2**20},
                    {"phase": "apk-received", "case": case, "bytes": SIZES[case], "exact": True}]
    if cancel_retry:
        metrics.append(cancellation_row())
    for (case, permission), (allowed, installer) in table_for(distribution, large, no_installer).items():
        metrics.append({"phase": "apk-dispatch", "case": case, "permission": permission, "result": allowed[0],
                        "installer_shown": installer, "installer_observed_ms": 150 if installer else 1000,
                        "dispatch_ms": 12.0, "transfer_reused": True})
    metrics.append({"phase": "apk-complete", "self_update_enabled": distribution == "Zapstore", "cases": len(cases)})
    rows = []
    for number, case in enumerate(cases, 1):
        rows += [(None, "upload", SIZES[case] + CIPHERTEXT_OVERHEAD, f"upload-{number}"),
                 (len(rows) + 1, "upload_complete", 0, f"upload-{number}")]
    if large:
        rows += [(None, "payload_fetch", SIZES[LARGE_CASE], "large-apk"), (len(rows) + 1, "payload_complete", 0, "large-apk")]
    for number, case in enumerate(cases, 1):
        if cancel_retry and case == "valid":
            rows.append((None, "hold_acquisition", 0, "control"))
            start = len(rows) + 1
            rows += [(None, "get", 0, f"upload-{number}"), (start, "status", 200, f"upload-{number}"),
                     (start, "body_bytes", HELD_PREFIX_BYTES, f"upload-{number}"),
                     (start, "held", HELD_PREFIX_BYTES, f"upload-{number}"),
                     (None, "cancel_marker", 0, "control"), (start, "disconnect", 0, f"upload-{number}"),
                     (None, "release_acquisition", 0, "control")]
        start = len(rows) + 1
        rows += [(None, "get", 0, f"upload-{number}"), (start, "status", 200, f"upload-{number}"),
                 (start, "body_bytes", SIZES[case] + CIPHERTEXT_OVERHEAD, f"upload-{number}"),
                 (start, "complete", 0, f"upload-{number}")]
    events = [{"seq": seq, "request": request, "kind": kind, "value": value, "fixture": fixture}
              for seq, (request, kind, value, fixture) in enumerate(rows, 1)]
    return metrics, events, distribution


class ApkEvidenceTest(unittest.TestCase):
    """A closure claim needs the right platform outcome per distribution and exactly one transfer per case."""

    def test_both_distributions_pass_without_claiming_an_installation(self):
        """Neither distribution's evidence, in any selected mode, claims an installation or a platform no-installer state."""
        for distribution, mode in MODES:
            result = check_apk(*evidence(distribution, **mode), **mode)
            self.assertTrue(result["passed"], (distribution, mode, result))
            self.assertFalse(result["installation_confirmed"])
            self.assertFalse(result["performance_qualified"])
            self.assertFalse(result["no_installer_platform_qualified"])
            self.assertEqual(mode.get("no_installer", False), result["no_installer_simulated"])

    def test_a_distribution_cannot_borrow_the_other_distributions_outcomes(self):
        """Play evidence labelled Zapstore, and the reverse, must fail rather than pass on shape."""
        metrics, events, _ = evidence("Play")
        self.assertFalse(check_apk(metrics, events, "Zapstore")["passed"])
        metrics, events, _ = evidence("Zapstore")
        self.assertFalse(check_apk(metrics, events, "Play")["passed"])

    def test_evidence_from_an_unselected_gap_fails_and_a_selected_gap_needs_its_evidence(self):
        """Interruption, payload or no-installer rows cannot pass without their selection, nor a selection without them."""
        for distribution in ("Play", "Zapstore"):
            self.assertFalse(check_apk(*evidence(distribution, cancel_retry=True))["passed"], distribution)
            self.assertFalse(check_apk(*evidence(distribution, large=True))["passed"], distribution)
            self.assertFalse(check_apk(*evidence(distribution), cancel_retry=True)["passed"], distribution)
            self.assertFalse(check_apk(*evidence(distribution), large=True)["passed"], distribution)
        self.assertFalse(check_apk(*evidence("Zapstore", no_installer=True))["passed"])
        self.assertFalse(check_apk(*evidence("Zapstore"), no_installer=True)["passed"])

    def test_the_simulated_no_installer_branch_exists_only_on_a_self_update_build(self):
        """A Play build answers InstallUnsupported before any launch, so its no-installer selection is invalid input."""
        metrics, events, _ = evidence("Play")
        result = check_apk(metrics, events, "Play", no_installer=True)
        self.assertEqual((False, ["invalid input"]), (result["passed"], result["violations"]))
        self.assertNotIn(("valid", NO_INSTALLER_PERMISSION), table_for("Play", no_installer=True))

    def test_every_missing_metric_or_ledger_event_fails(self):
        """Removing any single metric or request event is material evidence loss in every selected mode."""
        for distribution, mode in MODES:
            metrics, events, _ = evidence(distribution, **mode)
            for index in range(len(metrics)):
                self.assertFalse(check_apk(metrics[:index] + metrics[index + 1:], events, distribution, **mode)["passed"],
                                 (distribution, mode, index))
            for index in range(len(events)):
                self.assertFalse(check_apk(metrics, events[:index] + events[index + 1:], distribution, **mode)["passed"],
                                 (distribution, mode, index))

    def test_every_wrong_platform_result_fails(self):
        """Each dispatch row must carry its exact expected result and installer visibility."""
        for distribution, mode in MODES:
            metrics, events, _ = evidence(distribution, **mode)
            for index, row in enumerate(metrics):
                if row["phase"] != "apk-dispatch":
                    continue
                for field, value in (("result", "Error"), ("installer_shown", not row["installer_shown"]),
                                     ("transfer_reused", False), ("dispatch_ms", float("nan"))):
                    altered = deepcopy(metrics)
                    altered[index][field] = value
                    self.assertFalse(check_apk(altered, events, distribution, **mode)["passed"],
                                     (distribution, mode, index, field))

    def test_an_installer_behind_any_status_fails_qualification(self):
        """A launch that returns InvalidPackage, InstallPermissionRequired or InstallUnsupported is still a launch."""
        for distribution in ("Play", "Zapstore"):
            metrics, events, _ = evidence(distribution)
            for index, row in enumerate(metrics):
                if row.get("phase") == "apk-dispatch" and row["installer_shown"] is False:
                    altered = deepcopy(metrics)
                    altered[index]["installer_shown"] = True
                    result = check_apk(altered, events, distribution)
                    self.assertFalse(result["passed"], (distribution, row["case"], row["result"]))
                    self.assertTrue(any("wrong platform outcome" in v for v in result["violations"]))

    def test_every_dispatch_must_have_been_watched_for_an_installer(self):
        """A missing, short or non-numeric observation means an absent installer was never actually looked for."""
        for distribution in ("Play", "Zapstore"):
            metrics, events, _ = evidence(distribution)
            for index, row in enumerate(metrics):
                if row.get("phase") != "apk-dispatch":
                    continue
                for observed in (None, "1000", True, -1, float("nan")):
                    altered = deepcopy(metrics)
                    if observed is None:
                        del altered[index]["installer_observed_ms"]
                    else:
                        altered[index]["installer_observed_ms"] = observed
                    self.assertFalse(check_apk(altered, events, distribution)["passed"], (row["case"], observed))
                if row["installer_shown"] is False:
                    altered = deepcopy(metrics)
                    altered[index]["installer_observed_ms"] = 100
                    self.assertFalse(check_apk(altered, events, distribution)["passed"], row["case"])

    def test_a_retry_that_downloads_again_fails(self):
        """A denied or blocked dispatch must reuse the completed download, never fetch again, in every mode."""
        for distribution, mode in MODES:
            metrics, events, _ = evidence(distribution, **mode)
            extra = events + [{"seq": 999, "request": None, "kind": "get", "value": 0, "fixture": "upload-1"}]
            self.assertFalse(check_apk(metrics, extra, distribution, **mode)["passed"], (distribution, mode))

    def test_cancellation_evidence_must_be_bounded_exact_and_singular(self):
        """The cancel row needs a bounded socket close, exact retry bytes, a confirmed cancel and one held attempt."""
        metrics, events, distribution = evidence("Zapstore", cancel_retry=True)
        row = next(i for i, m in enumerate(metrics) if m["phase"] == "held-body-cancellation")
        for field, value in (("socket_close_elapsed_ms", 5_001), ("socket_close_elapsed_ms", float("nan")),
                             ("ack_elapsed_ms", -1), ("deliberate_retry_exact_bytes", False),
                             ("no_work_cancel_confirmed", False), ("success", False)):
            altered = deepcopy(metrics)
            altered[row][field] = value
            self.assertFalse(check_apk(altered, events, distribution, cancel_retry=True)["passed"], (field, value))
        duplicated = metrics[:row + 1] + [cancellation_row()] + metrics[row + 1:]
        self.assertFalse(check_apk(duplicated, events, distribution, cancel_retry=True)["passed"])
        held = next(e for e in events if e["kind"] == "held")
        for field, value in (("value", HELD_PREFIX_BYTES + 1), ("request", held["request"] + 1)):
            altered = deepcopy(events)
            next(e for e in altered if e["kind"] == "held")[field] = value
            self.assertFalse(check_apk(metrics, altered, distribution, cancel_retry=True)["passed"], field)

    def test_the_large_case_is_last_and_its_payload_fetch_is_counted_once(self):
        """The 30 to 31 MiB case extends the wire order at the end and a second host fetch is a violation."""
        self.assertEqual(CASES + (LARGE_CASE,), cases_for(True))
        self.assertEqual(CASES, cases_for(False))
        metrics, events, distribution = evidence("Play", large=True)
        extra = events + [{"seq": 999, "request": None, "kind": "payload_fetch", "value": 1, "fixture": "large-apk"},
                          {"seq": 1000, "request": 999, "kind": "payload_complete", "value": 0, "fixture": "large-apk"}]
        self.assertFalse(check_apk(metrics, extra, distribution, large=True)["passed"])

    def test_inexact_transfer_or_invalid_measurement_fails(self):
        """Bytes must be exact, and latency and peaks must be finite measurements."""
        metrics, events, distribution = evidence("Zapstore")
        for field, value in (("exact", False), ("bytes", 1)):
            altered = deepcopy(metrics)
            next(m for m in altered if m["phase"] == "apk-received")[field] = value
            self.assertFalse(check_apk(altered, events, distribution)["passed"], field)
        for field in ("elapsed_ms", "java_peak_bytes", "native_peak_bytes"):
            for value in (None, True, -1, float("nan"), float("inf")):
                altered = deepcopy(metrics)
                next(m for m in altered if m["phase"].startswith("apk-transfer"))[field] = value
                self.assertFalse(check_apk(altered, events, distribution)["passed"], (field, value))

    def test_malformed_inputs_fail(self):
        """Unknown distributions and non-dict metrics never pass."""
        metrics, events, _ = evidence("Play")
        self.assertFalse(check_apk(metrics, events, "Other")["passed"])
        self.assertFalse(check_apk(["x"], events, "Play")["passed"])
        self.assertFalse(check_apk(None, events, "Play")["passed"])


if __name__ == "__main__":
    unittest.main()
