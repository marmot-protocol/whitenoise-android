#!/usr/bin/env python3
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

SCRIPT = Path(__file__).with_name("check_manual_test_guide.py")
SPEC = importlib.util.spec_from_file_location("manual_guide_check", SCRIPT)
assert SPEC is not None
assert SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def guide(item: str) -> str:
    return "\n".join([
        "# White Noise Android manual release testing",
        "## How to use this guide",
        "## Safety and test-data rules",
        "## Test matrix and prerequisites",
        "## Smoke pass",
        "## Full release checklist",
        "| Prefix | Area |",
        "|---|---|",
        "| `MSG` | Messages |",
        "### Messages",
        item,
        "## Report a failure",
        "## Maintainer coverage map",
        "## Retired IDs",
        "| ID | Retired in | Reason | Superseded by |",
        "|---|---|---|---|",
    ]) + "\n"


class ManualGuideParserTest(unittest.TestCase):
    def test_accepts_one_exact_definition(self):
        active, retired, errors = MODULE.parse_guide(
            guide("1. [ ] **MSG-001 — Send text** — Send hello → **Expected:** Hello appears once")
        )
        self.assertEqual(active, {"MSG-001"})
        self.assertEqual(retired, set())
        self.assertEqual(errors, [])

    def test_rejects_checked_box(self):
        _, _, errors = MODULE.parse_guide(
            guide("1. [x] **MSG-001 — Send text** — Send hello → **Expected:** Hello appears once")
        )
        self.assertTrue(any("checked-box" in error for error in errors))

    def test_rejects_missing_expected_result(self):
        _, _, errors = MODULE.parse_guide(guide("1. [ ] **MSG-001 — Send text** — Send hello"))
        self.assertTrue(any("malformed-definition" in error for error in errors))

    def test_rejects_duplicate_id(self):
        text = guide("1. [ ] **MSG-001 — Send text** — Send hello → **Expected:** Hello appears once")
        text = text.replace(
            "## Report a failure",
            "2. [ ] **MSG-001 — Send again** — Send again → **Expected:** A second row appears\n## Report a failure",
        )
        _, _, errors = MODULE.parse_guide(text)
        self.assertTrue(any("duplicate active ID" in error for error in errors))

    def test_rejects_bad_ordinal(self):
        _, _, errors = MODULE.parse_guide(
            guide("2. [ ] **MSG-001 — Send text** — Send hello → **Expected:** Hello appears once")
        )
        self.assertTrue(any("ordinal 2 should be 1" in error for error in errors))

    def test_rejects_unresolved_reference(self):
        text = guide("1. [ ] **MSG-001 — Send text** — Send hello → **Expected:** Hello appears once")
        text = text.replace("## Smoke pass", "## Smoke pass\n- `MSG-999`")
        _, _, errors = MODULE.parse_guide(text)
        self.assertTrue(any("reference does not resolve" in error for error in errors))

    def test_rejects_invalid_retired_row(self):
        text = guide("1. [ ] **MSG-001 — Send text** — Send hello → **Expected:** Hello appears once")
        text += "| MSG-002 | yesterday | | MSG-999 |\n"
        _, _, errors = MODULE.parse_guide(text)
        self.assertTrue(any("Retired in" in error for error in errors))

    def test_inventory_allows_same_control_on_distinct_screens(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            first = root / "first.kt"
            second = root / "second.kt"
            first.write_text("R.string.action_color", encoding="utf-8")
            second.write_text("R.string.action_color", encoding="utf-8")
            inventory = root / "inventory.json"
            inventory.write_text(
                json.dumps(
                    {
                        "categories": {
                            "settings_controls": [
                                {
                                    "surface": "setting:action_color",
                                    "source": "first.kt",
                                    "anchor": "R.string.action_color",
                                    "test_ids": ["MSG-001"],
                                },
                                {
                                    "surface": "setting:action_color",
                                    "source": "second.kt",
                                    "anchor": "R.string.action_color",
                                    "test_ids": ["MSG-001"],
                                },
                            ]
                        }
                    }
                ),
                encoding="utf-8",
            )
            errors = []
            with (
                mock.patch.object(MODULE, "ROOT", root),
                mock.patch.object(MODULE, "INVENTORY", inventory),
                mock.patch.object(MODULE, "REQUIRED_INVENTORY_CATEGORIES", set()),
            ):
                MODULE.validate_inventory({"MSG-001"}, errors)
            self.assertEqual(errors, [])

    def test_inventory_rejects_duplicate_surface_in_same_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "screen.kt"
            source.write_text("R.string.action_color", encoding="utf-8")
            entry = {
                "surface": "setting:action_color",
                "source": "screen.kt",
                "anchor": "R.string.action_color",
                "test_ids": ["MSG-001"],
            }
            inventory = root / "inventory.json"
            inventory.write_text(
                json.dumps({"categories": {"settings_controls": [entry, entry]}}),
                encoding="utf-8",
            )
            errors = []
            with (
                mock.patch.object(MODULE, "ROOT", root),
                mock.patch.object(MODULE, "INVENTORY", inventory),
                mock.patch.object(MODULE, "REQUIRED_INVENTORY_CATEGORIES", set()),
            ):
                MODULE.validate_inventory({"MSG-001"}, errors)
            self.assertTrue(any("duplicate surface entry for the same source" in error for error in errors))

    def test_inventory_rejects_missing_categories_object(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            inventory = root / "inventory.json"
            inventory.write_text("{}", encoding="utf-8")
            errors = []
            with mock.patch.object(MODULE, "ROOT", root), mock.patch.object(MODULE, "INVENTORY", inventory):
                MODULE.validate_inventory({"MSG-001"}, errors)
            self.assertTrue(any("categories object" in error for error in errors))

    def test_user_facing_source_change_requires_both_maintenance_files(self):
        changed = {
            "app/src/main/java/dev/ipf/whitenoise/android/ui/FutureScreen.kt",
            "docs/manual-release-testing.md",
        }
        self.assertEqual(
            MODULE.maintenance_files_missing(changed),
            {"docs/manual-release-testing-surfaces.json"},
        )

    def test_user_facing_source_change_passes_with_both_maintenance_files(self):
        changed = {
            "app/src/main/AndroidManifest.xml",
            "docs/manual-release-testing.md",
            "docs/manual-release-testing-surfaces.json",
        }
        self.assertEqual(MODULE.maintenance_files_missing(changed), set())

    def test_internal_only_change_does_not_require_guide_update(self):
        self.assertEqual(MODULE.maintenance_files_missing({"gradle/libs.versions.toml"}), set())

    def test_added_surface_tokens_discovers_ui_labels_and_manifest_contracts(self):
        diff = "\n".join(
            [
                "+++ b/app/src/main/java/dev/ipf/whitenoise/android/ui/FutureScreen.kt",
                "+fun FutureScreen(",
                "+    title = stringResource(R.string.future_title)",
                "+++ b/app/src/staging/AndroidManifest.xml",
                "+<uses-permission android:name=\"android.permission.CAMERA\" />",
                "+<data android:scheme=\"future\" />",
            ]
        )
        self.assertEqual(
            MODULE.added_surface_tokens(diff),
            [
                ("app/src/main/java/dev/ipf/whitenoise/android/ui/FutureScreen.kt", "R.string.future_title"),
                ("app/src/main/java/dev/ipf/whitenoise/android/ui/FutureScreen.kt", "fun FutureScreen("),
                ("app/src/staging/AndroidManifest.xml", "android.permission.CAMERA"),
                ("app/src/staging/AndroidManifest.xml", "future"),
            ],
        )

    def test_added_surface_tokens_ignores_removed_and_non_app_lines(self):
        diff = "\n".join(
            [
                "+++ b/scripts/helper.py",
                "+fun FakeScreen(",
                "+++ b/app/src/main/java/dev/ipf/whitenoise/android/ui/Old.kt",
                "-fun RemovedScreen(",
            ]
        )
        self.assertEqual(MODULE.added_surface_tokens(diff), [])

    def test_current_composable_surface_discovery_is_exhaustive(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "app/src/main/java/dev/ipf/whitenoise/android/ui/Future.kt"
            source.parent.mkdir(parents=True)
            source.write_text(
                "@Composable\nprivate fun FutureDialog() = Unit\n\n@Composable\nfun FutureScreen() = Unit\n",
                encoding="utf-8",
            )
            with mock.patch.object(MODULE, "ROOT", root):
                self.assertEqual(
                    MODULE.current_composable_surfaces(),
                    {
                        ("app/src/main/java/dev/ipf/whitenoise/android/ui/Future.kt", "FutureDialog"),
                        ("app/src/main/java/dev/ipf/whitenoise/android/ui/Future.kt", "FutureScreen"),
                    },
                )

    def test_composable_discovery_accepts_multiline_suppress_with_quoted_arguments(self):
        text = '''
@Composable
@Suppress(
    "FunctionNaming",
    "LongMethod", // The comment must not terminate annotation parsing.
)
internal fun ConversationBottomBar() = Unit
'''
        self.assertEqual(MODULE.composable_names(text), {"ConversationBottomBar"})

    def test_composable_discovery_accepts_opt_in_class_arguments(self):
        text = '''
@Composable
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class,
)
private fun TimelineRow() = Unit
'''
        self.assertEqual(MODULE.composable_names(text), {"TimelineRow"})


if __name__ == "__main__":
    unittest.main()
