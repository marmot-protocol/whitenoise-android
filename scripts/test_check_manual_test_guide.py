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

    def test_production_flavor_sources_still_require_maintenance_files(self):
        for path in (
            "app/src/dev/java/dev/ipf/whitenoise/android/ui/FooScreen.kt",
            "app/src/zapstore/res/values/strings.xml",
            "app/src/play/AndroidManifest.xml",
            "app/src/testing/res/values/strings.xml",
        ):
            with self.subTest(path=path):
                self.assertEqual(
                    MODULE.maintenance_files_missing({path}),
                    {MODULE.GUIDE_PATH, MODULE.INVENTORY_PATH},
                )

    def test_internal_only_change_does_not_require_guide_update(self):
        self.assertEqual(MODULE.maintenance_files_missing({"gradle/libs.versions.toml"}), set())

    def test_test_only_source_changes_do_not_require_guide_update(self):
        for path in (
            "app/src/test/java/FooTest.kt",
            "app/src/androidTest/java/FooTest.kt",
            "app/src/testZapstore/java/FooTest.kt",
            "app/src/test/snapshots/foo.png",
        ):
            with self.subTest(path=path):
                self.assertEqual(MODULE.maintenance_files_missing({path}), set())

    def test_added_surface_tokens_ignore_test_only_source_sets(self):
        for path in (
            "app/src/test/java/FooTest.kt",
            "app/src/androidTest/java/FooTest.kt",
            "app/src/testZapstore/java/FooTest.kt",
            "app/src/test/snapshots/Foo.kt",
        ):
            with self.subTest(path=path):
                diff = "\n".join(
                    [
                        f"+++ b/{path}",
                        "+fun FakeScreen(",
                        "+    title = stringResource(R.string.fake_title)",
                    ]
                )
                self.assertEqual(MODULE.added_surface_tokens(diff), [])

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

    def test_composable_discovery_accepts_preceding_annotation_on_same_line(self):
        text = '''
@Suppress("unused") @Composable
fun FutureWidget() = Unit
'''
        self.assertEqual(MODULE.composable_names(text), {"FutureWidget"})

    def test_composable_discovery_ignores_comments_strings_and_non_declarations(self):
        text = '''
// @Composable fun CommentedScreen() = Unit
/* @Composable fun BlockCommentedScreen() = Unit */
val marker = "@Composable fun StringScreen() = Unit"
val callback = @Composable { Unit }

@Composable
fun RealScreen() = Unit
'''
        self.assertEqual(MODULE.composable_names(text), {"RealScreen"})

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

    def test_composable_discovery_accepts_receivers_modifiers_and_type_parameters(self):
        text = '''
@Composable
private inline fun <T> BoxScope.FuturePanel(value: T) = Unit

@Composable
internal fun WhiteNoiseAppState.observeDestination() = Unit

@Composable
fun <T> GenericScreen(value: T) = Unit
'''
        self.assertEqual(
            MODULE.composable_names(text),
            {
                "BoxScope.FuturePanel",
                "WhiteNoiseAppState.observeDestination",
                "GenericScreen",
            },
        )

    def test_composable_discovery_fails_closed_for_unparsed_function_declaration(self):
        text = '''
@Composable
private fun <T BrokenScreen(value: T) = Unit
'''
        with self.assertRaisesRegex(MODULE.ComposableDiscoveryError, "cannot parse"):
            MODULE.composable_names(text)

    def test_inventory_rejects_empty_test_ownership(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "screen.kt"
            source.write_text("FutureScreen", encoding="utf-8")
            inventory = root / "inventory.json"
            inventory.write_text(
                json.dumps(
                    {
                        "categories": {
                            "settings_controls": [
                                {
                                    "surface": "FutureScreen",
                                    "source": "screen.kt",
                                    "anchor": "FutureScreen",
                                    "test_ids": [],
                                }
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
            self.assertTrue(any("at least one active test ID" in error for error in errors))

    def test_inventory_allows_reasoned_coverage_exception(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "screen.kt"
            source.write_text("FutureScreen", encoding="utf-8")
            inventory = root / "inventory.json"
            inventory.write_text(
                json.dumps(
                    {
                        "categories": {
                            "settings_controls": [
                                {
                                    "surface": "FutureScreen",
                                    "source": "screen.kt",
                                    "anchor": "FutureScreen",
                                    "test_ids": [],
                                    "coverage_exception": "Covered by a platform-owned state with no executable app action.",
                                }
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

    def test_manifest_permission_and_entry_point_require_coverage(self):
        for category, surface, anchor in (
            ("manifest_permissions", "permission:android.permission.FUTURE", "android.permission.FUTURE"),
            ("android_entry_points", "intent:future", "future"),
        ):
            with self.subTest(category=category), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                source = root / "AndroidManifest.xml"
                source.write_text(anchor, encoding="utf-8")
                inventory = root / "inventory.json"
                inventory.write_text(
                    json.dumps(
                        {
                            "categories": {
                                category: [
                                    {
                                        "surface": surface,
                                        "source": "AndroidManifest.xml",
                                        "anchor": anchor,
                                        "test_ids": [],
                                    }
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
                    mock.patch.object(MODULE, "SEMANTIC_OWNER_IDS", {category: {surface: {"MSG-001"}}}),
                ):
                    MODULE.validate_inventory({"MSG-001"}, errors)
                self.assertTrue(any("at least one active test ID" in error for error in errors))

    def test_inventory_rejects_semantically_stale_owner_id(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "AndroidManifest.xml"
            source.write_text("android.permission.CAMERA", encoding="utf-8")
            inventory = root / "inventory.json"
            inventory.write_text(
                json.dumps(
                    {
                        "categories": {
                            "manifest_permissions": [
                                {
                                    "surface": "permission:android.permission.CAMERA",
                                    "source": "AndroidManifest.xml",
                                    "anchor": "android.permission.CAMERA",
                                    "test_ids": ["SEC-005"],
                                }
                            ]
                        }
                    }
                ),
                encoding="utf-8",
            )
            errors = []
            expected = {"manifest_permissions": {"permission:android.permission.CAMERA": {"MED-004"}}}
            with (
                mock.patch.object(MODULE, "ROOT", root),
                mock.patch.object(MODULE, "INVENTORY", inventory),
                mock.patch.object(MODULE, "REQUIRED_INVENTORY_CATEGORIES", set()),
                mock.patch.object(MODULE, "SEMANTIC_OWNER_IDS", expected),
            ):
                MODULE.validate_inventory({"MED-004", "SEC-005"}, errors)
            self.assertTrue(any("expected exactly: MED-004" in error for error in errors))

    def test_vibration_permission_uses_vibration_checklist_owner(self):
        surface = "permission:android.permission.VIBRATE"
        inventory = json.loads(MODULE.INVENTORY.read_text(encoding="utf-8"))
        entry = next(
            item
            for item in inventory["categories"]["manifest_permissions"]
            if item["surface"] == surface
        )
        self.assertEqual(MODULE.SEMANTIC_OWNER_IDS["manifest_permissions"][surface], {"GRP-016"})
        self.assertEqual(entry["test_ids"], ["GRP-016"])

    def test_inventory_rejects_missing_mapped_semantic_surface(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            inventory = root / "inventory.json"
            inventory.write_text(
                json.dumps({"categories": {"manifest_permissions": []}}),
                encoding="utf-8",
            )
            errors = []
            surface = "permission:android.permission.CAMERA"
            with (
                mock.patch.object(MODULE, "ROOT", root),
                mock.patch.object(MODULE, "INVENTORY", inventory),
                mock.patch.object(MODULE, "REQUIRED_INVENTORY_CATEGORIES", set()),
                mock.patch.object(
                    MODULE,
                    "SEMANTIC_OWNER_IDS",
                    {"manifest_permissions": {surface: {"MED-004"}}},
                ),
            ):
                MODULE.validate_inventory({"MED-004"}, errors)
            self.assertTrue(
                any("mapped semantic surface is missing from manifest_permissions inventory" in error for error in errors)
            )

    def test_semantic_owner_mapping_is_scoped_to_inventory_category(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "screen.kt"
            source.write_text("android.permission.CAMERA", encoding="utf-8")
            inventory = root / "inventory.json"
            inventory.write_text(
                json.dumps(
                    {
                        "categories": {
                            "manifest_permissions": [
                                {
                                    "surface": "permission:android.permission.CAMERA",
                                    "source": "screen.kt",
                                    "anchor": "android.permission.CAMERA",
                                    "test_ids": ["MED-004"],
                                }
                            ],
                            "settings_controls": [
                                {
                                    "surface": "permission:android.permission.CAMERA",
                                    "source": "screen.kt",
                                    "anchor": "android.permission.CAMERA",
                                    "test_ids": ["SET-001"],
                                }
                            ],
                        }
                    }
                ),
                encoding="utf-8",
            )
            errors = []
            expected = {"manifest_permissions": {"permission:android.permission.CAMERA": {"MED-004"}}}
            with (
                mock.patch.object(MODULE, "ROOT", root),
                mock.patch.object(MODULE, "INVENTORY", inventory),
                mock.patch.object(MODULE, "REQUIRED_INVENTORY_CATEGORIES", set()),
                mock.patch.object(MODULE, "SEMANTIC_OWNER_IDS", expected),
            ):
                MODULE.validate_inventory({"MED-004", "SET-001"}, errors)
            self.assertEqual(errors, [])


if __name__ == "__main__":
    unittest.main()
