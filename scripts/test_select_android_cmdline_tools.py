"""Regress the runner's latest/latest-2 folder ordering and incomplete SDK installs."""
import pathlib
import tempfile
import unittest

from scripts.select_android_cmdline_tools import select_tools


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
