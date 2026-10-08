#!/usr/bin/env python3
"""Validate anonymous resource campaigns without converting them into tracker closure.

Inputs are measured records, not a template to fill with assumed zeroes. Energy
is device-wide rail evidence; no field claims app-exclusive battery consumption.
The independent device matrix and before/after attribution remain required.
"""

from __future__ import annotations

import argparse
import json
import math
import re
import statistics
from pathlib import Path

SCENARIOS = ("disabled_idle", "push_idle", "local_idle", "reconnect", "push_burst")
ENERGY = ("cpu_energy_uws", "network_energy_uws", "memory_energy_uws")
RESOURCES = ENERGY + ("wake_lock_ms", "service_ms", "recovery_attempts")
RUN_FIELDS = set(RESOURCES) | {
    "scenario", "round", "window_ms", "screen_off", "process_alive", "mode",
    "permission", "received", "duplicates", "receipt_before_window_end",
    "temperature_decicelsius", "charging", "network", "restored",
}
CAMPAIGN_FIELDS = {
    "schema", "source_sha", "artifact_sha256", "mdk_sha", "device",
    "minimum_rounds", "expected_burst", "budgets", "runs",
}
MIN_WINDOW = {s: 60000 for s in SCENARIOS}
MIN_WINDOW.update(reconnect=25000, push_burst=30000)
MAX_INPUT_BYTES = 2 * 1024 * 1024


def number(value):
    """Reject missing, non-finite, negative and boolean pseudo-measurements."""
    return type(value) in (int, float) and math.isfinite(value) and value >= 0


def integer(value, minimum=0):
    """Require a real integral count instead of a rounded float or boolean."""
    return type(value) is int and value >= minimum


def percentile(values, fraction):
    """Use nearest rank so a small campaign cannot average away its worst tail."""
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * fraction) - 1)]


def validate(value):
    """Fail closed on incomplete, confounded or over-budget measured evidence."""
    failures = set()
    summaries = {}

    def fail(code):
        """Report a closed code without echoing potentially private input values."""
        failures.add(code)

    if not isinstance(value, dict) or set(value) != CAMPAIGN_FIELDS:
        fail("unknown_field")
        return result(failures, summaries)
    if value["schema"] != 1 or type(value["schema"]) is not int:
        fail("invalid_schema")
    for field, length in (("source_sha", 40), ("artifact_sha256", 64), ("mdk_sha", 40)):
        if not isinstance(value[field], str) or not re.fullmatch(r"[0-9a-f]{%d}" % length, value[field]):
            fail("invalid_provenance")
    device = value["device"]
    if not isinstance(device, dict) or set(device) != {"model", "api", "build"}:
        fail("unknown_field")
    elif not integer(device["api"], 34) or any(
        not isinstance(device[f], str) or not 1 <= len(device[f]) <= 200
        for f in ("model", "build")
    ):
        fail("invalid_provenance")
    minimum_rounds = value["minimum_rounds"]
    expected_burst = value["expected_burst"]
    if not integer(minimum_rounds, 5) or minimum_rounds > 100:
        fail("insufficient_rounds")
        minimum_rounds = 5
    if not integer(expected_burst, 5) or expected_burst > 100:
        fail("invalid_burst_count")
        expected_burst = 5
    budgets = value["budgets"]
    if not isinstance(budgets, dict) or set(budgets) != set(SCENARIOS):
        fail("invalid_budgets")
        budgets = {}
    for budget in budgets.values():
        if not isinstance(budget, dict) or set(budget) != set(RESOURCES):
            fail("invalid_budgets")
        elif not all(number(budget[m]) for m in RESOURCES):
            fail("invalid_budgets")
    runs = value["runs"]
    if not isinstance(runs, list) or not 1 <= len(runs) <= 1000:
        fail("invalid_measurement")
        return result(failures, summaries)
    grouped = {s: [] for s in SCENARIOS}
    posture = set()
    temperatures = []
    for run in runs:
        if not isinstance(run, dict) or set(run) != RUN_FIELDS:
            fail("unknown_field")
            continue
        scenario = run["scenario"]
        if not isinstance(scenario, str) or scenario not in grouped:
            fail("invalid_scenario")
            continue
        numeric_fields = RESOURCES + ("window_ms", "temperature_decicelsius")
        count_fields = ("round", "recovery_attempts", "received", "duplicates")
        booleans = ("screen_off", "process_alive", "permission", "receipt_before_window_end", "charging", "restored")
        if not all(number(run[k]) for k in numeric_fields) or not all(
            integer(run[k], 1 if k == "round" else 0) for k in count_fields
        ) or not all(type(run[k]) is bool for k in booleans):
            fail("invalid_measurement")
            continue
        if run["network"] not in ("wifi", "cellular"):
            fail("invalid_measurement")
            continue
        grouped[scenario].append(run)
        posture.add((run["network"], run["charging"]))
        temperatures.append(run["temperature_decicelsius"])
        if run["window_ms"] < MIN_WINDOW[scenario]:
            fail("window_too_short")
        if not run["screen_off"]:
            fail("screen_not_off")
        if not run["process_alive"]:
            fail("process_not_alive")
        if not run["restored"]:
            fail("state_not_restored")
        expected_mode = "local" if scenario == "local_idle" else "push"
        if run["mode"] != expected_mode:
            fail("mode_mismatch")
        if run["permission"] != (scenario != "disabled_idle"):
            fail("permission_mismatch")
        if run["duplicates"]:
            fail("duplicate_delivery")
        if scenario == "push_burst" and (
            run["received"] != expected_burst or not run["receipt_before_window_end"]
        ):
            fail("burst_receipt")
    if len(posture) != 1 or (temperatures and max(temperatures) - min(temperatures) > 20):
        fail("mixed_posture")
    round_sets = []
    for scenario, samples in grouped.items():
        rounds = [r["round"] for r in samples]
        round_sets.append(set(rounds))
        if len(rounds) < minimum_rounds or len(set(rounds)) != len(rounds):
            fail("unbalanced_rounds")
        if not samples:
            continue
        if len({r["window_ms"] for r in samples}) != 1:
            fail("mixed_posture")
        measured = {}
        for metric in RESOURCES:
            values = [r[metric] for r in samples]
            median = statistics.median(values)
            measured[metric] = {"median": median, "p95": percentile(values, .95), "maximum": max(values)}
            if metric in ENERGY and (median <= 0 or (max(values) - min(values)) / median > .25):
                fail("unstable_energy")
            budget = budgets.get(scenario)
            if isinstance(budget, dict) and number(budget.get(metric)) and max(values) > budget[metric]:
                fail("budget_exceeded")
        summaries[scenario] = {"samples": len(samples), "metrics": measured}
    if any(rounds != round_sets[0] for rounds in round_sets[1:]):
        fail("unbalanced_rounds")
    return result(failures, summaries)


def result(failures, summaries):
    """Keep resource acceptance separate from attribution and holistic device evidence."""
    return {
        "schema": 1,
        "resource_campaign_passed": not failures,
        "tracker_complete": False,
        "failures": sorted(failures),
        "scenarios": summaries,
        "remaining_gates": ["device_matrix", "before_after_attribution", "reviewed_budget_provenance"],
        "energy_scope": "system_wide_hardware_rails",
    }


def main():
    """Emit bounded anonymous JSON; malformed input never echoes its raw contents."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("campaign", type=Path)
    args = parser.parse_args()
    try:
        if args.campaign.stat().st_size > MAX_INPUT_BYTES:
            raise ValueError("oversized")
        value = json.loads(args.campaign.read_text(encoding="utf-8"))
        output = validate(value)
    except (OSError, ValueError, TypeError, KeyError, OverflowError):
        output = result({"invalid_input"}, {})
    print(json.dumps(output, indent=2, allow_nan=False))
    return 0 if output["resource_campaign_passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
