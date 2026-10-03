"""Evaluate pooled controlled-matrix results against the published latency and memory budgets."""

import argparse
import json
from pathlib import Path

from matrix_report import aggregate
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
    for budget in budgets:
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
        lines.append(f"| {r['budget']} | {r['link']} | {size_label(r['size'])} | {value} | {r['ceiling']:.1f} | "
                     f"{target} | {status} |")
    lines.append(f"\n{quiet} cell values are within their ceiling and meet any target.")
    return "\n".join(lines)


def main():
    """Pool the given reports, print the budget table and exit non-zero when any ceiling is breached."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reports", nargs="+", type=Path, help="Preserved matrix reports of one head")
    parser.add_argument("--budgets", type=Path, default=BUDGETS_PATH)
    args = parser.parse_args()
    result = evaluate(aggregate([json.loads(path.read_text())["raw"] for path in args.reports]),
                      load_budgets(args.budgets))
    print(table(result))
    raise SystemExit(0 if result["passed"] else 1)


if __name__ == "__main__":
    main()
