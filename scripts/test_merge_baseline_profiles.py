"""Tests for scripts/merge-baseline-profiles.py."""

import importlib.util
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location(
    "merge_baseline_profiles", Path(__file__).with_name("merge-baseline-profiles.py"))
merge = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(merge)


class MergeBaselineProfilesTest(unittest.TestCase):
    """Rule identity, flag union and ordering of merged profiles."""

    def test_flags_of_one_identity_are_combined_once(self):
        """A rule seen in several journeys keeps one line with every flag."""
        merged = merge.merge_rules([
            "HLexample/App;->launch()V",
            "SPLexample/App;->launch()V",
            "Lexample/App;",
            "Lexample/App;",
        ])
        self.assertEqual(merged, ["Lexample/App;", "HSPLexample/App;->launch()V"])

    def test_class_rule_sorts_before_its_members(self):
        """Each class rule precedes its member rules, and blank lines are dropped."""
        merged = merge.merge_rules([
            "PLb/B;->run()V",
            "",
            "Lb/B;",
            "HLa/A;->go()V",
        ])
        self.assertEqual(merged, ["HLa/A;->go()V", "Lb/B;", "PLb/B;->run()V"])


if __name__ == "__main__":
    unittest.main()
