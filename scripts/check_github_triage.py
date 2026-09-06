#!/usr/bin/env python3
"""Check or minimally reconcile the White Noise Android GitHub Project."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from collections import Counter


OWNER = "marmot-protocol"
REPO = f"{OWNER}/whitenoise-android"
PROJECT = "7"
PROJECT_ITEM_FETCH_ATTEMPTS = 3
LEGACY_PROJECT = "5"
RETIRED_LABELS = {"CRITICAL", "HIGH", "MEDIUM", "LOW", "tracking"}
REQUIRED_ITEM_FIELDS = ("priority", "area", "triage health", "status")
ALLOWED_ISSUE_TYPES = {"Bug", "Feature", "Task", "Tracking"}
ALLOWED_ITEM_FIELD_VALUES = {
    "priority": {"P0", "P1", "P2", "P3"},
    "area": {
        "Conversations",
        "Media",
        "Notifications",
        "Groups",
        "Profiles & identity",
        "Search",
        "Accessibility",
        "Performance & reliability",
        "Security & privacy",
        "Settings",
        "Release & tooling",
    },
    "triage health": {
        "Needs triage",
        "Ready",
        "Blocked",
        "Needs design",
        "Needs upstream",
    },
    "status": {"Todo", "In Progress", "Done"},
}


def gh(*args: str) -> str:
    completed = subprocess.run(
        ["gh", *args], check=True, text=True, capture_output=True
    )
    return completed.stdout


def gh_json(*args: str):
    return json.loads(gh(*args))


def fetch_project_items(initial_total_count: int) -> list[dict]:
    """Fetch every Project item, retrying if the Project grows mid-request."""
    limit = max(initial_total_count, 1)
    for _ in range(PROJECT_ITEM_FETCH_ATTEMPTS):
        response = gh_json(
            "project", "item-list", PROJECT, "--owner", OWNER,
            "--limit", str(limit), "--format", "json",
        )
        items = response["items"]
        total_count = response["totalCount"]
        if len(items) >= total_count:
            return items
        if total_count <= limit:
            raise RuntimeError(
                f"Project item pagination stopped at {len(items)} of {total_count} items"
            )
        limit = total_count
    raise RuntimeError(
        f"Project kept growing during {PROJECT_ITEM_FETCH_ATTEMPTS} pagination attempts"
    )


def snapshot() -> tuple[dict, dict, list[dict], list[dict], list[dict], set[str]]:
    current = gh_json("project", "view", PROJECT, "--owner", OWNER, "--format", "json")
    legacy = gh_json(
        "project", "view", LEGACY_PROJECT, "--owner", OWNER, "--format", "json"
    )
    # Fetch open issues directly. `--state all --limit 500` is not exhaustive in
    # this long-lived repository: it returns only the newest 500 issues and can
    # silently omit older issues that remain open.
    issues = gh_json(
        "issue", "list", "-R", REPO, "--state", "open", "--limit", "500",
        "--json", "number,url,state,issueType,labels,title",
    )
    pull_requests = gh_json("api", f"repos/{REPO}/pulls?state=open&per_page=100")
    project = fetch_project_items(current["items"]["totalCount"])
    labels = {
        item["name"]
        for item in gh_json("label", "list", "-R", REPO, "--limit", "500", "--json", "name")
    }
    return current, legacy, issues, pull_requests, project, labels


def findings(current, legacy, issues, pull_requests, project, labels) -> list[str]:
    problems: list[str] = []
    if current.get("closed") or not current.get("public"):
        problems.append("project 7 must be open and public")
    if not legacy.get("closed"):
        problems.append("legacy project 5 must remain closed")

    open_issues = [item for item in issues if item.get("state", "OPEN") == "OPEN"]
    open_by_number = {item["number"]: item for item in open_issues}
    project_open = [
        item for item in project if item.get("content", {}).get("number") in open_by_number
    ]
    counts = Counter(item["content"]["number"] for item in project_open)
    missing = sorted(set(open_by_number) - set(counts))
    duplicates = sorted(number for number, count in counts.items() if count != 1)
    if missing:
        problems.append(f"open issues missing from project: {missing}")
    if duplicates:
        problems.append(f"open issues duplicated in project: {duplicates}")

    open_prs = {item["number"]: item for item in pull_requests}
    project_prs = [
        item
        for item in project
        if item.get("content", {}).get("type") == "PullRequest"
        and item.get("content", {}).get("number") in open_prs
    ]
    pr_counts = Counter(item["content"]["number"] for item in project_prs)
    missing_prs = sorted(set(open_prs) - set(pr_counts))
    duplicate_prs = sorted(number for number, count in pr_counts.items() if count != 1)
    if missing_prs:
        problems.append(f"open pull requests missing from project: {missing_prs}")
    if duplicate_prs:
        problems.append(f"open pull requests duplicated in project: {duplicate_prs}")
    stale_pr_status = sorted(
        item["content"]["number"]
        for item in project_prs
        if item.get("status") != "In Progress"
    )
    if stale_pr_status:
        problems.append(
            f"open pull requests without In Progress status: {stale_pr_status}"
        )

    issue_types = {
        item["number"]: (item.get("issueType") or {}).get("name") for item in open_issues
    }
    untyped = sorted(number for number, issue_type in issue_types.items() if not issue_type)
    if untyped:
        problems.append(f"open issues missing native type: {untyped}")
    invalid_types = sorted(
        number
        for number, issue_type in issue_types.items()
        if issue_type and issue_type not in ALLOWED_ISSUE_TYPES
    )
    if invalid_types:
        problems.append(f"open issues with invalid native type: {invalid_types}")
    invalid_trackers = sorted(
        item["number"]
        for item in open_issues
        if item.get("title", "").casefold().startswith("tracking:")
        and (item.get("issueType") or {}).get("name") != "Tracking"
    )
    if invalid_trackers:
        problems.append(f"open trackers without native Tracking type: {invalid_trackers}")

    by_number = {item["content"]["number"]: item for item in project_open}
    for field in REQUIRED_ITEM_FIELDS:
        unset = sorted(number for number, item in by_number.items() if not item.get(field))
        if unset:
            problems.append(f"open issues missing {field}: {unset}")
        allowed = ALLOWED_ITEM_FIELD_VALUES.get(field)
        invalid = sorted(
            number
            for number, item in by_number.items()
            if item.get(field) and allowed and item[field] not in allowed
        )
        if invalid:
            problems.append(f"open issues with invalid {field}: {invalid}")

    stale_open = sorted(
        number for number, item in by_number.items() if item.get("status") == "Done"
    )
    if stale_open:
        problems.append(f"open issues with stale Done status: {stale_open}")
    stale_closed = sorted(
        item["content"]["number"]
        for item in project
        if item.get("content", {}).get("state") == "CLOSED"
        and item.get("status") != "Done"
    )
    if stale_closed:
        problems.append(f"closed project issues without Done status: {stale_closed}")

    retired_present = sorted(RETIRED_LABELS & labels)
    if retired_present:
        problems.append(f"retired labels still exist: {retired_present}")
    retired_used = sorted(
        item["number"]
        for item in open_issues
        if RETIRED_LABELS & {label["name"] for label in item.get("labels", [])}
    )
    if retired_used:
        problems.append(f"open issues use retired labels: {retired_used}")
    return problems


def add_missing(issues: list[dict], pull_requests: list[dict], project: list[dict]) -> None:
    present = {item.get("content", {}).get("number") for item in project}
    for issue in issues:
        if issue.get("state", "OPEN") == "OPEN" and issue["number"] not in present:
            gh("project", "item-add", PROJECT, "--owner", OWNER, "--url", issue["url"])
    for pull_request in pull_requests:
        if pull_request["number"] not in present:
            gh(
                "project", "item-add", PROJECT, "--owner", OWNER,
                "--url", pull_request["html_url"],
            )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--repair-additions", action="store_true", help="add missing open issues, then recheck"
    )
    args = parser.parse_args()
    gh("auth", "status")
    current, legacy, issues, pull_requests, project, labels = snapshot()
    if args.repair_additions:
        add_missing(issues, pull_requests, project)
        current, legacy, issues, pull_requests, project, labels = snapshot()
    problems = findings(current, legacy, issues, pull_requests, project, labels)
    report = {
        "project_url": current.get("url"),
        "open_issue_count": sum(item.get("state", "OPEN") == "OPEN" for item in issues),
        "open_pull_request_count": len(pull_requests),
        "project_item_count_including_native_closed_subissues": len(project),
        "healthy": not problems,
        "findings": problems,
    }
    print(json.dumps(report, indent=2))
    return 0 if not problems else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except subprocess.CalledProcessError as error:
        print(error.stderr.strip() or str(error), file=sys.stderr)
        raise SystemExit(error.returncode)
