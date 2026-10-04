"""Compare two controlled latency matrices; a candidate is accepted only if it is not slower and not different."""

import argparse
import json
from pathlib import Path

from matrix_report import aggregate, check_matrix, compare


def load_checked(paths):
    """Read each preserved run and return its raw matrix, or the violations of every run that fails correctness.

    Aggregation drops counters such as HEAD requests, so a run is only comparable after the same correctness checks
    that gated its own report: a run that fails them cannot make a candidate pass.
    """
    runs, violations = [], []
    for path in paths:
        raw = json.loads(path.read_text())["raw"]
        verdict = check_matrix(raw)
        violations.extend(f"{path.name}: {violation}" for violation in verdict["violations"])
        runs.append(raw)
    return runs, violations


def main():
    """Print the per-cell verdicts and exit non-zero when correctness differs or any cell is materially slower.

    Exits with status 2, before comparing anything, when any baseline or candidate run fails correctness.
    """
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, nargs="+", required=True, help="One or more repeats of the baseline")
    parser.add_argument("--candidate", type=Path, nargs="+", required=True, help="One or more repeats of the candidate")
    args = parser.parse_args()
    baseline_runs, baseline_violations = load_checked(args.baseline)
    candidate_runs, candidate_violations = load_checked(args.candidate)
    if baseline_violations or candidate_violations:
        print(json.dumps({"accepted": False, "invalid_runs": baseline_violations + candidate_violations}, indent=2))
        raise SystemExit(2)
    baseline = aggregate(baseline_runs)
    candidate = aggregate(candidate_runs)
    result = compare(baseline, candidate)
    print(json.dumps({k: result[k] for k in ("accepted", "correctness_diffs")}, indent=2))
    for cell in result["faster"] + result["slower"]:
        print(f'{cell["verdict"]:>6} {cell["profile"]} {cell["size"]} {cell["metric"]}: '
              f'{cell["baseline_p50"]:.1f} -> {cell["candidate_p50"]:.1f} ms (spread {cell["spread_ms"]:.1f})')
    raise SystemExit(0 if result["accepted"] else 1)


if __name__ == "__main__":
    main()
