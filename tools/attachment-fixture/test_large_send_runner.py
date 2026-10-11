"""Runner contracts: only an emulator with the isolated package reaches the instrumentation."""

from pathlib import Path
import tempfile
import unittest
from unittest import mock

import large_send_runner as runner


class LargeSendRunnerTest(unittest.TestCase):
    """Host-only guard rails. The Android behavior is qualified by the device run, not by these."""

    def test_a_physical_serial_or_unknown_profile_is_refused_before_any_adb_call(self):
        """The fixture is for disposable emulators only, so nothing reaches adb for a refused device or profile."""
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(runner, "adb_command") as adb:
            output = Path(directory) / "out.json"
            for serial, profile in (("PHYSICAL000TEST", "reference-api36-arm64"), ("emulator-5554", "pixel-api37-arm64"),
                                    ("emulator-5554", "no-such-profile")):
                with self.assertRaises(ValueError):
                    runner.run("adb", serial, Path(directory), output, profile)
            adb.assert_not_called()

    def test_the_instrumentation_command_targets_only_the_isolated_package_and_one_test(self):
        """The command names the isolated test package and class, with no install, uninstall or clear flag."""
        with mock.patch.object(runner.subprocess, "run") as run:
            run.return_value.stdout = "OK (1 test)"
            runner.instrument("adb", "emulator-5554", (4001, 4002))
        command = run.call_args.args[0]
        self.assertEqual(["adb", "-s", "emulator-5554"], command[:3])
        self.assertEqual(runner.TEST, command[command.index("class") + 1])
        self.assertNotIn("fixtureLargeSendBytes", command)
        self.assertTrue(command[-1].startswith("dev.ipf.whitenoise.android.medialatency.test/"))
        self.assertFalse({"uninstall", "install", "clear"} & set(command))

    def test_a_smaller_ceiling_is_passed_only_when_asked(self):
        """A smaller emulator may lower the large file's size, and the argument appears only then."""
        with mock.patch.object(runner.subprocess, "run") as run:
            run.return_value.stdout = "OK (1 test)"
            runner.instrument("adb", "emulator-5554", (4001, 4002), large_send_bytes=300 * 1024 * 1024)
        command = run.call_args.args[0]
        self.assertEqual(str(300 * 1024 * 1024), command[command.index("fixtureLargeSendBytes") + 1])


if __name__ == "__main__":
    unittest.main()
