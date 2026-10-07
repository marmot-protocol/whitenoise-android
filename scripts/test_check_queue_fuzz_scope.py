import unittest
from pathlib import Path
from unittest.mock import patch
from scripts.check_queue_fuzz_scope import selected
from scripts.check_fuzz_pr_triggers import pr_paths

WORKFLOW = (Path(__file__).resolve().parents[1] / '.github/workflows/fuzz-pr.yml').read_text()


def raw_paths(paths):
    return b''.join((f':100644 100644 {"a" * 40} {"b" * 40} M\0{path}\0').encode() for path in paths)


class QueueFuzzScopeTest(unittest.TestCase):
    def test_every_literal_trigger_and_nested_glob_runs(self):
        for path in pr_paths(WORKFLOW):
            path = path.replace('**', 'nested/deep/Parser.kt')
            with self.subTest(path=path), patch('scripts.check_queue_fuzz_scope.complete_diff', return_value=raw_paths([path])):
                self.assertTrue(selected('merge_group', 'a', 'b', WORKFLOW))

    def test_known_unrelated_changes_skip_but_missing_diff_runs(self):
        for paths, expected in [(['docs/README.md'], False),
                                (['app/src/main/java/dev/ipf/whitenoise/android/ui/Theme.kt'], False),
                                (None, True), (['fuzz/src/new/file.kt'], True)]:
            with patch('scripts.check_queue_fuzz_scope.complete_diff', return_value=None if paths is None else raw_paths(paths)):
                self.assertEqual(selected('merge_group', 'a', 'b', WORKFLOW), expected)

    def test_malformed_diff_runs(self):
        for raw in [b'broken', b'', raw_paths(['fuzz/a']).replace(b'100644', b'120000'), raw_paths(['../bad'])]:
            with patch('scripts.check_queue_fuzz_scope.complete_diff', return_value=raw):
                self.assertTrue(selected('merge_group', 'a', 'b', WORKFLOW))

    def test_other_events_keep_existing_native_path_selection(self):
        with patch('scripts.check_queue_fuzz_scope.complete_diff', side_effect=AssertionError):
            self.assertTrue(selected('pull_request', None, None, WORKFLOW))

    def test_unknown_or_malformed_pattern_runs(self):
        for workflow in [WORKFLOW.replace("'fuzz/**'", "'fuzz/[ab]/**'"), 'on: {}']:
            self.assertTrue(selected('merge_group', 'a', 'b', workflow))


if __name__ == '__main__':
    unittest.main()
