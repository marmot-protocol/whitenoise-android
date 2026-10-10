"""Regression cases for false screen credit and presentation receipt substitution."""
import json
from pathlib import Path
import tempfile
import unittest

from scripts.maestro_screen_coverage import flow_assertions, screen_bindings, executed_screens
from scripts.maestro_runtime import presentation_arguments, qualify_presentation


class ScreenProofTest(unittest.TestCase):
    def test_literal_parent_scope_keeps_child_identity_without_optional_credit(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / '.maestro').mkdir()
            flow = root / '.maestro/main.yaml'
            flow.write_text(
                'appId: test\n---\n'
                '- assertVisible:\n    text: "Color #15803D"\n    childOf:\n      id: other.picker\n'
                '- assertVisible:\n    text: "Color #15803D"\n    childOf:\n      id: ${PARENT}\n'
                '- assertVisible:\n    text: "Color #15803D"\n    optional: true\n'
                '- runFlow:\n    when:\n      visible: Other\n    commands:\n'
                '      - assertVisible:\n          text: Optional child\n')
            selectors, hashes = flow_assertions(flow, root)
            self.assertEqual(selectors, [{'text': 'Color #15803D', 'childOf': {'id': 'other.picker'}}])
            self.assertEqual(set(hashes), {'.maestro/main.yaml'})

    def test_substituted_selectors_grant_no_literal_screen_credit(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / '.maestro').mkdir()
            (root / '.maestro/child.yaml').write_text(
                'appId: test\n---\n- assertVisible: ${TITLE}\n- assertVisible: Actual child\n')
            flow = root / '.maestro/main.yaml'
            flow.write_text(
                'appId: test\n---\n- assertVisible: Actual parent\n'
                '- assertVisible:\n    id: ${ID}\n'
                '- assertVisible:\n    enabled: false\n    containsChild:\n      text: ${LABEL}\n'
                '- runFlow:\n    file: child.yaml\n    env:\n      TITLE: Dynamic child\n')
            selectors, hashes = flow_assertions(flow, root)
            self.assertEqual(selectors, [{'text': 'Actual parent'}, {'text': 'Actual child'}])
            self.assertEqual(len(hashes), 2)

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
        hashes = {'.maestro/real.yaml': 'b' * 64}
        screens = [{'source': 'screen.kt', 'symbol': 'Screen', 'bindings': [{'case': 'real', 'flow_sha256': hashes}]}]
        source = 'a' * 40
        for outcomes in [[], [{'case': 'other', 'passed': True}], [{'case': 'real', 'passed': False}],
                         [{'case': 'real', 'passed': True, 'failure': 'Native cleanup failed'}],
                         [{'case': 'real', 'passed': True}],
                         [{'case': 'real', 'passed': True, 'flow_sha256': {'.maestro/real.yaml': 'c' * 64}}],
                         [{'case': 'real', 'passed': True, 'flow_sha256': hashes, 'not_run': True}]]:
            proof = executed_screens(screens, {'source_sha': source, 'errors': [], 'results': outcomes}, source)
            self.assertFalse(proof[0]['execution_verified'])
        proof = executed_screens(screens, {'source_sha': source, 'errors': [], 'results': [{'case': 'real', 'passed': True, 'flow_sha256': hashes}]}, source)
        self.assertTrue(proof[0]['execution_verified'])
        for campaign in [{'source_sha': 'b' * 40}, {'source_sha': source, 'errors': ['Missing shard']},
                         {'source_sha': source, 'results': [{'case': 'real'}, {'case': 'real'}]}]:
            with self.assertRaises(ValueError):
                executed_screens(screens, campaign, source)

    def test_symlinked_flow_directory_cannot_substitute_assertions(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / '.maestro').mkdir()
            (root / '.maestro/real').mkdir()
            (root / '.maestro/real/main.yaml').write_text('appId: test\n---\n- assertVisible: Actual\n')
            (root / '.maestro/link').symlink_to(root / '.maestro/real', target_is_directory=True)
            with self.assertRaisesRegex(ValueError, 'symlink'):
                flow_assertions(root / '.maestro/link/main.yaml', root)

    def test_malformed_presentation_cannot_run_without_native_verification(self):
        for scenario in [None, '', 4, [], 'other,scenario', 'unsafe/path']:
            with self.assertRaises(ValueError):
                presentation_arguments({'postcondition': 'presentation-checked', 'presentation': scenario,
                                        'presentation_actions': ['dismiss']})

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

    def test_only_non_cancellable_overlays_admit_observation_without_dispatch(self):
        case = {'postcondition': 'presentation-observed', 'presentation': 'extra-wait-signout',
                'presentation_actions': []}
        self.assertEqual(presentation_arguments(case)[-1], 'none')
        proof = {'scenario': 'extra-wait-signout', 'callbacks': [], 'observationOnly': True, 'verified': True}
        qualify_presentation(case, {'presentation': proof})
        for changed in [{**case, 'presentation': 'update-confirm'},
                        {**case, 'presentation_actions': ['dismiss']},
                        {**case, 'postcondition': 'presentation-checked'}]:
            with self.assertRaises(ValueError):
                presentation_arguments(changed)
        for changed in [{**proof, 'observationOnly': False}, {**proof, 'callbacks': ['dismiss']},
                        {**proof, 'scenario': 'extra-wait-wipe'}, {**proof, 'verified': False}]:
            with self.assertRaises(ValueError):
                qualify_presentation(case, {'presentation': changed})



if __name__ == '__main__':
    unittest.main()
