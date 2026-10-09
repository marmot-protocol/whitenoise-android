import unittest
from collections import Counter
import importlib.util
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("scroll_edge_check", Path(__file__).with_name("check_scroll_edge_fades.py"))
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
calls = MODULE.calls
inventory_errors = MODULE.inventory_errors


class ScrollEdgeInventoryTests(unittest.TestCase):
    def test_repository_coverage(self):
        self.assertEqual([], inventory_errors())

    def test_native_menu_binding_accepts_only_its_actual_state_and_known_stable_option(self):
        for call in ('.scrollEdgeFade(menuScrollState)',
                     '.scrollEdgeFade(menuScrollState, stableRenderTarget = true)',
                     '.scrollEdgeFade(\n menuScrollState,\n stableRenderTarget = true\n)'):
            with self.subTest(call=call):
                self.assertTrue(MODULE.menu_scroll_state_bound('scrollState = menuScrollState; ' + call))
        for source in ('scrollState = otherState; .scrollEdgeFade(menuScrollState)',
                       'scrollState = menuScrollState; .scrollEdgeFade(otherState)',
                       'scrollState = menuScrollState; .scrollEdgeFade(menuScrollState, fadeEnabled = false)',
                       'scrollState = menuScrollState; // .scrollEdgeFade(menuScrollState)',
                       'scrollState = menuScrollState; val label = ".scrollEdgeFade(menuScrollState)"'):
            with self.subTest(source=source):
                self.assertFalse(MODULE.menu_scroll_state_bound(source))

    def test_qualified_and_aliased_raw_calls_cannot_hide(self):
        source = '''import androidx.compose.foundation.lazy.LazyColumn as HiddenList
        HiddenList { item { Text("sample") } }
        androidx.compose.foundation.lazy.LazyColumn(state = state) {}
        Modifier.verticalScroll(state)'''
        self.assertEqual(Counter(LazyColumn=2, verticalScroll=1), calls(source))

    def test_declarations_comments_and_strings_are_not_viewports(self):
        source = '''fun Modifier.fadingVerticalScroll(state: ScrollState) = this
        // LazyColumn {}
        val label = "LazyColumn( and verticalScroll("
        /* androidx.compose.foundation.lazy.LazyColumn() */
        SettingsList { item {} }'''
        self.assertEqual(Counter(SettingsList=1), calls(source))

    def test_shared_container_aliases_and_trailing_lambdas_are_covered(self):
        self.assertEqual(Counter(WhiteNoiseLazyColumn=1), calls('''
        import dev.ipf.whitenoise.android.ui.common.WhiteNoiseLazyColumn as StyledList
        StyledList { item {} }'''))


if __name__ == '__main__':
    unittest.main()
