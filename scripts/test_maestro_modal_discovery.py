"""Regressions for modal UI that suffix-only screen discovery silently omitted."""

import unittest
from pathlib import Path
import tempfile

from scripts.maestro_modal_discovery import modal_owners, MODAL_CONSTRUCTORS
from scripts.maestro_coverage import screen_catalog


class ModalDiscoveryTests(unittest.TestCase):
    def test_production_primitive_wrappers_must_be_in_the_constructor_audit(self):
        root = Path(__file__).resolve().parents[1] / 'app/src/main/java/dev/ipf/whitenoise/android'
        wrappers = set()
        for source in ('ui/common/WhiteNoiseDialogs.kt', 'ui/conversation/PlaybackDialog.kt'):
            wrappers.update(modal_owners((root / source).read_text()))
        self.assertGreaterEqual(wrappers, {'ChoiceDialog', 'SpeechChoiceDialog', 'PlaybackDialog'})
        self.assertFalse(wrappers - MODAL_CONSTRUCTORS, 'Production modal wrapper escapes caller discovery')
        for wrapper in wrappers:
            self.assertEqual(modal_owners('@Composable fun UnsuffixedContent() { ' + wrapper + '() }'),
                             {'UnsuffixedContent'})

    def test_real_gesture_picker_owner_requires_a_direct_binding(self):
        root = Path(__file__).resolve().parents[1]
        source = 'app/src/main/java/dev/ipf/whitenoise/android/ui/settings/GestureSettingsScreen.kt'
        owners = modal_owners((root / source).read_text())
        self.assertIn('GestureSettingsContent', owners)
        with tempfile.TemporaryDirectory() as folder:
            synthetic = Path(folder)
            target = synthetic / source
            target.parent.mkdir(parents=True)
            target.write_text((root / source).read_text())
            screens = screen_catalog(synthetic, {'ui': [{'source': source, 'test_ids': ['SET-032']}]}, {})
            self.assertIn('GestureSettingsContent', {row['symbol'] for row in screens})

    def test_plural_lowercase_and_same_file_children_are_not_hidden(self):
        text = ('@Composable fun ParentScreen() {}\n'
                '@Composable private fun TimestampOverlays() { Popup({}) {} }\n'
                '@Composable internal fun ttsSentenceAccessibilityActions(): List<Action> {\n'
                ' if (choosing) { AlertDialog(onDismissRequest = {}) }\n'
                ' return emptyList()\n}\n'
                '@Composable private fun LocalDeleteDetails() { WhiteNoiseAlertDialog() }\n')
        self.assertEqual(modal_owners(text),
                         {'TimestampOverlays', 'ttsSentenceAccessibilityActions', 'LocalDeleteDetails'})
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = 'app/src/main/java/dev/ipf/whitenoise/android/ui/Disclosure.kt'
            path = root / source
            path.parent.mkdir(parents=True)
            path.write_text(text)
            with self.assertRaisesRegex(ValueError, 'no maintained source'):
                screen_catalog(root, {}, {})
            screens = screen_catalog(root, {'ui': [{'source': source, 'test_ids': ['MSG-002']}]}, {})
            self.assertEqual({row['symbol'] for row in screens},
                             {'ParentScreen', 'TimestampOverlays', 'ttsSentenceAccessibilityActions',
                              'LocalDeleteDetails'})
            self.assertTrue(all(row['execution_verified'] is False for row in screens))

    def test_literals_comments_and_constructor_declarations_grant_no_modal_credit(self):
        text = ('// fun Phantom() { AlertDialog() }\n'
                '/* outer /* nested AlertDialog() */ Popup() */\n'
                'val example = "AlertDialog() and fun Quoted() { Popup() }"\n'
                'val raw = """WhiteNoiseModalBottomSheet()"""\n'
                '@Composable fun WhiteNoiseAlertDialog() {}\n'
                '@Composable fun Scope.Popup() {}\n')
        self.assertEqual(modal_owners(text), set())

    def test_expression_factory_cannot_borrow_the_next_function_body(self):
        text = ('private fun colors() = Colors()\n'
                '@Composable fun ComposerContent() { DropdownMenu(expanded = true) {} }\n'
                'fun Modifier.actions() = composed { AlertDialog() }\n')
        self.assertEqual(modal_owners(text), {'ComposerContent', 'Modifier.actions'})

    def test_innermost_function_owns_its_dialog_and_defaults_keep_their_owner(self):
        text = ('@Composable fun ParentContent() {\n'
                ' @Composable fun ChildDetails() { AlertDialog() }\n'
                ' ChildDetails()\n}\n'
                '@Composable fun ScannerContent(scanner: @Composable () -> Unit = { Dialog() }) {}\n')
        self.assertEqual(modal_owners(text), {'ChildDetails', 'ScannerContent'})

    def test_local_value_helpers_do_not_own_later_parent_menus(self):
        text = ('@Composable fun ProfileQrSheet() {\n'
                ' fun ownsSheet() = account == "fixture"\n'
                ' if (sharing) { WhiteNoiseDropdownMenu() }\n}\n'
                '@Composable fun FileLibraryRow() {\n'
                ' suspend fun fetchFile() = cached()\n'
                '     ?: load()\n'
                ' Column { WhiteNoiseDropdownMenu() }\n}\n')
        self.assertEqual(modal_owners(text), {'ProfileQrSheet', 'FileLibraryRow'})

    def test_unowned_or_unbalanced_modal_source_fails_closed(self):
        with self.assertRaisesRegex(ValueError, 'no audited named owner'):
            modal_owners('val content = @Composable { AlertDialog() }')
        with self.assertRaisesRegex(ValueError, 'Unbalanced'):
            modal_owners('@Composable fun ChildContent() { AlertDialog()')


if __name__ == '__main__':
    unittest.main()
