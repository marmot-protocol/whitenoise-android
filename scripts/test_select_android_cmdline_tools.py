"""Regress the runner's latest/latest-2 folder ordering and incomplete SDK installs."""
import pathlib
import tempfile
import unittest

from scripts.select_android_cmdline_tools import activate_latest, select_tools


class CommandLineToolsTest(unittest.TestCase):
    """Only package metadata and complete tools qualify an Android 17 AVD creator."""

    def install(self, root, name, revision, complete=True):
        """Create inert SDK metadata/tool fixtures without invoking an Android installer."""
        directory = root / "cmdline-tools" / name
        (directory / "bin").mkdir(parents=True)
        (directory / "source.properties").write_text(f"Pkg.Revision = {revision}\n")
        for tool in ("sdkmanager", "avdmanager") if complete else ("sdkmanager",):
            (directory / "bin" / tool).write_text("")
        return directory / "bin"

    def test_new_revision_wins_over_lexically_later_old_latest_folder(self):
        """The exact CI latest-2/latest ordering cannot silently choose pre-API-37 tools."""
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            self.install(root, "latest", "12.0")
            updated = self.install(root, "latest-2", "22.0")
            self.assertEqual(select_tools(root), updated)

    def test_highest_complete_package_revision_wins(self):
        """A folder suffix and partial installation cannot outrank a complete newer revision."""
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            self.install(root, "latest-10", "22.0")
            updated = self.install(root, "23.0", "23.0")
            self.install(root, "latest", "24.0", complete=False)
            self.assertEqual(select_tools(root), updated)

    def test_old_or_missing_tools_cannot_qualify_api37(self):
        """Reject absent tools and older AVD metadata readers instead of accepting a bad target."""
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            with self.assertRaises(ValueError):
                select_tools(root)
            self.install(root, "latest", "12.0")
            with self.assertRaises(ValueError):
                select_tools(root)

    def test_action_latest_path_uses_selected_revision_and_preserves_original(self):
        """The emulator action prepends latest/bin, so PATH selection alone is insufficient."""
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            self.install(root, "latest", "12.0")
            updated = self.install(root, "latest-2", "23.0")
            active = activate_latest(root, select_tools(root))
            self.assertEqual(active.resolve(), updated.resolve())
            self.assertEqual((active.parent / "source.properties").read_text(), "Pkg.Revision = 23.0\n")
            backup = root / "cmdline-tools" / "previous-share-tools" / "source.properties"
            self.assertEqual(backup.read_text(), "Pkg.Revision = 12.0\n")
            self.assertEqual(activate_latest(root, select_tools(root)), active)

    def test_current_latest_install_is_retained_without_a_backup(self):
        """A runner already using current tools needs no path replacement."""
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            current = self.install(root, "latest", "23.0")
            self.assertEqual(activate_latest(root, select_tools(root)), current)
            self.assertFalse((root / "cmdline-tools" / "latest").is_symlink())
            self.assertFalse((root / "cmdline-tools" / "previous-share-tools").exists())
