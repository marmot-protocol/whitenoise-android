#!/usr/bin/env python3

import unittest
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).parent))
import check_github_triage as checker


def issue(number=1, issue_type="Bug"):
    return {
        "number": number,
        "url": f"https://github.com/marmot-protocol/whitenoise-android/issues/{number}",
        "issueType": {"name": issue_type} if issue_type is not None else None,
        "labels": [],
    }


def project_item(number=1, **overrides):
    item = {
        "content": {"number": number},
        "priority": "P1",
        "area": "Messaging",
        "triage health": "Ready",
        "status": "Todo",
    }
    item.update(overrides)
    return item


class FindingsTest(unittest.TestCase):
    def findings(self, issues=None, project=None):
        return checker.findings(
            {"closed": False, "public": True},
            {"closed": True},
            issues or [issue()],
            project or [project_item()],
            set(),
        )

    def test_accepts_documented_values(self):
        issues = [issue(number, issue_type) for number, issue_type in enumerate(
            sorted(checker.ALLOWED_ISSUE_TYPES), start=1
        )]
        project = []
        priorities = sorted(checker.ALLOWED_ITEM_FIELD_VALUES["priority"])
        health_values = sorted(checker.ALLOWED_ITEM_FIELD_VALUES["triage health"])
        statuses = sorted(checker.ALLOWED_ITEM_FIELD_VALUES["status"])
        for index, current_issue in enumerate(issues):
            project.append(project_item(
                current_issue["number"],
                priority=priorities[index % len(priorities)],
                **{
                    "triage health": health_values[index % len(health_values)],
                    "status": statuses[index % len(statuses)],
                },
            ))

        self.assertEqual([], self.findings(issues, project))

    def test_rejects_unsupported_values(self):
        problems = self.findings(
            [issue(issue_type="Incident")],
            [project_item(**{
                "priority": "Urgent",
                "triage health": "Healthy",
                "status": "Canceled",
            })],
        )

        self.assertIn("open issues with invalid native type: [1]", problems)
        self.assertIn("open issues with invalid priority: [1]", problems)
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


if __name__ == "__main__":
    unittest.main()
