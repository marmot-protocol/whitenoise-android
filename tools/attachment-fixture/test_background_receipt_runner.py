"""Runner contracts: only an emulator with the isolated package reaches the instrumentation."""

from pathlib import Path
import tempfile
import unittest
from unittest import mock

import background_receipt_runner as runner


class BackgroundReceiptRunnerTest(unittest.TestCase):
    """Host-only guard rails; the Android behavior is qualified by the device run, not by these."""

    def test_a_physical_serial_or_unknown_profile_is_refused_before_any_adb_call(self):
        """The fixture is for disposable emulators only; nothing reaches adb for a refused device or profile."""
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(runner, "adb_command") as adb:
            output = Path(directory) / "out.json"
            for serial, profile in (("PHYSICAL000TEST", "reference-api30-arm64"), ("emulator-5554", "pixel-api37-arm64"),
                                    ("emulator-5554", "no-such-profile")):
                with self.assertRaises(ValueError):
                    runner.run("adb", serial, Path(directory), output, profile)
            adb.assert_not_called()

    def test_the_instrumentation_command_targets_only_the_isolated_package_and_one_test(self):
        """Each stage names the isolated test package, the class, its stage and session, and never installs."""
        for stage in runner.STAGES:
            with mock.patch.object(runner.subprocess, "run") as run:
                run.return_value.stdout = "OK (1 test)"
                runner.instrument("adb", "emulator-5554", (4001, 4002), stage, "session")
            command = run.call_args.args[0]
            self.assertEqual(["adb", "-s", "emulator-5554"], command[:3])
            self.assertEqual(runner.TEST, command[command.index("class") + 1])
            self.assertEqual(stage, command[command.index("fixtureBackgroundStage") + 1])
            self.assertEqual("session", command[command.index("fixtureRestartSession") + 1])
            self.assertTrue(command[-1].startswith("dev.ipf.whitenoise.android.medialatency.test/"))
            self.assertFalse({"uninstall", "install", "clear"} & set(command))

    def test_an_unknown_stage_is_refused_before_adb(self):
        """Only the two declared stages can be launched."""
        with mock.patch.object(runner.subprocess, "run") as run, self.assertRaises(ValueError):
            runner.instrument("adb", "emulator-5554", (4001, 4002), "prepare", "session")
        run.assert_not_called()

    def test_only_a_crash_after_the_held_row_counts_as_the_hand_off(self):
        """The main stage must crash after reporting the held row; a pass, or a crash without it, is a failure."""
        crash = "INSTRUMENTATION_RESULT: shortMsg=Process crashed.\n"
        held = [{"phase": "background-receipt-held"}]
        with mock.patch.object(runner, "metrics_of", return_value=held), \
                mock.patch.object(runner, "passed", return_value=False):
            self.assertTrue(runner.ended_abruptly(crash))
            self.assertFalse(runner.ended_abruptly("no crash marker"))
        with mock.patch.object(runner, "metrics_of", return_value=[]), \
                mock.patch.object(runner, "passed", return_value=False):
            self.assertFalse(runner.ended_abruptly(crash))
        with mock.patch.object(runner, "metrics_of", return_value=held), \
                mock.patch.object(runner, "passed", return_value=True):
            self.assertFalse(runner.ended_abruptly(crash))


if __name__ == "__main__":
    unittest.main()
