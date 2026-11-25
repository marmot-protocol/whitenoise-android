"""Regression coverage for the report-only paired battery comparison."""

import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from scripts.battery_eval import METRICS, report


BASELINE = {"source_sha": "a" * 40, "target_apk_sha256": "b" * 64, "mdk_sha": "c" * 40}


def campaign(comparison=False, scenario="push_idle", changes=(0, 2, -1, 1, -2)):
    """Build five synthetic pairs in alternating acquisition order."""
    idle = scenario.endswith("_idle")
    duration = 60000 if idle else 30000
    expected = 0 if idle else 5
    pairs = []
    for index, change in enumerate(changes):
        pair = []
        for arm, energy in (("a", 6_000_000), ("b", 6_000_000 * (1 + change / 100))):
            digest = hashlib.sha256(f"{comparison}:{scenario}:{index}:{arm}".encode()).hexdigest()
            pair.append({
                "arm": arm, "screen_off": True, "process_alive": True, "charging": False,
                "restored": True, "mode": "push", "permission": True, "temperature_decicelsius": 330,
                "duration_ms": duration, "received": expected, "duplicates": 0,
                "receipt_before_window_end": not idle, "recovery_attempts": 0, "capture_sha256": digest,
                "energy_uws": {"total": energy, "cpu": 1_000_000, "network": 2_000_000, "memory": 500_000},
            })
        pairs.append(pair if index % 2 == 0 else pair[::-1])
    return {
        "schema": 1,
        "method": "perfetto",
        "network": "wifi",
        "device": {"model": "Pixel 9a", "api": 37, "build": "google/test-fingerprint"},
        "fixture_sha256": "d" * 64,
        "a": BASELINE.copy(),
        "b": {**BASELINE, "target_apk_sha256": "e" * 64} if comparison else BASELINE.copy(),
        "scenarios": {scenario: {
            "window_ms": duration, "expected_messages": expected, "max_recovery_attempts": 0, "pairs": pairs,
        }},
    }


class BatteryEvalTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.directory = Path(temp.name)

    def write(self, value, name="campaign.json"):
        path = self.directory / name
        path.write_text(json.dumps(value), encoding="utf-8")
        return path

    def test_same_apk_calibrates_and_comparison_uses_paired_noise(self):
        aa = self.write(campaign(), "calibration.json")
        calibration = report(aa)["scenarios"]["push_idle"]
        self.assertEqual("calibrated", calibration["status"])
        self.assertAlmostEqual(2, calibration["noise_floor_percent"])
        self.assertEqual(100, calibration["median_a"]["total"])
        for change, expected in ((-10, "lower_energy"), (1, "within_noise"), (10, "higher_energy")):
            with self.subTest(change=change):
                ab = self.write(campaign(True, changes=(change,) * 5))
                result = report(ab, aa)["scenarios"]["push_idle"]
                self.assertEqual(expected, result["status"])
                self.assertAlmostEqual(change, result["median_change_percent"])

    def test_total_energy_requires_equal_actual_windows_in_each_pair(self):
        """Equal power over different event windows cannot yield an energy verdict."""
        for scenario in ("push_burst", "reconnect"):
            with self.subTest(scenario=scenario):
                value = campaign(scenario=scenario)
                config = value["scenarios"][scenario]
                if scenario == "reconnect":
                    config["max_recovery_attempts"] = 2
                baseline, candidate = config["pairs"][0]
                baseline.update(duration_ms=31500)
                candidate.update(duration_ms=28500)
                baseline["energy_uws"]["total"] = 6_300_000
                candidate["energy_uws"]["total"] = 5_700_000

                result = report(self.write(value))["scenarios"][scenario]
                self.assertEqual({"status": "inconclusive", "reason": "mismatched_event_windows"}, result)

    def test_delivery_failure_surfaces_without_a_rail_sample(self):
        aa = self.write(campaign(scenario="push_burst"), "calibration.json")
        ab = campaign(True, "push_burst", (-10,) * 5)
        sample = ab["scenarios"]["push_burst"]["pairs"][0][1]
        sample.update(received=0, receipt_before_window_end=False, energy_uws=None)
        result = report(self.write(ab), aa)["scenarios"]["push_burst"]
        self.assertEqual("delivery_failed", result["status"])
        self.assertEqual({"a": 0, "b": 1}, result["delivery_failures"])
        self.assertNotIn("median_change_percent", result)

    def test_calibration_must_share_apk_and_be_repeatable(self):
        self.assertEqual("artifact_mismatch", report(self.write(campaign(True)))["reason"])
        unstable = self.write(campaign(changes=(0, 1, 2, 0, 90)), "calibration.json")
        ab = self.write(campaign(True))
        self.assertEqual("unusable_calibration", report(ab, unstable)["scenarios"]["push_idle"]["reason"])

    def test_rejects_confounders_and_capture_reuse(self):
        for code, mutate in (
            ("invalid_device_posture", lambda a, b: b.update(charging=True)),
            ("reused_capture", lambda a, b: b.update(capture_sha256=a["capture_sha256"])),
            ("invalid_measurement_window", lambda a, b: b.update(duration_ms=45000)),
            ("invalid_rail_energy", lambda a, b: b["energy_uws"].update(total=None)),
            ("temperature_spread", lambda a, b: b.update(temperature_decicelsius=351)),
        ):
            with self.subTest(code=code):
                value = campaign()
                mutate(*value["scenarios"]["push_idle"]["pairs"][0])
                result = report(self.write(value))["scenarios"]["push_idle"]
                self.assertEqual({"status": "inconclusive", "reason": code}, result)

    def test_calibration_rejects_a_different_fixture_or_workload(self):
        aa = self.write(campaign(), "calibration.json")
        ab = campaign(True)
        ab["fixture_sha256"] = "e" * 64
        self.assertEqual("calibration_mismatch", report(self.write(ab), aa)["reason"])
        ab = campaign(True)
        ab["scenarios"]["push_idle"]["window_ms"] = 61000
        result = report(self.write(ab), aa)["scenarios"]["push_idle"]
        self.assertEqual("missing_or_mismatched_calibration", result["reason"])

    def test_reads_and_verifies_macrobenchmark_iteration_values(self):
        value = campaign()
        value["method"] = "macrobenchmark"
        device = value["device"]
        for index, pair in enumerate(value["scenarios"]["push_idle"]["pairs"]):
            for sample in pair:
                energy = sample.pop("energy_uws")
                data = {
                    "context": {"build": {
                        "model": device["model"], "fingerprint": device["build"], "version": {"sdk": device["api"]},
                    }},
                    "benchmarks": [{
                        "name": "idleWithNativePushPower",
                        "className": "dev.ipf.whitenoise.android.benchmark.BackgroundIdleBenchmark",
                        "repeatIterations": 1,
                        "thermalThrottleSleepSeconds": 0,
                        "totalRunTimeNs": index * 2 + (sample["arm"] == "b"),
                        "metrics": {v: {"runs": [energy[k]], "median": -1} for k, v in METRICS.items()},
                    }],
                }
                evidence = self.write(data, f"{index}-{sample['arm']}-benchmarkData.json")
                sample["benchmark_json"] = evidence.name
                sample["capture_sha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
        manifest = self.write(value)
        self.assertEqual("calibrated", report(manifest)["scenarios"]["push_idle"]["status"])
        evidence.write_text("{}", encoding="utf-8")
        self.assertEqual("capture_digest_mismatch", report(manifest)["scenarios"]["push_idle"]["reason"])

    def test_cli_is_report_only_even_for_unusable_evidence(self):
        sample = self.write({"private": "fixture text"})
        run = subprocess.run(
            [sys.executable, str(Path(__file__).with_name("battery_eval.py")), str(sample)],
            capture_output=True, text=True, check=True,
        )
        self.assertTrue(json.loads(run.stdout)["report_only"])
        self.assertNotIn("fixture text", run.stdout)


if __name__ == "__main__":
    unittest.main()
