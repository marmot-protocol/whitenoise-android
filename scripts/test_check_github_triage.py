#!/usr/bin/env python3

import unittest
from unittest.mock import patch
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).parent))
import check_github_triage as checker


def issue(number=1, issue_type="Bug", state="OPEN", title="Issue"):
    return {
        "number": number,
        "url": f"https://github.com/marmot-protocol/whitenoise-android/issues/{number}",
        "issueType": {"name": issue_type} if issue_type is not None else None,
        "state": state,
        "title": title,
        "labels": [],
    }


def project_item(number=1, state="OPEN", **overrides):
    item = {
        "content": {"number": number, "state": state},
        "priority": "P1",
        "area": "Conversations",
        "triage health": "Ready",
        "status": "Todo",
    }
    item.update(overrides)
    return item


def pull_request(number=2):
    return {
        "number": number,
        "html_url": f"https://github.com/marmot-protocol/whitenoise-android/pull/{number}",
    }


def project_pull_request(number=2, **overrides):
    item = {
        "content": {"number": number, "state": "OPEN", "type": "PullRequest"},
        "status": "In Progress",
    }
    item.update(overrides)
    return item


class FindingsTest(unittest.TestCase):
    def findings(self, issues=None, project=None, pull_requests=None):
        return checker.findings(
            {"closed": False, "public": True},
            {"closed": True},
            issues or [issue()],
            pull_requests or [],
            project or [project_item()],
            set(),
        )

    def test_accepts_documented_values(self):
        issues = [issue(number, issue_type) for number, issue_type in enumerate(
            sorted(checker.ALLOWED_ISSUE_TYPES), start=1
        )]
        project = []
        priorities = sorted(checker.ALLOWED_ITEM_FIELD_VALUES["priority"])
        areas = sorted(checker.ALLOWED_ITEM_FIELD_VALUES["area"])
        health_values = sorted(checker.ALLOWED_ITEM_FIELD_VALUES["triage health"])
        for index, current_issue in enumerate(issues):
            project.append(project_item(
                current_issue["number"],
                priority=priorities[index % len(priorities)],
                area=areas[index % len(areas)],
                **{
                    "triage health": health_values[index % len(health_values)],
                    "status": "Todo",
                },
            ))

        self.assertEqual([], self.findings(issues, project))

    def test_rejects_unsupported_values(self):
        problems = self.findings(
            [issue(issue_type="Incident")],
            [project_item(**{
                "priority": "Urgent",
                "area": "Unknown",
                "triage health": "Healthy",
                "status": "Canceled",
            })],
        )

        self.assertIn("open issues with invalid native type: [1]", problems)
        self.assertIn("open issues with invalid priority: [1]", problems)
        self.assertIn("open issues with invalid area: [1]", problems)
        self.assertIn("open issues with invalid triage health: [1]", problems)
        self.assertIn("open issues with invalid status: [1]", problems)

    def test_reports_missing_values_separately(self):
        problems = self.findings(
            [issue(issue_type=None)],
            [project_item(**{"priority": None, "status": ""})],
        )

        self.assertIn("open issues missing native type: [1]", problems)
        self.assertIn("open issues missing priority: [1]", problems)
        self.assertIn("open issues missing status: [1]", problems)
        self.assertFalse(any("invalid priority" in problem for problem in problems))
        self.assertFalse(any("invalid status" in problem for problem in problems))

    def test_rejects_tracking_title_without_native_tracking_type(self):
        problems = self.findings(
            [issue(issue_type="Task", title="Tracking: rollout")],
            [project_item()],
        )

        self.assertIn("open trackers without native Tracking type: [1]", problems)

    def test_rejects_stale_delivery_status(self):
        open_done = self.findings(
            [issue()],
            [project_item(status="Done")],
        )
        closed_todo = self.findings(
            [issue(state="CLOSED")],
            [project_item(state="CLOSED", status="Todo")],
        )

        self.assertIn("open issues with stale Done status: [1]", open_done)
        self.assertIn("closed project issues without Done status: [1]", closed_todo)

    def test_accepts_delivery_status_matching_issue_state(self):
        self.assertEqual([], self.findings([issue()], [project_item(status="Todo")]))
        self.assertEqual(
            [],
            self.findings(
                [issue(state="CLOSED")],
                [project_item(state="CLOSED", status="Done")],
            ),
        )

    def test_add_missing_ignores_closed_issues(self):
        calls = []
        original = checker.gh
        checker.gh = lambda *args: calls.append(args)
        try:
            checker.add_missing(
                [issue(number=1), issue(number=2, state="CLOSED")],
                [],
                [],
            )
        finally:
            checker.gh = original

        self.assertEqual(1, len(calls))
        self.assertIn("/issues/1", calls[0][-1])

    def test_reconciles_open_pull_request_membership_and_status(self):
        missing = self.findings(
            project=[project_item()],
            pull_requests=[pull_request()],
        )
        stale = self.findings(
            project=[project_item(), project_pull_request(status="Todo")],
            pull_requests=[pull_request()],
        )
        healthy = self.findings(
            project=[project_item(), project_pull_request()],
            pull_requests=[pull_request()],
        )

        self.assertIn("open pull requests missing from project: [2]", missing)
        self.assertIn("open pull requests without In Progress status: [2]", stale)
        self.assertEqual([], healthy)


class ProjectItemsTest(unittest.TestCase):
    def test_fetches_every_project_item_beyond_the_old_500_item_cap(self):
        calls = []
        expected = [{"id": index} for index in range(527)]
        with patch.object(checker, "gh_json", side_effect=lambda *args: calls.append(args) or {
            "totalCount": 527, "items": expected,
        }):
            actual = checker.fetch_project_items(527)

        self.assertEqual(expected, actual)
        self.assertIn("527", calls[0])

    def test_retries_when_the_project_grows_during_pagination(self):
        calls = []
        responses = iter([
            {"totalCount": 528, "items": [{"id": index} for index in range(527)]},
            {"totalCount": 528, "items": [{"id": index} for index in range(528)]},
        ])
        with patch.object(
            checker, "gh_json", side_effect=lambda *args: calls.append(args) or next(responses)
        ):
            actual = checker.fetch_project_items(527)

        self.assertEqual(528, len(actual))
        self.assertIn("527", calls[0])
        self.assertIn("528", calls[1])

    def test_fails_loudly_if_the_cli_returns_a_partial_page(self):
        with patch.object(checker, "gh_json", return_value={
            "totalCount": 527,
            "items": [{"id": index} for index in range(500)],
        }):
            with self.assertRaisesRegex(RuntimeError, "stopped at 500 of 527"):
                checker.fetch_project_items(527)

    def test_fails_loudly_if_the_project_keeps_growing(self):
        responses = iter([
            {"totalCount": total, "items": [{"id": index} for index in range(total - 1)]}
            for total in (528, 529, 530)
        ])
        with patch.object(checker, "gh_json", side_effect=lambda *args: next(responses)):
            with self.assertRaisesRegex(RuntimeError, "kept growing during 3"):
                checker.fetch_project_items(527)


if __name__ == "__main__":
    unittest.main()
