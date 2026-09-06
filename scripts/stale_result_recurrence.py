#!/usr/bin/env python3
"""Measure stale-result bug-fix recurrence over a bounded historical window.

This tool produces the reproducible baseline described in the measurement
contract for the invariant-gate policy review. It has three subcommands:

  fetch     Retrieve issue metadata for the fixed baseline window from the
            GitHub API into a local snapshot file. The snapshot contains
            user-authored text and must never be committed.
  classify  Deterministically classify a snapshot, applying the reviewed
            adjudication fixture, and write or verify the frozen
            privacy-safe classification fixture.
  report    Aggregate the frozen classification fixture into the eligible,
            stale-result, named-recurrence, and monthly tables. Runs
            offline from checked-in data only.

Classification distinguishes three kinds of report:

  1. stale-result evidence: a defect explicitly described as stale work, a
     stale callback, a late async completion, an obsolete generation or
     session result, or an old-account/old-screen result applied after
     ownership changed;
  2. named-prior-fix evidence: a report that names a prior issue or pull
     request as an earlier attempted fix, regression, recurrence, or
     incomplete fix; and
  3. broad language only: words such as "again", "old", or "stale" without
     established stale-result behavior or a named earlier fix.

Keyword matching only generates candidates. Every ambiguous case must be
resolved through the adjudication fixture; the classifier fails rather than
silently promoting a keyword hit to a final classification.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

REPO = "marmot-protocol/whitenoise-android"

# Fixed historical baseline window (UTC, inclusive).
WINDOW_START = datetime(2026, 5, 26, 0, 0, 0, tzinfo=timezone.utc)
WINDOW_END = datetime(2026, 8, 20, 23, 59, 59, tzinfo=timezone.utc)

# Months whose coverage inside the window is not the full calendar month.
PARTIAL_MONTHS = {
    "2026-05": "partial: 2026-05-26 through 2026-05-31",
    "2026-08": "partial: 2026-08-01 through 2026-08-20",
}

SCRIPT_DIR = Path(__file__).resolve().parent
ADJUDICATIONS_PATH = SCRIPT_DIR / "stale_result_recurrence_adjudications.json"
CLASSIFICATIONS_PATH = SCRIPT_DIR / "stale_result_recurrence_classifications.json"

SEARCH_RESULT_CAP = 1000
PAGE_SIZE = 100

EXCLUSION_CODES = (
    "excluded_pull_request",
    "excluded_out_of_window",
    "excluded_non_defect",
    "excluded_duplicate",
    "excluded_audit_artifact",
)

# Strong stale-result evidence: the report itself establishes that a result,
# callback, or completion from an earlier request/owner was applied after it
# should have been invalidated. Rule ids are stable and appear in the frozen
# fixture as machine-readable evidence codes.
STALE_STRONG_RULES = {
    "stale_noun": re.compile(
        r"\bstale\s+(result|results|callback|callbacks|response|responses|"
        r"completion|completions|data|state|snapshot|update|updates|token|"
        r"generation|read|render|value|values|list|content)\b"
    ),
    "late_completion": re.compile(
        r"\b(late|delayed|out[- ]of[- ]order)\s+(async\s+)?"
        r"(completion|callback|response|result|apply)\b"
    ),
    "obsolete_generation": re.compile(
        r"\b(obsolete|outdated|superseded)\s+"
        r"(generation|session|request|result|response|query|search)\b"
    ),
    "old_owner_result": re.compile(
        r"\b(previous|earlier|old|prior|stale)\s+"
        r"(account|screen|conversation|chat|session|request|search|query|"
        r"generation)(?:'s)?\s+"
        r"(result|results|response|responses|data|state|content|messages|list)\b"
    ),
    "wrong_owner_apply": re.compile(
        r"\b(result|results|response|callback|completion|data|content)\s+"
        r"(from|for|of)\s+(a\s+|the\s+)?"
        r"(previous|earlier|old|prior|different|wrong|another)\s+"
        r"(account|screen|conversation|chat|session|request|generation|query)\b"
    ),
    "applied_after_switch": re.compile(
        r"\b(applied|arrives?|arrived|completes?|completed|lands?|landed|"
        r"delivered|shown|rendered|written)\s+after\s+(the\s+)?(user\s+)?"
        r"(account|screen|conversation|chat|ownership|selection)\s+"
        r"(switch\w*|change\w*|navigat\w*)"
    ),
    "completes_after_leaving": re.compile(
        r"\b(completes?|completed|finish\w*|callback\s+fires?|fires?)\s+after\s+"
        r"(leaving|closing|exiting|dismissing|navigating\s+away)\b"
    ),
    "old_overwrites_new": re.compile(
        r"\b(older|earlier|stale|previous)\s+"
        r"(response|result|data|write|completion|value)\s+"
        r"(overwr\w+|replac\w+|clobber\w+)"
    ),
}

# Medium stale-result signals: enough context to demand a manual reading but
# not enough to classify on keywords alone. These become ambiguous unless the
# adjudication fixture resolves them.
STALE_MEDIUM_RULES = {
    "race_with_ownership": re.compile(
        r"\brace\b[^.\n]{0,120}"
        r"(account|screen|conversation|chat|switch\w*|navigat\w*)"
    ),
    "ownership_with_race": re.compile(
        r"(account|screen|conversation|chat)[^.\n]{0,120}\brace\b"
    ),
    "async_after_dispose": re.compile(
        r"\b(callback|coroutine|job|listener|observer)\b[^.\n]{0,120}"
        r"\b(after|despite)\b[^.\n]{0,80}"
        r"\b(cancel\w*|dispose\w*|unsubscrib\w*|clear\w*|destroy\w*)"
    ),
    "newer_overwritten": re.compile(
        r"\b(newer|latest|current)\s+"
        r"(draft|value|state|result|data|selection|text)\b[^.\n]{0,80}"
        r"\b(overwritten|replaced|clobbered|lost|reverted)\b"
    ),
}

# Named-prior-fix evidence needs both a concrete reference to earlier work
# and recurrence language near that reference.
# At most five digits so six-digit hex colors such as #000000 never read as
# an issue reference.
REFERENCE_PATTERN = re.compile(
    r"#\d{2,5}\b|github\.com/[\w.-]+/[\w.-]+/(issues|pull)/\d+"
)
RECURRENCE_VERB_PATTERN = re.compile(
    r"\b(regression|regressed|recurs?|recurred|recurrence|reoccurr\w*|"
    r"re-occurr\w*|reintroduced?|re-introduced|resurfaced|"
    r"previously\s+fixed|earlier\s+fix|prior\s+fix|"
    r"attempted\s+fix|incomplete\s+fix|fix\s+(did|does)\s+not|came\s+back|"
    r"broke\s+again|happen(s|ing|ed)?\s+again\s+after)\b"
)
NAMED_RECURRENCE_PROXIMITY = 200

BROAD_WORDS_PATTERN = re.compile(r"\b(again|old|stale)\b")

DUPLICATE_BODY_PATTERN = re.compile(r"^\s*duplicate\s+of\s+#\d+", re.IGNORECASE)

VALID_ADJUDICATION_FIELDS = {"exclusion", "stale_result", "named_recurrence", "reason"}


def gh_json(*args: str):
    """Run a gh command and decode its JSON output."""
    completed = subprocess.run(
        ["gh", *args], check=True, text=True, capture_output=True
    )
    return json.loads(completed.stdout)


def parse_timestamp(value: str) -> datetime:
    """Parse a GitHub ISO-8601 timestamp into an aware UTC datetime."""
    return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(
        timezone.utc
    )


def format_timestamp(value: datetime) -> str:
    """Render an aware datetime as the Z-suffixed form GitHub search accepts."""
    return value.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def search_page(fetch, start: datetime, end: datetime, page: int):
    """Fetch one search page for issues created inside [start, end]."""
    query = (
        f"repo:{REPO}+is:issue+"
        f"created:{format_timestamp(start)}..{format_timestamp(end)}"
    )
    return fetch(
        "api",
        f"search/issues?q={query}&per_page={PAGE_SIZE}&page={page}"
        "&sort=created&order=asc",
    )


def fetch_window(fetch, start: datetime, end: datetime) -> list[dict]:
    """Fetch every issue created inside [start, end], bisecting as needed.

    The GitHub search API silently caps any single query at 1000 results, so
    a window whose total exceeds the cap is split at its midpoint and each
    half is fetched recursively. Missing that cap would silently drop issues
    from the denominator, so the split is mandatory rather than best-effort.
    """
    first = search_page(fetch, start, end, 1)
    total = first["total_count"]
    if total > SEARCH_RESULT_CAP:
        if end - start < timedelta(seconds=2):
            raise RuntimeError(
                f"window {format_timestamp(start)}..{format_timestamp(end)} "
                f"holds {total} issues and cannot be split further"
            )
        midpoint = start + (end - start) / 2
        midpoint = midpoint.replace(microsecond=0)
        return fetch_window(fetch, start, midpoint) + fetch_window(
            fetch, midpoint + timedelta(seconds=1), end
        )
    items = list(first["items"])
    page = 2
    while len(items) < total:
        chunk = search_page(fetch, start, end, page)["items"]
        if not chunk:
            raise RuntimeError(
                f"search pagination ended early: got {len(items)} of {total} "
                f"issues for {format_timestamp(start)}..{format_timestamp(end)}"
            )
        items.extend(chunk)
        page += 1
    return items


def snapshot_record(item: dict) -> dict:
    """Reduce a search result to the fields classification needs."""
    return {
        "number": item["number"],
        "created_at": item["created_at"],
        "state": item.get("state"),
        "state_reason": item.get("state_reason"),
        "type": (item.get("type") or {}).get("name"),
        "labels": sorted(label["name"] for label in item.get("labels", [])),
        "is_pull_request": "pull_request" in item,
        "title": item.get("title") or "",
        "body": item.get("body") or "",
    }


def month_key(created: datetime) -> str:
    """Format an aware datetime's calendar month as YYYY-MM."""
    return f"{created.year:04d}-{created.month:02d}"


def match_rule_ids(rules: dict, text: str) -> list[str]:
    """Return the sorted ids of the rules whose patterns match the text."""
    return sorted(rule_id for rule_id, pattern in rules.items() if pattern.search(text))


def named_recurrence_signal(text: str) -> str:
    """Return yes/ambiguous/no for the named-prior-fix axis.

    "yes" requires recurrence language within NAMED_RECURRENCE_PROXIMITY
    characters of a concrete issue/PR reference. Recurrence language and a
    reference that never come near each other is ambiguous, because the
    reference may describe unrelated context.
    """
    references = list(REFERENCE_PATTERN.finditer(text))
    verbs = list(RECURRENCE_VERB_PATTERN.finditer(text))
    if not references or not verbs:
        return "no"
    for reference in references:
        for verb in verbs:
            gap = max(
                reference.start() - verb.end(), verb.start() - reference.end()
            )
            if gap <= NAMED_RECURRENCE_PROXIMITY:
                return "yes"
    return "ambiguous"


def stale_result_signal(text: str) -> tuple[str, list[str]]:
    """Return (yes/ambiguous/no, matched rule ids) for the stale-result axis."""
    strong = match_rule_ids(STALE_STRONG_RULES, text)
    if strong:
        return "yes", strong
    medium = match_rule_ids(STALE_MEDIUM_RULES, text)
    if medium:
        return "ambiguous", medium
    if BROAD_WORDS_PATTERN.search(text):
        return "no", ["broad_language_only"]
    return "no", []


def exclusion_for(record: dict) -> str | None:
    """Return the machine-readable exclusion code for a snapshot record."""
    if record.get("is_pull_request"):
        return "excluded_pull_request"
    created = parse_timestamp(record["created_at"])
    if created < WINDOW_START or created > WINDOW_END:
        return "excluded_out_of_window"
    # The typed "Bug" issue type was only adopted repo-wide during August 2026,
    # so the "bug" label is the defect marker that is consistent across the
    # whole baseline window. Either marker makes an issue a defect at filing.
    is_defect = record.get("type") == "Bug" or "bug" in (record.get("labels") or [])
    if not is_defect:
        return "excluded_non_defect"
    if record.get("state_reason") == "duplicate" or DUPLICATE_BODY_PATTERN.search(
        record.get("body") or ""
    ):
        return "excluded_duplicate"
    return None


def validate_adjudications(adjudications: dict) -> dict[int, dict]:
    """Validate and normalize the adjudication fixture keyed by issue number."""
    normalized: dict[int, dict] = {}
    for key, entry in adjudications.items():
        number = int(key)
        unknown = set(entry) - VALID_ADJUDICATION_FIELDS
        if unknown:
            raise ValueError(
                f"adjudication for #{number} has unknown fields {sorted(unknown)}"
            )
        if "reason" not in entry:
            raise ValueError(f"adjudication for #{number} must state a reason code")
        exclusion = entry.get("exclusion")
        if exclusion is not None and exclusion not in EXCLUSION_CODES:
            raise ValueError(
                f"adjudication for #{number} has unknown exclusion {exclusion!r}"
            )
        normalized[number] = entry
    return normalized


def classify_record(record: dict, adjudication: dict | None) -> dict:
    """Classify one snapshot record, applying any manual adjudication."""
    number = record["number"]
    created = parse_timestamp(record["created_at"])
    result = {
        "number": number,
        "created": created.date().isoformat(),
        "month": month_key(created),
        "exclusion": exclusion_for(record),
        "stale_result": False,
        "named_recurrence": False,
        "rules": [],
        "adjudicated": adjudication is not None,
        "ambiguous": [],
    }
    if adjudication and "exclusion" in adjudication:
        result["exclusion"] = adjudication["exclusion"]
    if result["exclusion"] is not None:
        return result

    text = f"{record.get('title') or ''}\n{record.get('body') or ''}".lower()
    stale, rules = stale_result_signal(text)
    named = named_recurrence_signal(text)
    result["rules"] = rules

    if adjudication and "stale_result" in adjudication:
        result["stale_result"] = bool(adjudication["stale_result"])
    elif stale == "ambiguous":
        result["ambiguous"].append("stale_result")
    else:
        result["stale_result"] = stale == "yes"

    if adjudication and "named_recurrence" in adjudication:
        result["named_recurrence"] = bool(adjudication["named_recurrence"])
    elif named == "ambiguous":
        result["ambiguous"].append("named_recurrence")
    else:
        result["named_recurrence"] = named == "yes"
    return result


def classify_snapshot(records: list[dict], adjudications: dict[int, dict]) -> list[dict]:
    """Classify a full snapshot; raise if any ambiguity is unadjudicated."""
    seen: set[int] = set()
    results = []
    for record in sorted(records, key=lambda item: item["number"]):
        if record["number"] in seen:
            raise ValueError(f"snapshot lists #{record['number']} twice")
        seen.add(record["number"])
        results.append(classify_record(record, adjudications.get(record["number"])))
    unresolved = [result for result in results if result["ambiguous"]]
    if unresolved:
        listing = ", ".join(
            f"#{result['number']}({'+'.join(result['ambiguous'])})"
            for result in unresolved
        )
        raise AmbiguityError(
            f"{len(unresolved)} issues need manual adjudication: {listing}",
            unresolved,
        )
    orphaned = sorted(set(adjudications) - seen)
    if orphaned:
        raise ValueError(
            "adjudication fixture names issues missing from the snapshot: "
            + ", ".join(f"#{number}" for number in orphaned)
        )
    for result in results:
        del result["ambiguous"]
    return results


class AmbiguityError(RuntimeError):
    """Raised when keyword matching alone cannot finalize a classification."""

    def __init__(self, message: str, unresolved: list[dict]):
        """Keep the unresolved classifications alongside the error message."""
        super().__init__(message)
        self.unresolved = unresolved


def aggregate(classifications: list[dict]) -> dict:
    """Compute the counts the measurement contract requires."""
    months: dict[str, dict] = {}
    totals = {
        "created": 0,
        "eligible": 0,
        "stale_result": 0,
        "named_recurrence": 0,
        "stale_named_recurrence": 0,
        "exclusions": {code: 0 for code in EXCLUSION_CODES},
    }
    for entry in classifications:
        month = months.setdefault(
            entry["month"],
            {
                "created": 0,
                "eligible": 0,
                "stale_result": 0,
                "named_recurrence": 0,
                "stale_named_recurrence": 0,
                "note": PARTIAL_MONTHS.get(entry["month"], "full month"),
            },
        )
        month["created"] += 1
        totals["created"] += 1
        if entry["exclusion"] is not None:
            totals["exclusions"][entry["exclusion"]] += 1
            continue
        month["eligible"] += 1
        totals["eligible"] += 1
        if entry["named_recurrence"]:
            month["named_recurrence"] += 1
            totals["named_recurrence"] += 1
        if entry["stale_result"]:
            month["stale_result"] += 1
            totals["stale_result"] += 1
            if entry["named_recurrence"]:
                month["stale_named_recurrence"] += 1
                totals["stale_named_recurrence"] += 1
    return {"months": dict(sorted(months.items())), "totals": totals}


def render_report(summary: dict) -> str:
    """Render the aggregate summary as a stable plain-text table."""
    lines = [
        f"Baseline window: {format_timestamp(WINDOW_START)}"
        f"..{format_timestamp(WINDOW_END)} (UTC, inclusive)",
        "",
        "month    created  eligible  stale  named  stale+named  note",
    ]
    for month, row in summary["months"].items():
        lines.append(
            f"{month}  {row['created']:>7}  {row['eligible']:>8}  "
            f"{row['stale_result']:>5}  {row['named_recurrence']:>5}  "
            f"{row['stale_named_recurrence']:>11}  {row['note']}"
        )
    totals = summary["totals"]
    lines.append("")
    lines.append(
        f"totals   {totals['created']:>7}  {totals['eligible']:>8}  "
        f"{totals['stale_result']:>5}  {totals['named_recurrence']:>5}  "
        f"{totals['stale_named_recurrence']:>11}"
    )
    lines.append("")
    lines.append("exclusions:")
    for code, count in totals["exclusions"].items():
        lines.append(f"  {code}: {count}")
    if totals["eligible"]:
        stale_rate = totals["stale_result"] / totals["eligible"]
        lines.append("")
        lines.append(f"stale-result share of eligible defects: {stale_rate:.1%}")
    lines.append(
        "note: partial months are labeled and must not be compared to full"
        " calendar months as raw counts"
    )
    return "\n".join(lines)


def load_json(path: Path):
    """Read and decode one JSON file."""
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def dump_json(path: Path, payload) -> None:
    """Write JSON with stable key order and a trailing newline."""
    with path.open("w", encoding="utf-8") as handle:
        json.dump(payload, handle, indent=2, sort_keys=True)
        handle.write("\n")


def command_fetch(args) -> int:
    """CLI: fetch the baseline window into a local, uncommitted snapshot."""
    items = fetch_window(gh_json, WINDOW_START, WINDOW_END)
    records = [snapshot_record(item) for item in items]
    records.sort(key=lambda record: record["number"])
    dump_json(Path(args.snapshot), records)
    print(f"wrote {len(records)} issues to {args.snapshot}")
    print("the snapshot contains user-authored text; do not commit it")
    return 0


def command_classify(args) -> int:
    """CLI: classify a snapshot and write or verify the frozen fixture."""
    records = load_json(Path(args.snapshot))
    adjudications = validate_adjudications(load_json(ADJUDICATIONS_PATH))
    try:
        classifications = classify_snapshot(records, adjudications)
    except AmbiguityError as error:
        print(str(error), file=sys.stderr)
        return 2
    if args.write:
        dump_json(CLASSIFICATIONS_PATH, classifications)
        print(f"wrote {len(classifications)} classifications")
        return 0
    frozen = load_json(CLASSIFICATIONS_PATH)
    if frozen != classifications:
        print(
            "classification drift: reclassifying the snapshot does not"
            " reproduce the frozen fixture; rerun with --write and review",
            file=sys.stderr,
        )
        return 1
    print("frozen classifications reproduced exactly")
    return 0


def command_report(_args) -> int:
    """CLI: print the aggregate tables from the frozen fixture."""
    classifications = load_json(CLASSIFICATIONS_PATH)
    print(render_report(aggregate(classifications)))
    return 0


def main(argv: list[str] | None = None) -> int:
    """Parse arguments and dispatch to the selected subcommand."""
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)

    fetch_parser = commands.add_parser(
        "fetch", help="retrieve the baseline window into a local snapshot"
    )
    fetch_parser.add_argument("snapshot", help="path for the uncommitted snapshot")
    fetch_parser.set_defaults(handler=command_fetch)

    classify_parser = commands.add_parser(
        "classify", help="classify a snapshot against the adjudication fixture"
    )
    classify_parser.add_argument("snapshot", help="path of the fetched snapshot")
    classify_parser.add_argument(
        "--write",
        action="store_true",
        help="write the frozen classification fixture instead of verifying it",
    )
    classify_parser.set_defaults(handler=command_classify)

    report_parser = commands.add_parser(
        "report", help="aggregate the frozen classification fixture"
    )
    report_parser.set_defaults(handler=command_report)

    args = parser.parse_args(argv)
    return args.handler(args)


if __name__ == "__main__":
    sys.exit(main())
