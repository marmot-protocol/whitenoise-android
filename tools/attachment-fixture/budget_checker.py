"""Enforce per-sample fixture ceilings; these are regression guards, not release SLOs."""

import argparse
from collections import Counter
import json
import math
from pathlib import Path

REOPEN = "received-native-reopen-unavailable-endpoint"
PHASE_COUNTS = {"received-cold": 1, "received-retained": 10, REOPEN: 1}
# Declared environments use the original reference ceilings; adding a profile does not qualify its measurements.
PROFILES = {
    "reference-api30-arm64": {"api": "30", "abi": "arm64-v8a"},
    "reference-api36-arm64": {"api": "36", "abi": "arm64-v8a"},
    "ci-api34-x86_64": {"api": "34", "abi": "x86_64"},
    "pixel-api37-arm64": {"api": "37", "abi": "arm64-v8a"},
}
LIMITS = {"cold_ms": 1500, "local_ms": 150, "java_peak_bytes": 32 * 1024**2,
          "native_peak_bytes": 128 * 1024**2}


def check_budget(metrics, profile):
    """Reject missing phases, unsuccessful reads, invalid measurements and any ceiling breach."""
    if profile not in PROFILES:
        raise ValueError("unknown attachment budget profile")
    violations = []
    counts = Counter()
    if not isinstance(metrics, list):
        metrics = []
        violations.append("metrics must be a list")
    for index, sample in enumerate(metrics):
        if not isinstance(sample, dict) or not isinstance(sample.get("phase"), str):
            violations.append(f"sample {index}: missing phase")
            continue
        phase = sample["phase"]
        if phase in ("fixture-stage", "genuine-native-send-retention"):
            continue
        if phase not in PHASE_COUNTS:
            violations.append(f"sample {index}: unknown measured phase")
            continue
        counts[phase] += 1
        if sample.get("success") is not True or sample.get("payload_bytes") != 1024:
            violations.append(f"sample {index}: {phase} lacks successful exact 1 KiB read")
        ceilings = {"elapsed_ms": LIMITS["cold_ms" if phase == "received-cold" else "local_ms"],
                    "java_peak_bytes": LIMITS["java_peak_bytes"],
                    "native_peak_bytes": LIMITS["native_peak_bytes"]}
        for field, ceiling in ceilings.items():
            value = sample.get(field)
            if isinstance(value, bool) or not isinstance(value, (int, float)) or (isinstance(value, float) and not math.isfinite(value)) or value < 0:
                violations.append(f"sample {index}: {phase} invalid {field}")
            elif value > ceiling:
                violations.append(f"sample {index}: {phase} {field} exceeds {ceiling}")
    for phase, expected in PHASE_COUNTS.items():
        if counts[phase] != expected:
            violations.append(f"{phase}: expected {expected} samples, got {counts[phase]}")
    return {"profile": profile, "limits": dict(LIMITS), "passed": not violations, "violations": violations}


def main():
    """Check an existing redacted Android report and fail the command on a budget violation."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    parser.add_argument("--profile", choices=PROFILES, required=True)
    args = parser.parse_args()
    result = check_budget(json.loads(args.report.read_text()).get("metrics"), args.profile)
    print(json.dumps(result, indent=2))
    raise SystemExit(0 if result["passed"] else 1)


if __name__ == "__main__":
    main()
