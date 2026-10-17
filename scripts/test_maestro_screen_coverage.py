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
            (root / 'screen.kt').write_text('fun Screen() = Unit')
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

    def test_default_remaining_keeps_limits_and_cannot_excuse_an_unmapped_screen(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'config').mkdir()
            (root / '.maestro').mkdir()
            (root / '.maestro/main.yaml').write_text('appId: test\n---\n- assertVisible: Real screen\n')
            mapping = root / 'config/maestro-screen-assertions.json'
            (root / 'screen.kt').write_text('fun Screen() = Unit')
            screen = {'source': 'screen.kt', 'symbol': 'Screen'}
            row = {**screen, 'bindings': [{'case': 'case', 'selector': {'text': 'Real screen'}, 'scope': 'Chrome'}]}
            cases = {'case': {'flow': '.maestro/main.yaml'}}
            def save(value):
                mapping.write_text(json.dumps({'schema': 1, 'default_remaining': 'Unproven', 'screens': [value]}))
            save(row)
            proof = screen_bindings(root, [screen], cases)[0]
            self.assertEqual(proof['remaining'], 'Unproven')
            self.assertFalse(proof['execution_verified'])
            save({**row, 'remaining': 'Specific platform limits'})
            self.assertEqual(screen_bindings(root, [screen], cases)[0]['remaining'], 'Specific platform limits')
            save({**screen, 'bindings': []})
            with self.assertRaisesRegex(ValueError, 'Unmapped screen'):
                screen_bindings(root, [screen], cases)
            mapping.write_text(json.dumps({'schema': 1, 'default_remaining': True, 'screens': [row]}))
            with self.assertRaisesRegex(ValueError, 'Invalid default remaining'):
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

    def test_translation_faults_require_durable_native_prerequisites_after_log_clear(self):
        """UI callbacks survive log clearing only with retained actual package and route observations."""
        for suffix in ('missing', 'unverified', 'detection', 'model', 'engine'):
            scenario = f'modal-translation-{suffix}'
            case = {'postcondition': 'presentation-checked', 'presentation': scenario,
                    'presentation_actions': ['dismiss']}
            proof = {'scenario': scenario, 'callbacks': ['dismiss'], 'verified': True}
            with self.subTest(scenario=scenario):
                with self.assertRaisesRegex(ValueError, 'prerequisites'):
                    qualify_presentation(case, {'presentation': proof})
                acquisition = {'package': 'dev.davidv.translator', 'enginePackageAbsent': True,
                               'acquisitionRoute': 'GITHUB_RELEASE'}
                # No log field exists: the generation-bound native receipt is the proof.
                qualify_presentation(case, {'presentation': {**proof, 'translationAcquisition': acquisition}})
                for invalid in (None, {}, {**acquisition, 'enginePackageAbsent': False},
                                {**acquisition, 'enginePackageAbsent': 1},
                                {**acquisition, 'package': 'other.package'},
                                {**acquisition, 'acquisitionRoute': 'INSTALLED_ENGINE'}):
                    with self.assertRaisesRegex(ValueError, 'prerequisites'):
                        qualify_presentation(case, {'presentation': {**proof, 'translationAcquisition': invalid}})

    def test_search_menu_requires_dismissal_before_the_selected_action(self):
        """The production dropdown closes before dispatch; UI success alone missed this order."""
        root = Path(__file__).resolve().parents[1]
        cases = json.loads((root / 'config/maestro-runtime-cases.json').read_text())['cases']
        for name, action in [('category', 'category-selected'), ('clear', 'clear-handoff')]:
            case = cases[f'presentation-extra-search-menu-{name}']
            proof = {'scenario': case['presentation'], 'callbacks': ['dismiss', action], 'verified': True}
            qualify_presentation(case, {'presentation': proof})
            for callbacks in [[action], [action, 'dismiss'], ['dismiss', 'dismiss', action], ['dismiss']]:
                with self.assertRaises(ValueError):
                    qualify_presentation(case, {'presentation': {**proof, 'callbacks': callbacks}})

    def test_copy_presentations_reject_stale_or_uncleared_clipboard_receipts(self):
        root = Path(__file__).resolve().parents[1]
        cases = json.loads((root / 'config/maestro-runtime-cases.json').read_text())['cases']
        names = ['feedback-add-copyable', 'feedback-chat-copyable', 'feedback-person-copyable',
                 'text-dialog-copy', 'surface-profile-qr-copy']
        fields = ['clipboardBaselineCleared', 'clipboardVerified', 'clipboardCleared', 'clipboardDialogCleanupVerified']
        for scenario in names:
            case = cases[f'presentation-{scenario}']
            proof = {'scenario': scenario, 'callbacks': case['presentation_actions'], 'verified': True,
                     **dict.fromkeys(fields, True)}
            qualify_presentation(case, {'presentation': proof})
            for field in fields:
                for value in [False, None, 1, 'true']:
                    with self.assertRaisesRegex(ValueError, 'fresh presentation clipboard'):
                        qualify_presentation(case, {'presentation': {**proof, field: value}})
                missing = {k: v for k, v in proof.items() if k != field}
                with self.assertRaises(ValueError):
                    qualify_presentation(case, {'presentation': missing})

    def test_license_recovery_requires_retry_selection_and_return_in_order(self):
        root = Path(__file__).resolve().parents[1]
        cases = json.loads((root / 'config/maestro-runtime-cases.json').read_text())['cases']
        case = cases['presentation-extra-licenses-failure-retry']
        actions = ['retry', 'dismiss']
        proof = {'scenario': case['presentation'], 'callbacks': actions, 'verified': True}
        qualify_presentation(case, {'presentation': proof})
        for callbacks in [actions[1:], actions[:-1], list(reversed(actions)),
                          ['retry', 'retry', *actions[1:]], ['dismiss']]:
            with self.assertRaises(ValueError):
                qualify_presentation(case, {'presentation': {**proof, 'callbacks': callbacks}})

    def test_opener_label_cannot_certify_a_surface_and_order_survives_nested_flows(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'config').mkdir()
            (root / '.maestro').mkdir()
            child = root / '.maestro/open.yaml'
            child.write_text('appId: test\n---\n- tapOn: Settings\n- assertVisible: Settings\n')
            flow = root / '.maestro/main.yaml'
            flow.write_text('appId: test\n---\n- runFlow: open.yaml\n- assertVisible:\n    id: settings-home-content\n')
            selectors, _ = flow_assertions(flow, root)
            self.assertNotIn({'text': 'Settings'}, selectors)
            (root / 'settings.kt').write_text('fun SettingsScreen() = Unit')
            screen = {'source': 'settings.kt', 'symbol': 'SettingsScreen'}
            binding = {'case': 'case', 'selector': {'id': 'settings-home-content'},
                       'opened_by': {'text': 'Settings'}, 'scope': 'Opened settings content'}
            mapping = root / 'config/maestro-screen-assertions.json'
            mapping.write_text(json.dumps({'schema': 1, 'screens': [{**screen, 'bindings': [binding]}]}))
            cases = {'case': {'flow': '.maestro/main.yaml'}}
            self.assertEqual(len(screen_bindings(root, [screen], cases)), 1)
            flow.write_text('appId: test\n---\n- assertVisible:\n    id: settings-home-content\n- runFlow: open.yaml\n')
            with self.assertRaisesRegex(ValueError, 'precedes'):
                screen_bindings(root, [screen], cases)
            mapping.write_text(json.dumps({'schema': 1, 'screens': [{**screen, 'bindings': [
                {**binding, 'selector': {'text': 'Settings'}}]}]}))
            with self.assertRaisesRegex(ValueError, 'not directly asserted'):
                screen_bindings(root, [screen], cases)

    def test_discovered_modal_requires_explicit_entry_and_real_opening_order(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'config').mkdir()
            (root / '.maestro').mkdir()
            (root / 'owner.kt').write_text('fun OddOwner() { Dialog(onDismissRequest = {}) {} }')
            flow = root / '.maestro/main.yaml'
            mapping = root / 'config/maestro-screen-assertions.json'
            screen = {'source': 'owner.kt', 'symbol': 'OddOwner'}
            case = {'case': {'flow': '.maestro/main.yaml'}}
            binding = {'case': 'case', 'selector': {'id': 'modal.done', 'enabled': True},
                       'entry_assertion': {'id': 'modal.body'}, 'opened_by': {'text': 'Open'},
                       'scope': 'Actual modal body and edited control'}
            def check(commands, value, passes=False):
                flow.write_text('appId: test\n---\n' + commands)
                mapping.write_text(json.dumps({'schema': 1, 'screens': [{**screen, 'bindings': [value]}]}))
                if passes:
                    self.assertEqual(len(screen_bindings(root, [screen], case)), 1)
                else:
                    with self.assertRaises(ValueError):
                        screen_bindings(root, [screen], case)
            entry = '- assertVisible:\n    id: modal.body\n'
            done = '- assertVisible:\n    id: modal.done\n    enabled: true\n'
            opened = '- tapOn: Open\n' + entry + '- tapOn: Field\n' + done
            check(opened, binding, True)
            check(opened, {key: value for key, value in binding.items() if key != 'opened_by'})
            check(entry + opened, binding)
            check(opened, {**binding, 'opened_by': {'text': 'Field'}})
            check(opened, {**binding, 'entry_assertion': {'id': '${ENTRY}'}})
            check('- tapOn:\n    text: Open\n    optional: true\n' + entry + done, binding)
            check('- runFlow:\n    when:\n      visible: Parent\n    commands:\n      - tapOn: Open\n' + entry + done,
                  binding)
            initial = {key: value for key, value in binding.items() if key != 'opened_by'}
            initial['visible_on_entry'] = True
            check(entry + '- tapOn: Field\n' + done, initial, True)
            check(opened, initial)
            check(entry + done, {**initial, 'visible_on_entry': 'true'})
            check(entry + done, {**initial, 'opened_by': {'text': 'Open'}})
            child = root / '.maestro/open.yaml'
            child.write_text('appId: test\n---\n- longPressOn: Message\n' + entry)
            check('- runFlow: open.yaml\n' + done, {**binding, 'opened_by': {'longPressOn': {'text': 'Message'}}}, True)
            check('- back\n' + entry + done, {**binding, 'opened_by': {'back': True}}, True)

    def test_callback_free_screens_reject_host_back_dispatch_credit(self):
        root = Path(__file__).resolve().parents[1]
        cases = json.loads((root / 'config/maestro-runtime-cases.json').read_text())['cases']
        host = (root / 'app/src/androidTest/java/dev/ipf/whitenoise/android/maestro/MaestroPresentationFixture.kt').read_text()
        self.assertNotIn('BackHandler', host)
        for name in ('startup-loading', 'startup-destination-loading', 'tts-return', 'selection-quick-cue',
                     'extra-message-pending-loading', 'extra-message-footer', 'startup-terminal',
                     'startup-projection-loading', 'startup-projection-terminal', 'selection-conversation-loading',
                     'surface-onboarding-welcome', 'surface-onboarding-signin', 'surface-onboarding-signup',
                     'extra-message-pending-failed', 'surface-rejoin-cancel', 'extra-message-mention-cancel',
                     'extra-notification-scope-cancel', 'extra-native-library-menu',
                     'update-reminder-idle', 'update-reminder-disabled'):
            case = cases['presentation-' + name]
            self.assertEqual(case['postcondition'], 'presentation-observed')
            self.assertEqual(case['presentation_actions'], [])
            proof = {'scenario': name, 'callbacks': [], 'observationOnly': True, 'verified': True}
            qualify_presentation(case, {'presentation': proof})
            with self.assertRaises(ValueError):
                qualify_presentation(case, {'presentation': {**proof, 'callbacks': ['dismiss']}})

    def test_image_close_uses_production_callback_and_failure_proves_retry_without_dismiss(self):
        root = Path(__file__).resolve().parents[1]
        cases = json.loads((root / 'config/maestro-runtime-cases.json').read_text())['cases']
        failure = cases['presentation-nostr-image-failure']
        self.assertEqual(failure['presentation_actions'], [])
        self.assertEqual(failure['postcondition'], 'presentation-observed')
        proof = {'scenario': 'nostr-image-failure', 'callbacks': [], 'observationOnly': True,
                 'verified': True, 'nostrImageLoads': 2}
        qualify_presentation(failure, {'presentation': proof})
        for value in (None, False, True, '2', 1, 3, 2.0):
            with self.subTest(value=value), self.assertRaises(ValueError):
                qualify_presentation(failure, {'presentation': {**proof, 'nostrImageLoads': value}})
        with self.assertRaises(ValueError):
            qualify_presentation(failure, {'presentation': {**proof, 'callbacks': ['dismiss']}})
        source = (root / 'app/src/androidTest/java/dev/ipf/whitenoise/android/maestro/'
                  'MaestroNostrPresentation.kt').read_text()
        self.assertIn('onFullscreenDismiss = { fixture.finish("dismiss") }', source)
        success = (root / '.maestro/runtime/presentation-nostr-image.yaml').read_text()
        self.assertIn('- tapOn: Close\n- assertVisible:', success)
        self.assertNotIn('- back', success)
        failed = (root / '.maestro/runtime/presentation-nostr-image-failure.yaml').read_text()
        self.assertNotIn('presentation.complete', failed)
        self.assertNotIn('- back', failed)

    def test_current_dictation_recovery_cases_replace_removed_popups(self):
        root = Path(__file__).resolve().parents[1]
        cases = json.loads((root / 'config/maestro-runtime-cases.json').read_text())['cases']
        for name in ('partial-cancel', 'partial-send', 'partial-disabled', 'discard', 'keep-later'):
            self.assertNotIn('presentation-dictation-' + name, cases)
        case = cases['presentation-dictation-draft-send']
        expected = ['draft-recovered', 'send-handoff', 'cleared-editor-verified']
        proof = {'scenario': case['presentation'], 'callbacks': expected, 'verified': True}
        qualify_presentation(case, {'presentation': proof})
        for callbacks in [expected[:1], expected[:-1], expected[::-1], ['dismiss']]:
            with self.assertRaises(ValueError):
                qualify_presentation(case, {'presentation': {**proof, 'callbacks': callbacks}})
        status = cases['presentation-dictation-status-closing']
        proof = {'scenario': status['presentation'], 'callbacks': [], 'observationOnly': True, 'verified': True}
        qualify_presentation(status, {'presentation': proof})
        with self.assertRaises(ValueError):
            qualify_presentation(status, {'presentation': {**proof, 'callbacks': ['dismiss']}})

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
