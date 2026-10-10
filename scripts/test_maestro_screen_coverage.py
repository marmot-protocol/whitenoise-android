"""Regression cases for false screen credit and presentation receipt substitution."""
import json
from pathlib import Path
import tempfile
import unittest

from scripts.maestro_screen_coverage import flow_assertions, screen_bindings, executed_screens
from scripts.maestro_runtime import presentation_arguments, qualify_presentation


class ScreenProofTest(unittest.TestCase):
    def test_only_unconditional_assertions_grant_credit(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / '.maestro').mkdir()
            (root / '.maestro/child.yaml').write_text('appId: test\n---\n- assertVisible: Child\n')
            flow = root / '.maestro/main.yaml'
            flow.write_text('appId: test\n---\n- assertVisible: Parent\n- runFlow: child.yaml\n- runFlow:\n    when:\n      visible: Optional\n    file: child.yaml\n- assertVisible:\n    text: Maybe\n    optional: true\n')
            selectors, hashes = flow_assertions(flow, root)
            self.assertEqual(selectors, [{'text': 'Parent'}, {'text': 'Child'}])
            self.assertEqual(len(hashes), 2)
            flow.write_text('appId: test\n---\n- runFlow: main.yaml\n')
            with self.assertRaisesRegex(ValueError, 'Recursive'):
                flow_assertions(flow, root)

    def test_new_removed_duplicate_and_unasserted_screens_fail(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'config').mkdir()
            (root / '.maestro').mkdir()
            (root / '.maestro/main.yaml').write_text('appId: test\n---\n- assertVisible: Real screen\n')
            mapping = root / 'config/maestro-screen-assertions.json'
            screen = {'source': 'screen.kt', 'symbol': 'Screen'}
            row = {**screen, 'bindings': [{'case': 'case', 'selector': {'text': 'Real screen'}, 'scope': 'Actual screen chrome'}]}
            cases = {'case': {'flow': '.maestro/main.yaml'}}
            def save(rows):
                mapping.write_text(json.dumps({'schema': 1, 'screens': rows}))
            save([row])
            self.assertEqual(len(screen_bindings(root, [screen], cases)), 1)
            for rows in [[], [row, row], [{**row, 'symbol': 'Removed'}],
                         [{**row, 'bindings': [{'case': 'case', 'selector': {'text': 'Unasserted'}, 'scope': 'Chrome'}]}],
                         [{**screen, 'bindings': []}]]:
                save(rows)
                with self.assertRaises(ValueError):
                    screen_bindings(root, [screen], cases)

    def test_failed_missing_and_foreign_execution_get_no_screen_credit(self):
        screens = [{'source': 'screen.kt', 'symbol': 'Screen', 'bindings': [{'case': 'real'}]}]
        source = 'a' * 40
        for outcomes in [[], [{'case': 'other', 'passed': True}], [{'case': 'real', 'passed': False}],
                         [{'case': 'real', 'passed': True, 'failure': 'Native cleanup failed'}]]:
            proof = executed_screens(screens, {'source_sha': source, 'errors': [], 'results': outcomes}, source)
            self.assertFalse(proof[0]['execution_verified'])
        proof = executed_screens(screens, {'source_sha': source, 'errors': [], 'results': [{'case': 'real', 'passed': True}]}, source)
        self.assertTrue(proof[0]['execution_verified'])
        for campaign in [{'source_sha': 'b' * 40}, {'source_sha': source, 'errors': ['Missing shard']},
                         {'source_sha': source, 'results': [{'case': 'real'}, {'case': 'real'}]}]:
            with self.assertRaises(ValueError):
                executed_screens(screens, campaign, source)

    def test_presentation_callback_receipt_is_exact_and_independent_of_labels(self):
        case = {'postcondition': 'presentation-checked', 'presentation': 'update-confirm', 'presentation_actions': ['download']}
        self.assertIn('presentationScenario', presentation_arguments(case))
        actual = {'scenario': 'update-confirm', 'callbacks': ['download'], 'verified': True}
        qualify_presentation(case, {'presentation': actual})
        for invalid in [{}, {**actual, 'scenario': 'other'}, {**actual, 'verified': False},
                        {**actual, 'callbacks': []}, {**actual, 'callbacks': ['install']},
                        {**actual, 'callbacks': ['download', 'download']}]:
            with self.assertRaises(ValueError):
                qualify_presentation(case, {'presentation': invalid})


if __name__ == '__main__':
    unittest.main()
