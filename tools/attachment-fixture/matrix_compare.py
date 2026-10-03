"""Compare two controlled latency matrices; a candidate is accepted only if it is not slower and not different."""

import argparse
import json
from pathlib import Path

from matrix_report import aggregate, compare


def main():
    """Print the per-cell verdicts and exit non-zero when correctness differs or any cell is materially slower."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, nargs="+", required=True, help="One or more repeats of the baseline")
    parser.add_argument("--candidate", type=Path, nargs="+", required=True, help="One or more repeats of the candidate")
    args = parser.parse_args()
    baseline = aggregate([json.loads(path.read_text())["raw"] for path in args.baseline])
    candidate = aggregate([json.loads(path.read_text())["raw"] for path in args.candidate])
    result = compare(baseline, candidate)
    print(json.dumps({k: result[k] for k in ("accepted", "correctness_diffs")}, indent=2))
    for cell in result["faster"] + result["slower"]:
        print(f'{cell["verdict"]:>6} {cell["profile"]} {cell["size"]} {cell["metric"]}: '
              f'{cell["baseline_p50"]:.1f} -> {cell["candidate_p50"]:.1f} ms (spread {cell["spread_ms"]:.1f})')
    raise SystemExit(0 if result["accepted"] else 1)


if __name__ == "__main__":
    main()
