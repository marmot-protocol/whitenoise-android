"""APK qualification must fail when any transfer, platform outcome or request-count proof is absent."""

from copy import deepcopy
import unittest

from apk_checker import CASES, CIPHERTEXT_OVERHEAD, PLAY, ZAPSTORE, check_apk

SIZES = {"valid": 8_000_001, "generic": 8_000_001, "conflict": 8_000_001, "no-manifest": 4_222, "truncated": 4_000_001}


def evidence(distribution):
    """Construct the declared device and ledger shape; the platform behavior itself is qualified on a device."""
    metrics = []
    for case in CASES:
        metrics += [{"phase": f"apk-transfer-{case}", "success": True, "payload_bytes": SIZES[case], "elapsed_ms": 40.0,
                     "java_peak_bytes": 30 * 2**20, "native_peak_bytes": 90 * 2**20},
                    {"phase": "apk-received", "case": case, "bytes": SIZES[case], "exact": True}]
    table = ZAPSTORE if distribution == "Zapstore" else PLAY
    for (case, permission), (allowed, installer) in table.items():
        metrics.append({"phase": "apk-dispatch", "case": case, "permission": permission, "result": allowed[0],
                        "installer_shown": installer, "dispatch_ms": 12.0, "transfer_reused": True})
    metrics.append({"phase": "apk-complete", "self_update_enabled": distribution == "Zapstore", "cases": len(CASES)})
    rows = []
    for number, case in enumerate(CASES, 1):
        rows += [(None, "upload", SIZES[case] + CIPHERTEXT_OVERHEAD, f"upload-{number}"),
                 (len(rows) + 1, "upload_complete", 0, f"upload-{number}")]
    for number, case in enumerate(CASES, 1):
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
        """Neither distribution's evidence ever claims that a package was installed."""
        for distribution in ("Play", "Zapstore"):
            result = check_apk(*evidence(distribution))
            self.assertTrue(result["passed"], (distribution, result))
            self.assertFalse(result["installation_confirmed"])
            self.assertFalse(result["performance_qualified"])

    def test_a_distribution_cannot_borrow_the_other_distributions_outcomes(self):
        """Play evidence labelled Zapstore, and the reverse, must fail rather than pass on shape."""
        metrics, events, _ = evidence("Play")
        self.assertFalse(check_apk(metrics, events, "Zapstore")["passed"])
        metrics, events, _ = evidence("Zapstore")
        self.assertFalse(check_apk(metrics, events, "Play")["passed"])

    def test_every_missing_metric_or_ledger_event_fails(self):
        """Removing any single metric or request event is material evidence loss."""
        for distribution in ("Play", "Zapstore"):
            metrics, events, _ = evidence(distribution)
            for index in range(len(metrics)):
                self.assertFalse(check_apk(metrics[:index] + metrics[index + 1:], events, distribution)["passed"], index)
            for index in range(len(events)):
                self.assertFalse(check_apk(metrics, events[:index] + events[index + 1:], distribution)["passed"], index)

    def test_every_wrong_platform_result_fails(self):
        """Each dispatch row must carry its exact expected result and installer visibility."""
        for distribution in ("Play", "Zapstore"):
            metrics, events, _ = evidence(distribution)
            for index, row in enumerate(metrics):
                if row["phase"] != "apk-dispatch":
                    continue
                for field, value in (("result", "Error"), ("installer_shown", not row["installer_shown"]),
                                     ("transfer_reused", False), ("dispatch_ms", float("nan"))):
                    altered = deepcopy(metrics)
                    altered[index][field] = value
                    self.assertFalse(check_apk(altered, events, distribution)["passed"], (distribution, index, field))

    def test_a_retry_that_downloads_again_fails(self):
        """A denied or blocked dispatch must reuse the completed download, never fetch again."""
        for distribution in ("Play", "Zapstore"):
            metrics, events, _ = evidence(distribution)
            extra = events + [{"seq": 999, "request": None, "kind": "get", "value": 0, "fixture": "upload-1"}]
            self.assertFalse(check_apk(metrics, extra, distribution)["passed"])

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
