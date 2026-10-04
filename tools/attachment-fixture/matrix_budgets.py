"""Evaluate pooled controlled-matrix results against the published latency and memory budgets."""

import argparse
import json
from pathlib import Path

from matrix_report import aggregate, load_checked_runs
from matrix_tables import size_label

MIB = 1024 * 1024
BUDGETS_PATH = Path(__file__).with_name("matrix_budgets.json")


def load_budgets(path=BUDGETS_PATH):
    """The budget definitions; a malformed file fails loudly instead of silently passing."""
    data = json.loads(Path(path).read_text())
    if data.get("schema") != 1 or not isinstance(data.get("budgets"), list) or not data["budgets"]:
        raise ValueError("unsupported budget schema")
    for budget in data["budgets"]:
        for key in ("id", "metric", "unit", "ceiling"):
            if key not in budget:
                raise ValueError(f"budget is missing {key}")
    return data["budgets"]


def limit(bound, size):
    """A bound is a fixed part plus a part that grows with the payload in MiB."""
    return None if bound is None else bound["fixed"] + bound["per_mib"] * size / MIB


def observed(profile, cell, budget):
    """The budgeted quantile of one cell, in the budget's unit, or None when it was not measured."""
    metric = budget["metric"]
    stats = (profile["recreated_native_lease_ms"].get(cell["size"]) if metric == "restart_lease_ms"
             else cell.get(metric))
    if not stats or stats.get(budget.get("quantile", "p50")) is None:
        return None
    value = stats[budget.get("quantile", "p50")]
    return value / MIB if budget["unit"] == "MiB" else value


def evaluate(agg, budgets):
    """One row per budget and cell. A ceiling breach or a missing measurement is a violation."""
    rows = []
    present = {profile["name"] for profile in agg["profiles"]}
    for budget in budgets:
        # A budget that names its links is not met by a matrix that never measured them.
        for link in budget.get("links", ()):
            if link not in present:
                rows.append({"budget": budget["id"], "link": link, "size": None, "observed": None, "ceiling": None,
                             "target": None, "within_ceiling": False, "met_target": None})
        for profile in agg["profiles"]:
            if "links" in budget and profile["name"] not in budget["links"]:
                continue
            for cell in profile["cells"]:
                value = observed(profile, cell, budget)
                ceiling = limit(budget["ceiling"], cell["size"])
                target = limit(budget.get("target"), cell["size"])
                rows.append({"budget": budget["id"], "link": profile["name"], "size": cell["size"],
                             "observed": value, "ceiling": ceiling, "target": target,
                             "within_ceiling": value is not None and value <= ceiling,
                             "met_target": None if target is None or value is None else value <= target})
    violations = [r for r in rows if not r["within_ceiling"]]
    return {"rows": rows, "violations": violations, "passed": bool(rows) and not violations}


def table(result):
    """Markdown rows for every cell that breaches a ceiling or misses a target, and a count of the rest."""
    lines = ["| Budget | Link | Size | Observed | Ceiling | Target | Status |", "| --- | --- | ---: | ---: | ---: | ---: | --- |"]
    quiet = 0
    for r in result["rows"]:
        if r["within_ceiling"] and r["met_target"] is not False:
            quiet += 1
            continue
        status = "over ceiling" if not r["within_ceiling"] else "target not met"
        value = "missing" if r["observed"] is None else f"{r['observed']:.1f}"
        target = "—" if r["target"] is None else f"{r['target']:.1f}"
        size = "link not measured" if r["size"] is None else size_label(r["size"])
        ceiling = "—" if r["ceiling"] is None else f"{r['ceiling']:.1f}"
        lines.append(f"| {r['budget']} | {r['link']} | {size} | {value} | {ceiling} | {target} | {status} |")
    lines.append(f"\n{quiet} cell values are within their ceiling and meet any target.")
    return "\n".join(lines)


def main():
    """Pool the given reports, print the budget table and exit non-zero when any ceiling is breached.

    Exits with status 2, before any budget is evaluated, when a report did not qualify, fails the correctness check,
    or was taken in a different environment or link shape than the others, because timings that meet a ceiling
    prove nothing when the run behind them was incomplete or its requests were wrong.
    """
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reports", nargs="+", type=Path, help="Preserved matrix reports of one head")
    parser.add_argument("--budgets", type=Path, default=BUDGETS_PATH)
    args = parser.parse_args()
    runs, violations = load_checked_runs(args.reports)
    try:
        pooled = aggregate(runs) if not violations else None
    except ValueError as error:
        violations.append(str(error))
    if violations:
        print(json.dumps({"passed": False, "invalid_runs": violations}, indent=2))
        raise SystemExit(2)
    result = evaluate(pooled, load_budgets(args.budgets))
    print(table(result))
    raise SystemExit(0 if result["passed"] else 1)


if __name__ == "__main__":
    main()
