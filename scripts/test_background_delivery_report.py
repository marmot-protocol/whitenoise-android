"""Regression evidence for false-positive background delivery qualification."""

import copy
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("background_delivery_report.py")
SCENARIOS = ("disabled_idle", "push_idle", "local_idle", "reconnect", "push_burst")


def sample(scenario, round_number):
    """Build anonymous measured evidence, including receive-side burst receipts."""
    return {
        "scenario": scenario,
        "round": round_number,
        "window_ms": 60000,
        "screen_off": True,
        "process_alive": True,
        "mode": "local" if scenario == "local_idle" else "push",
        "permission": scenario != "disabled_idle",
        "cpu_energy_uws": 1000000,
        "network_energy_uws": 100000,
        "memory_energy_uws": 50000,
        "wake_lock_ms": 100 if scenario == "push_burst" else 0,
        "service_ms": 60000 if scenario == "local_idle" else 100,
        "recovery_attempts": 1 if scenario == "reconnect" else 0,
        "received": 5 if scenario == "push_burst" else 0,
        "duplicates": 0,
        "receipt_before_window_end": scenario == "push_burst",
        "temperature_decicelsius": 300,
        "charging": False,
        "network": "wifi",
        "restored": True,
    }


def campaign():
    """Provide five balanced rounds with conservative fixture-only resource budgets."""
    return {
        "schema": 1,
        "source_sha": "a" * 40,
        "artifact_sha256": "b" * 64,
        "mdk_sha": "c" * 40,
        "device": {"model": "Fixture", "api": 37, "build": "fixture-build"},
        "minimum_rounds": 5,
        "expected_burst": 5,
        "budgets": {
            s: {"cpu_energy_uws": 1100000, "network_energy_uws": 110000,
                "memory_energy_uws": 60000, "wake_lock_ms": 200,
                "service_ms": 60000 if s == "local_idle" else 200,
                "recovery_attempts": 4}
            for s in SCENARIOS
        },
        "runs": [sample(s, r) for r in range(1, 6) for s in SCENARIOS],
    }


class BackgroundDeliveryReportTest(unittest.TestCase):
    """Exercise the CLI's acceptance boundary with measured and misleading records."""

    def report(self, value):
        """Run the real command against a private input rather than its internal helpers."""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "campaign.json"
            path.write_text(json.dumps(value), encoding="utf-8")
            return subprocess.run([sys.executable, str(SCRIPT), str(path)],
                                  capture_output=True, text=True, check=False)

    def test_complete_campaign_is_resource_evidence_not_tracker_closure(self):
        """Keep resource acceptance separate from holistic device and attribution gates."""
        result = self.report(campaign())
        self.assertEqual(0, result.returncode, result.stderr)
        output = json.loads(result.stdout)
        self.assertTrue(output["resource_campaign_passed"])
        self.assertFalse(output["tracker_complete"])
        self.assertIn("device_matrix", output["remaining_gates"])

    def test_empty_burst_smoke_run_cannot_qualify(self):
        """Reject a sleep-only run even when its resource numbers meet every budget."""
        value = campaign()
        for run in value["runs"]:
            if run["scenario"] == "push_burst":
                run["received"] = 0
        result = self.report(value)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("burst_receipt", result.stdout)

    def test_foreground_catch_up_after_measurement_cannot_qualify_burst(self):
        """Require receive evidence before foreground recovery can contaminate the window."""
        value = campaign()
        value["runs"][-1]["receipt_before_window_end"] = False
        self.assertIn("burst_receipt", self.report(value).stdout)

    def test_dropped_power_iteration_is_not_zero_energy(self):
        """Preserve an unavailable rail as missing evidence rather than an assumed zero."""
        value = campaign()
        value["runs"][0]["cpu_energy_uws"] = None
        result = self.report(value)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("invalid_measurement", result.stdout)

    def test_permission_revoke_killing_process_invalidates_disabled_floor(self):
        """Refuse an idle floor collected while permission revocation killed the target."""
        value = campaign()
        value["runs"][0]["process_alive"] = False
        self.assertIn("process_not_alive", self.report(value).stdout)

    def test_local_fallback_cannot_be_labelled_as_native_push(self):
        """Do not compare an automatic persistent fallback as if it were FCM idle."""
        value = campaign()
        value["runs"][1]["mode"] = "local"
        self.assertIn("mode_mismatch", self.report(value).stdout)

    def test_noisy_repeated_samples_do_not_establish_budget(self):
        """Reject a spread that cannot support a stable repeated energy comparison."""
        value = campaign()
        value["runs"][0]["cpu_energy_uws"] = 10000
        self.assertIn("unstable_energy", self.report(value).stdout)

    def test_shortened_smoke_windows_do_not_qualify(self):
        """Keep a quick mechanics smoke run distinct from a full observation."""
        value = campaign()
        value["runs"][0]["window_ms"] = 10000
        self.assertIn("window_too_short", self.report(value).stdout)

    def test_missing_posture_or_round_cannot_be_silently_dropped(self):
        """Require balanced rounds rather than hiding a failed or dropped iteration."""
        value = campaign()
        value["runs"].pop()
        self.assertIn("unbalanced_rounds", self.report(value).stdout)

    def test_charging_or_mixed_network_samples_do_not_qualify(self):
        """Keep confounded or unrestored device postures outside resource acceptance."""
        for key, replacement, failure in (("charging", True, "mixed_posture"),
                                           ("network", "cellular", "mixed_posture"),
                                           ("restored", False, "state_not_restored")):
            with self.subTest(key=key):
                value = campaign()
                value["runs"][0][key] = replacement
                self.assertIn(failure, self.report(value).stdout)

    def test_identifiers_and_content_are_rejected_without_echoing_values(self):
        """Ensure schema rejection does not copy private input into diagnostic output."""
        value = campaign()
        value["runs"][0]["account_id"] = "PRIVATE-IDENTITY-SENTINEL"
        result = self.report(value)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("unknown_field", result.stdout)
        self.assertNotIn("PRIVATE-IDENTITY-SENTINEL", result.stdout + result.stderr)

    def test_non_finite_boolean_and_negative_numeric_values_are_rejected(self):
        """Validate physical quantities before statistical comparison or JSON output."""
        for invalid in (float("nan"), float("inf"), True, -1):
            with self.subTest(value=invalid):
                value = campaign()
                value["runs"][0]["wake_lock_ms"] = invalid
                self.assertIn("invalid_measurement", self.report(value).stdout)

    def test_budget_overrun_is_reported(self):
        """Require every sampled tail to respect its explicitly supplied resource budget."""
        value = campaign()
        for run in value["runs"]:
            if run["scenario"] == "push_idle":
                run["wake_lock_ms"] = 500
        result = self.report(value)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("budget_exceeded", result.stdout)

    def test_original_input_is_not_mutated(self):
        """Leave original measured evidence available for independent review."""
        value = campaign()
        expected = copy.deepcopy(value)
        self.report(value)
        self.assertEqual(expected, value)


if __name__ == "__main__":
    unittest.main()
