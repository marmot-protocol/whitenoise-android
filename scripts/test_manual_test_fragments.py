"""Real-file and Git-history regressions for independent manual-test inputs."""
import json
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from scripts import check_manual_test_guide as checker
from scripts import manual_test_fragments as fragments
from scripts.test_check_manual_test_guide import guide

FIRST = "1. [ ] **MSG-001 — Send text** — Send hello → **Expected:** Hello appears once"
SECOND = "1. [ ] **MSG-002 — Send media** — Send a photo → **Expected:** Photo appears once"
SOURCE = "app/src/main/java/Foo.kt"
OTHER = "app/src/main/java/Bar.kt"


class FragmentTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.write(fragments.GUIDE_PATH, guide(FIRST))
        self.inventory = {
            "categories": {"composable_surfaces": [self.entry(SOURCE), self.entry(OTHER)]},
            "discovery_exceptions": [{"surface": "global", "reason": "No user-facing surface"}],
        }
        self.write(fragments.INVENTORY_PATH, json.dumps(self.inventory))
        self.write(SOURCE, "fun FooScreen() {}")
        self.write(OTHER, "fun FooScreen() {}")

    def write(self, path, content):
        destination = self.root / path
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(content, encoding="utf-8")

    def entry(self, source, ids=None):
        return {"source": source, "surface": "FooScreen", "anchor": "fun FooScreen(", "test_ids": ids or ["MSG-001"]}

    def case_path(self, test_id="MSG-001"):
        return self.root / fragments.CASE_DIR / f"{test_id}.md"

    def surface_path(self, source=SOURCE):
        return self.root / fragments.SURFACE_DIR / f"{source}.json"

    def seed(self, source=SOURCE):
        fragments.extract_case(self.root, "MSG-001")
        fragments.extract_source(self.root, source)

    def git(self, *args):
        return subprocess.run(["git", *args], cwd=self.root, text=True, capture_output=True, check=True).stdout.strip()

    def commit(self, message):
        self.git("add", ".")
        self.git("commit", "-qm", message)
        return self.git("rev-parse", "HEAD")

    def init_git(self):
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.name", "Fixture")
        self.git("config", "user.email", "fixture@example.invalid")
        self.git("config", "commit.gpgsign", "false")
        return self.commit("legacy inputs")

    def test_legacy_inputs_and_extraction_preserve_effective_coverage(self):
        before = fragments.load_guide(self.root)
        self.assertEqual(before, guide(FIRST))
        self.seed()
        self.assertEqual(fragments.load_guide(self.root), before)
        self.assertEqual(fragments.load_inventory(self.root), self.inventory)
        with self.assertRaises(FileExistsError):
            fragments.extract_case(self.root, "MSG-001")

    def test_case_changes_render_and_new_id_appends_without_renumbering(self):
        self.seed()
        path = self.case_path()
        path.write_text(path.read_text().replace("Send hello", "Send hello twice"))
        self.write(f"{fragments.CASE_DIR}/MSG-002.md", f"<!-- legacy-sha256: none -->\n\n{SECOND}\n")
        rendered = fragments.load_guide(self.root)
        self.assertIn("Send hello twice", rendered)
        self.assertIn(SECOND.replace("1.", "2.", 1), rendered)
        active, retired, errors = checker.parse_guide(rendered)
        self.assertEqual((active, retired, errors), ({"MSG-001", "MSG-002"}, set(), []))

    def test_legacy_case_edits_cannot_be_silently_masked(self):
        self.seed()
        self.write(fragments.GUIDE_PATH, guide(FIRST.replace("Send hello", "Send goodbye")))
        with self.assertRaisesRegex(fragments.FragmentError, "legacy definition changed"):
            fragments.load_guide(self.root)

    def test_legacy_mapping_edits_cannot_be_silently_masked(self):
        self.seed()
        self.inventory["categories"]["composable_surfaces"][0]["test_ids"] = ["MSG-002"]
        self.write(fragments.INVENTORY_PATH, json.dumps(self.inventory))
        with self.assertRaisesRegex(fragments.FragmentError, "legacy source mapping changed"):
            fragments.load_inventory(self.root)

    def test_one_source_override_preserves_other_source_and_global_exceptions(self):
        self.seed()
        path = self.surface_path()
        data = json.loads(path.read_text())
        data["categories"]["composable_surfaces"][0]["test_ids"].append("MSG-002")
        path.write_text(json.dumps(data))
        actual = fragments.load_inventory(self.root)
        self.assertEqual(actual["discovery_exceptions"], self.inventory["discovery_exceptions"])
        rows = actual["categories"]["composable_surfaces"]
        self.assertEqual(next(e for e in rows if e["source"] == OTHER), self.entry(OTHER))
        self.assertEqual(next(e for e in rows if e["source"] == SOURCE)["test_ids"], ["MSG-001", "MSG-002"])

    def test_raw_duplicates_fail_before_overrides_can_remove_them(self):
        self.seed()
        self.inventory["categories"]["composable_surfaces"].append(self.entry(SOURCE))
        self.write(fragments.INVENTORY_PATH, json.dumps(self.inventory))
        with self.assertRaisesRegex(fragments.FragmentError, "duplicate surface"):
            fragments.load_inventory(self.root)
        with self.assertRaisesRegex(fragments.FragmentError, "duplicate JSON key"):
            fragments.decode_inventory('{"categories": {}, "categories": {}}')
        self.write(fragments.GUIDE_PATH, guide(FIRST + "\n" + FIRST))
        with self.assertRaisesRegex(fragments.FragmentError, "duplicate active ID"):
            fragments.load_guide(self.root)

    def test_malformed_checked_wrong_id_or_unknown_prefix_case_fails(self):
        self.seed()
        original = self.case_path().read_text()
        for content in [original.replace("[ ]", "[x]"), original.replace("MSG-001", "MSG-002"), original + FIRST]:
            with self.subTest(content=content):
                self.case_path().write_text(content)
                with self.assertRaises(fragments.FragmentError):
                    fragments.load_guide(self.root)
        self.case_path().write_text(original)
        self.write(f"{fragments.CASE_DIR}/NEW-001.md", f"<!-- legacy-sha256: none -->\n\n{FIRST.replace('MSG-001', 'NEW-001')}\n")
        with self.assertRaisesRegex(fragments.FragmentError, "no existing checklist section"):
            fragments.load_guide(self.root)

    def test_retired_case_cannot_be_reactivated(self):
        self.write(fragments.GUIDE_PATH, guide(FIRST) + "| MSG-002 | #1 | Obsolete | — |\n")
        self.write(f"{fragments.CASE_DIR}/MSG-002.md", f"<!-- legacy-sha256: none -->\n\n{SECOND}\n")
        with self.assertRaisesRegex(fragments.FragmentError, "retired ID"):
            fragments.load_guide(self.root)

    def test_source_path_schema_and_entry_ownership_fail_closed(self):
        self.seed()
        original = json.loads(self.surface_path().read_text())
        for change in [{"source": "../escape"}, {"source": OTHER}, {"future_schema": 2}, {"categories": []}]:
            self.surface_path().write_text(json.dumps({**original, **change}))
            with self.assertRaises((fragments.FragmentError, ValueError)):
                fragments.load_inventory(self.root)
        original["categories"]["composable_surfaces"][0]["source"] = OTHER
        self.surface_path().write_text(json.dumps(original))
        with self.assertRaisesRegex(fragments.FragmentError, "every entry must belong"):
            fragments.load_inventory(self.root)

    def test_symlink_inputs_and_extraction_are_rejected(self):
        self.case_path().parent.mkdir(parents=True)
        self.case_path().symlink_to(self.root / fragments.GUIDE_PATH)
        with self.assertRaises(fragments.FragmentError):
            fragments.load_guide(self.root)
        with self.assertRaises(fragments.FragmentError):
            fragments.extract_case(self.root, "MSG-001")

    def test_historical_legacy_and_fragment_ids_survive_exact_tree_reads(self):
        base = self.init_git()
        self.seed()
        self.write(f"{fragments.CASE_DIR}/MSG-002.md", f"<!-- legacy-sha256: none -->\n\n{SECOND}\n")
        newer = self.commit("add source-owned case")
        self.assertEqual(fragments.load_guide(self.root, revision=base), guide(FIRST))
        (self.root / fragments.CASE_DIR / "MSG-002.md").unlink()
        with mock.patch.object(checker, "ROOT", self.root), mock.patch.object(checker, "GUIDE", self.root / fragments.GUIDE_PATH):
            errors = []
            checker.validate_history(newer, {"MSG-001"}, set(), errors)
            self.assertTrue(any("removed without retirement" in e for e in errors))
            errors = []
            checker.validate_history("not-a-revision", {"MSG-001"}, set(), errors)
            self.assertTrue(any("history" in e and "cannot read" in e for e in errors))

    def test_extraction_only_and_unrelated_fragments_do_not_satisfy_maintenance(self):
        base = self.init_git()
        self.seed(OTHER)
        changed = {SOURCE, f"{fragments.CASE_DIR}/MSG-001.md", f"{fragments.SURFACE_DIR}/{OTHER}.json"}
        with mock.patch.object(checker, "ROOT", self.root), mock.patch.object(checker, "GUIDE", self.root / fragments.GUIDE_PATH), mock.patch.object(checker, "INVENTORY", self.root / fragments.INVENTORY_PATH):
            errors = []
            checker.validate_fragment_maintenance(base, changed, errors)
            self.assertEqual(len(errors), 2)
            self.case_path().write_text(self.case_path().read_text().replace("Send hello", "Send hello twice"))
            errors = []
            checker.validate_fragment_maintenance(base, changed, errors)
            self.assertEqual(len(errors), 1)
            self.assertIn("changed production source", errors[0])

    def test_relevant_effective_fragment_changes_satisfy_source_contract(self):
        base = self.init_git()
        self.seed()
        self.write(f"{fragments.CASE_DIR}/MSG-002.md", f"<!-- legacy-sha256: none -->\n\n{SECOND}\n")
        path = self.surface_path()
        data = json.loads(path.read_text())
        data["categories"]["composable_surfaces"][0]["test_ids"].append("MSG-002")
        path.write_text(json.dumps(data))
        changed = {SOURCE, f"{fragments.CASE_DIR}/MSG-002.md", f"{fragments.SURFACE_DIR}/{SOURCE}.json"}
        with mock.patch.object(checker, "ROOT", self.root):
            errors = []
            checker.validate_fragment_maintenance(base, changed, errors)
            self.assertEqual(errors, [])

    def test_deleting_an_override_cannot_count_as_an_unchanged_update(self):
        self.seed()
        base = self.init_git()
        self.case_path().unlink()
        self.surface_path().unlink()
        changed = {SOURCE, f"{fragments.CASE_DIR}/MSG-001.md", f"{fragments.SURFACE_DIR}/{SOURCE}.json"}
        with (
            mock.patch.object(checker, "ROOT", self.root),
            mock.patch.object(checker, "GUIDE", self.root / fragments.GUIDE_PATH),
            mock.patch.object(checker, "INVENTORY", self.root / fragments.INVENTORY_PATH),
        ):
            errors = []
            checker.validate_fragment_maintenance(base, changed, errors)
            self.assertEqual(len(errors), 2)

    def test_historical_malformed_and_executable_inputs_fail_closed(self):
        self.seed()
        self.case_path().write_text("bad scenario\n")
        base = self.init_git()
        with mock.patch.object(checker, "ROOT", self.root), mock.patch.object(checker, "GUIDE", self.root / fragments.GUIDE_PATH):
            errors = []
            checker.validate_history(base, {"MSG-001"}, set(), errors)
            self.assertTrue(any("history" in e for e in errors))
        self.case_path().chmod(0o755)
        with self.assertRaisesRegex(fragments.FragmentError, "non-executable"):
            fragments.load_guide(self.root)
        executable = self.commit("executable case")
        with self.assertRaisesRegex(fragments.FragmentError, "non-executable"):
            fragments.load_guide(self.root, revision=executable)

    def test_effective_inventory_still_rejects_missing_anchors_and_dangling_ids(self):
        self.seed()
        path = self.surface_path()
        data = json.loads(path.read_text())
        data["categories"]["composable_surfaces"][0]["anchor"] = "fun Missing("
        data["categories"]["composable_surfaces"][0]["test_ids"] = ["MSG-999"]
        path.write_text(json.dumps(data))
        with (
            mock.patch.object(checker, "ROOT", self.root),
            mock.patch.object(checker, "INVENTORY", self.root / fragments.INVENTORY_PATH),
            mock.patch.object(checker, "REQUIRED_INVENTORY_CATEGORIES", set()),
            mock.patch.object(checker, "SEMANTIC_OWNER_IDS", {}),
        ):
            errors = []
            checker.validate_inventory({"MSG-001"}, errors)
            self.assertTrue(any("anchor no longer exists" in e for e in errors))
            self.assertTrue(any("reference does not resolve" in e for e in errors))

    def test_generation_is_deterministic(self):
        other = self.root / "other-checkout"
        shutil.copytree(self.root, other)
        for root, sources in [(self.root, [SOURCE, OTHER]), (other, [OTHER, SOURCE])]:
            for source in sources:
                fragments.extract_source(root, source)
            fragments.extract_case(root, "MSG-001")
            path = root / fragments.CASE_DIR / "MSG-002.md"
            path.write_text(f"<!-- legacy-sha256: none -->\n\n{SECOND}\n", encoding="utf-8")
        self.assertEqual(fragments.load_guide(self.root), fragments.load_guide(other))
        self.assertEqual(
            json.dumps(fragments.load_inventory(self.root), indent=2),
            json.dumps(fragments.load_inventory(other), indent=2),
        )

    def test_ambiguous_new_case_section_fails_but_existing_overrides_work(self):
        self.write(fragments.GUIDE_PATH, guide(FIRST + "\n### Other messages\n" + SECOND))
        fragments.extract_case(self.root, "MSG-002")
        self.assertEqual(checker.parse_guide(fragments.load_guide(self.root))[2], [])
        third = SECOND.replace("MSG-002", "MSG-003")
        self.write(f"{fragments.CASE_DIR}/MSG-003.md", f"<!-- legacy-sha256: none -->\n\n{third}\n")
        with self.assertRaisesRegex(fragments.FragmentError, "prefix spans multiple sections"):
            fragments.load_guide(self.root)

    def test_stray_files_are_ignored_consistently_in_working_and_git_trees(self):
        self.seed()
        self.write(f"{fragments.CASE_DIR}/.DS_Store", "untracked metadata")
        self.write(f"{fragments.CASE_DIR}/MSG-001.md~", "editor backup")
        self.write(f"{fragments.SURFACE_DIR}/backup.json~", "editor backup")
        before = fragments.load_guide(self.root)
        before_inventory = fragments.load_inventory(self.root)
        base = self.init_git()
        self.assertEqual(fragments.load_guide(self.root, revision=base), before)
        self.assertEqual(fragments.load_inventory(self.root, revision=base), before_inventory)

    def test_new_source_can_extract_empty_mapping_then_own_a_new_case(self):
        base = self.init_git()
        source = "app/src/main/java/New.kt"
        self.write(source, "fun FooScreen() {}")
        fragments.extract_source(self.root, source)
        path = self.surface_path(source)
        data = json.loads(path.read_text())
        self.assertEqual(data["categories"], {})
        data["categories"]["composable_surfaces"] = [self.entry(source, ["MSG-002"])]
        path.write_text(json.dumps(data))
        self.write(f"{fragments.CASE_DIR}/MSG-002.md", f"<!-- legacy-sha256: none -->\n\n{SECOND}\n")
        changed = {source, f"{fragments.CASE_DIR}/MSG-002.md", f"{fragments.SURFACE_DIR}/{source}.json"}
        with mock.patch.object(checker, "ROOT", self.root):
            errors = []
            checker.validate_fragment_maintenance(base, changed, errors)
            self.assertEqual(errors, [])

    def test_two_independent_changes_merge_without_a_shared_aggregate_edit(self):
        self.write(fragments.GUIDE_PATH, guide(FIRST + "\n" + SECOND.replace("1.", "2.", 1)))
        self.inventory["categories"]["composable_surfaces"][1]["test_ids"] = ["MSG-002"]
        self.write(fragments.INVENTORY_PATH, json.dumps(self.inventory))
        base = self.init_git()
        self.git("switch", "-qc", "first")
        self.seed()
        self.case_path().write_text(self.case_path().read_text().replace("Send hello", "Send hello twice"))
        self.commit("first scenario")
        self.git("switch", "-qc", "second", base)
        fragments.extract_case(self.root, "MSG-002")
        fragments.extract_source(self.root, OTHER)
        path = self.case_path("MSG-002")
        path.write_text(path.read_text().replace("Send a photo", "Send two photos"))
        self.commit("second scenario")
        self.git("merge", "--no-edit", "first")
        rendered = fragments.load_guide(self.root)
        self.assertIn("Send hello twice", rendered)
        self.assertIn("Send two photos", rendered)
        self.assertEqual(checker.parse_guide(rendered)[2], [])
        self.assertEqual(self.git("diff", base, "--", fragments.GUIDE_PATH, fragments.INVENTORY_PATH), "")


if __name__ == "__main__":
    unittest.main()
