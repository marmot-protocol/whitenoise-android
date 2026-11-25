#!/usr/bin/env python3
"""Report paired White Noise battery measurements against a same-APK calibration."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import statistics
from pathlib import Path

if __package__:
    from .background_delivery_report import MAX_INPUT_BYTES, MIN_WINDOW, integer, number
else:
    from background_delivery_report import MAX_INPUT_BYTES, MIN_WINDOW, integer, number


BENCHMARKS = {
    "disabled_idle": ("BackgroundIdleBenchmark", "idleWithDeliveryDisabledPower"),
    "push_idle": ("BackgroundIdleBenchmark", "idleWithNativePushPower"),
    "local_idle": ("BackgroundIdleBenchmark", "idleWithKeepConnectedPower"),
    "push_burst": ("BackgroundIdleBenchmark", "pushBurstPower"),
    "reconnect": ("NetworkRecoveryBenchmark", "backgroundValidatedNetworkRecoveryPower"),
}
METRICS = {
    "total": "energyTotalUws",
    "cpu": "energyCategoryCpuUws",
    "network": "energyCategoryNetworkUws",
    "memory": "energyCategoryMemoryUws",
}
ARTIFACT = {"source_sha": 40, "target_apk_sha256": 64, "mdk_sha": 40}
CAMPAIGN_FIELDS = {"schema", "device", "fixture_sha256", "network", "method", "a", "b", "scenarios"}
SCENARIO_FIELDS = {"window_ms", "expected_messages", "max_recovery_attempts", "pairs"}
SAMPLE_FIELDS = {
    "arm", "screen_off", "process_alive", "charging", "restored", "mode", "permission",
    "temperature_decicelsius", "duration_ms", "received", "duplicates",
    "receipt_before_window_end", "recovery_attempts", "capture_sha256",
}
MIN_PAIRS = 5


class EvidenceError(ValueError):
    """A fixed reason code safe to include in a report."""


def require(condition, code):
    if not condition:
        raise EvidenceError(code)


def fields(value, expected):
    return isinstance(value, dict) and value.keys() == expected


def hex_digest(value, length):
    return isinstance(value, str) and re.fullmatch(r"[0-9a-f]{%d}" % length, value)


def read_json(path, capture_sha256=None):
    with path.open("rb") as stream:
        data = stream.read(MAX_INPUT_BYTES + 1)
    require(len(data) <= MAX_INPUT_BYTES, "input_too_large")
    if capture_sha256:
        require(hashlib.sha256(data).hexdigest() == capture_sha256, "capture_digest_mismatch")
    return json.loads(data)


def benchmark_energy(path, capture_sha256, device, scenario):
    """Read the per-iteration rail values emitted by the existing Macrobenchmarks."""
    report = read_json(path, capture_sha256)
    build = report["context"]["build"]
    require(
        (build["model"], build["version"]["sdk"], build["fingerprint"]) ==
        (device["model"], device["api"], device["build"]),
        "benchmark_device_mismatch",
    )
    benchmark_class, method = BENCHMARKS[scenario]
    test, = report["benchmarks"]
    require(
        test["className"] == f"dev.ipf.whitenoise.android.benchmark.{benchmark_class}"
        and test["name"] == method and type(test["repeatIterations"]) is int and test["repeatIterations"] == 1
        and test["thermalThrottleSleepSeconds"] == 0,
        "benchmark_mismatch",
    )
    energy = {}
    for rail, metric in METRICS.items():
        values = test["metrics"][metric]["runs"]
        require(isinstance(values, list) and len(values) == 1, "missing_rail_sample")
        energy[rail] = values[0]
    return energy


def sample_energy(sample, scenario, config, campaign, directory):
    """Check control evidence and normalize energy to the scenario unit."""
    macrobenchmark = campaign["method"] == "macrobenchmark"
    source = "benchmark_json" if macrobenchmark else "energy_uws"
    require(fields(sample, SAMPLE_FIELDS | {source}), "invalid_sample")
    require(hex_digest(sample["capture_sha256"], 64), "invalid_capture_digest")
    require(
        sample["screen_off"] is True and sample["charging"] is False
        and sample["process_alive"] is True and sample["restored"] is True,
        "invalid_device_posture",
    )
    expected_mode = "local" if scenario == "local_idle" else "push"
    require(
        sample["mode"] == expected_mode and sample["permission"] is (scenario != "disabled_idle"),
        "invalid_delivery_posture",
    )
    duration = sample["duration_ms"]
    window = config["window_ms"]
    require(
        number(duration) and abs(duration - window) <= window * .05
        and number(sample["temperature_decicelsius"]),
        "invalid_measurement_window",
    )
    require(
        all(integer(sample[key]) for key in ("received", "duplicates", "recovery_attempts"))
        and type(sample["receipt_before_window_end"]) is bool,
        "missing_delivery_evidence",
    )
    delivery_ok = (
        sample["received"] == config["expected_messages"] and sample["duplicates"] == 0
        and sample["receipt_before_window_end"] is (config["expected_messages"] != 0)
        and (scenario != "reconnect" or sample["recovery_attempts"] <= config["max_recovery_attempts"])
    )
    if not delivery_ok:
        return None, False
    if macrobenchmark:
        path = (directory / sample[source]).resolve()
        require(directory in path.parents, "invalid_benchmark_path")
        energy = benchmark_energy(path, sample["capture_sha256"], campaign["device"], scenario)
    else:
        energy = sample[source]
    require(
        fields(energy, METRICS.keys()) and all(number(v) for v in energy.values())
        and 1 <= energy["total"] <= 10**15
        and 1.01 * energy["total"] >= sum(energy[rail] for rail in ("cpu", "network", "memory")),
        "invalid_rail_energy",
    )
    divisor = duration if scenario.endswith("_idle") else 1000
    return {rail: value / divisor for rail, value in energy.items()}, True


def summarize_scenario(scenario, config, campaign, directory, used_captures):
    """Summarize complete pairs, requiring equal event windows for total energy."""
    require(fields(config, SCENARIO_FIELDS), "invalid_scenario_config")
    require(integer(config["window_ms"], MIN_WINDOW[scenario]), "invalid_measurement_window")
    expected = config["expected_messages"]
    cap = config["max_recovery_attempts"]
    require(
        integer(expected) and integer(cap) and (
            (scenario.endswith("_idle") and expected == cap == 0)
            or (scenario == "push_burst" and expected == 5 and cap == 0)
            or (scenario == "reconnect" and expected > 0 and cap > 0)
        ),
        "invalid_workload",
    )
    pairs = config["pairs"]
    require(isinstance(pairs, list) and MIN_PAIRS <= len(pairs) <= 100, "insufficient_or_excess_pairs")
    a_first = 0
    samples = {"a": [], "b": []}
    temperatures = []
    failed_deliveries = {"a": 0, "b": 0}
    for pair in pairs:
        require(
            isinstance(pair, list) and len(pair) == 2
            and all(isinstance(sample, dict) for sample in pair)
            and all(sample.get("arm") in ("a", "b") for sample in pair)
            and pair[0]["arm"] != pair[1]["arm"],
            "invalid_pair",
        )
        a_first += pair[0]["arm"] == "a"
        for sample in pair:
            energy, delivery_ok = sample_energy(sample, scenario, config, campaign, directory)
            digest = sample["capture_sha256"]
            require(digest not in used_captures, "reused_capture")
            used_captures.add(digest)
            arm = sample["arm"]
            samples[arm].append(energy)
            temperatures.append(sample["temperature_decicelsius"])
            failed_deliveries[arm] += not delivery_ok
    require(abs(2 * a_first - len(pairs)) <= 1, "unbalanced_order")
    require(max(temperatures) - min(temperatures) <= 20, "temperature_spread")
    if any(failed_deliveries.values()):
        return {"status": "delivery_failed", "pairs": len(pairs), "delivery_failures": failed_deliveries}
    if not scenario.endswith("_idle"):
        require(
            all(first["duration_ms"] == second["duration_ms"] for first, second in pairs),
            "mismatched_event_windows",
        )
    total_a = [sample["total"] for sample in samples["a"]]
    total_b = [sample["total"] for sample in samples["b"]]
    deltas = [100 * (b - a) / a for a, b in zip(total_a, total_b)]
    totals = total_a + total_b
    temperature_range = (min(temperatures), max(temperatures))
    summary = {
        "pairs": len(pairs),
        "unit": "mW" if scenario.endswith("_idle") else "mJ",
        "median_a": {rail: statistics.median(s[rail] for s in samples["a"]) for rail in METRICS},
        "median_b": {rail: statistics.median(s[rail] for s in samples["b"]) for rail in METRICS},
        "median_change_percent": statistics.median(deltas),
        "min_change_percent": min(deltas),
        "max_change_percent": max(deltas),
    }
    total_spread = 100 * (max(totals) - min(totals)) / statistics.median(totals)
    return summary, total_spread, temperature_range


def read_campaign(path, same_apk, used_captures):
    """Validate metadata and summarize available scenarios independently."""
    campaign = read_json(path)
    require(fields(campaign, CAMPAIGN_FIELDS), "invalid_campaign")
    require(type(campaign["schema"]) is int and campaign["schema"] == 1, "invalid_schema")
    device = campaign["device"]
    require(
        fields(device, {"model", "api", "build"}) and integer(device["api"], 34)
        and isinstance(device["model"], str) and device["model"].startswith("Pixel ")
        and isinstance(device["build"], str) and 1 <= len(device["build"]) <= 200,
        "invalid_device",
    )
    require(
        hex_digest(campaign["fixture_sha256"], 64) and campaign["network"] in ("wifi", "cellular")
        and campaign["method"] in ("macrobenchmark", "perfetto"),
        "invalid_fixture",
    )
    for arm in ("a", "b"):
        artifact = campaign[arm]
        require(
            fields(artifact, ARTIFACT.keys()) and all(hex_digest(artifact[k], n) for k, n in ARTIFACT.items()),
            "invalid_artifact",
        )
    apk_matches = campaign["a"]["target_apk_sha256"] == campaign["b"]["target_apk_sha256"]
    require(apk_matches == same_apk and (not same_apk or campaign["a"] == campaign["b"]), "artifact_mismatch")
    scenarios = campaign["scenarios"]
    require(isinstance(scenarios, dict) and scenarios and scenarios.keys() <= BENCHMARKS.keys(), "invalid_scenarios")
    results = {}
    for scenario, config in scenarios.items():
        try:
            results[scenario] = summarize_scenario(scenario, config, campaign, path.resolve().parent, used_captures)
        except EvidenceError as exc:
            results[scenario] = {"status": "inconclusive", "reason": str(exc)}
        except (OSError, TypeError, ValueError, KeyError, OverflowError, RuntimeError):
            results[scenario] = {"status": "inconclusive", "reason": "missing_or_invalid_sample"}
    return campaign, results


def report(campaign_path, calibration_path=None):
    """Generate a report with no device identifiers, source paths or fixture contents."""
    result = {
        "schema": 1,
        "report_only": True,
        "mode": "comparison" if calibration_path else "calibration",
        "energy_scope": "system_wide_measured_rails",
        "scenarios": {},
    }
    used_captures = set()
    try:
        campaign, scenarios = read_campaign(campaign_path, calibration_path is None, used_captures)
        if calibration_path:
            calibration, calibration_scenarios = read_campaign(calibration_path, True, used_captures)
            require(
                all(campaign[k] == calibration[k] for k in ("device", "fixture_sha256", "network", "method", "a")),
                "calibration_mismatch",
            )
    except EvidenceError as exc:
        return {**result, "reason": str(exc)}
    except (OSError, TypeError, ValueError, KeyError, OverflowError):
        return {**result, "reason": "missing_or_invalid_campaign"}

    result["artifacts"] = {"a": campaign["a"], "b": campaign["b"]}
    for scenario, sample in scenarios.items():
        if isinstance(sample, dict):
            result["scenarios"][scenario] = sample
            continue
        summary, spread, temperatures = sample
        result["scenarios"][scenario] = summary
        if not calibration_path:
            set_calibration(summary, spread)
        else:
            match = calibration_scenarios.get(scenario)
            options = ("window_ms", "expected_messages", "max_recovery_attempts")
            if not isinstance(match, tuple) or any(
                campaign["scenarios"][scenario][k] != calibration["scenarios"][scenario][k] for k in options
            ):
                summary.update(status="inconclusive", reason="missing_or_mismatched_calibration")
                continue
            calibrated, aa_spread, aa_temperatures = match
            set_calibration(calibrated, aa_spread)
            temperatures = temperatures + aa_temperatures
            if calibrated["status"] != "calibrated" or max(temperatures) - min(temperatures) > 20:
                summary.update(status="inconclusive", reason="unusable_calibration")
                continue
            floor = calibrated["noise_floor_percent"]
            summary["noise_floor_percent"] = floor
            change = summary["median_change_percent"]
            if change < -floor:
                summary["status"] = "lower_energy"
            elif change > floor:
                summary["status"] = "higher_energy"
            else:
                summary["status"] = "within_noise"
    return result


def set_calibration(summary, spread):
    """Use the largest absolute same-APK paired change as the observed noise floor."""
    summary["total_spread_percent"] = spread
    if spread > 25:
        summary.update(status="inconclusive", reason="unstable_calibration")
    else:
        summary["status"] = "calibrated"
        summary["noise_floor_percent"] = max(abs(summary["min_change_percent"]), abs(summary["max_change_percent"]))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("campaign", type=Path, help="paired A/A campaign, or A/B with --calibration")
    parser.add_argument("--calibration", type=Path, help="raw A/A campaign with matching baseline and setup")
    args = parser.parse_args()
    output = report(args.campaign, args.calibration)
    print(json.dumps(output, indent=2, allow_nan=False))


if __name__ == "__main__":
    main()
