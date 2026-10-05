"""Runner contracts: only an emulator with the isolated package reaches adb, and request counts match the checker."""

from pathlib import Path
import tempfile
import unittest
from unittest import mock

import forward_checker as checker
import forward_runner as runner


class ForwardRunnerTest(unittest.TestCase):
    """Host-only guard rails; the Android behavior is qualified by the device run, not by these."""

    def test_a_physical_serial_or_unknown_profile_is_refused_before_any_adb_call(self):
        """The fixture is for disposable emulators only; nothing reaches adb for a refused device or profile."""
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(runner, "adb_command") as adb:
            output = Path(directory) / "out.json"
            for serial, profile in (("46131FDAS003CG", "reference-api30-arm64"), ("emulator-5554", "pixel-api37-arm64"),
                                    ("emulator-5554", "no-such-profile")):
                with self.assertRaises(ValueError):
                    runner.run("adb", serial, Path(directory), output, profile)
            adb.assert_not_called()

    def test_the_instrumentation_command_targets_only_the_isolated_package_and_the_forward_probe(self):
        """The command names the isolated test package, the forward probe and its opt-in flag, with no install flag."""
        with mock.patch.object(runner.subprocess, "run") as run:
            run.return_value.stdout = "OK (1 test)"
            runner.instrument("adb", "emulator-5554", (4001, 4002))
        command = run.call_args.args[0]
        self.assertEqual(["adb", "-s", "emulator-5554"], command[:3])
        self.assertEqual(runner.PROBE, command[command.index("class") + 1])
        self.assertEqual("true", command[command.index("fixtureForward") + 1])
        self.assertTrue(command[-1].startswith("dev.ipf.whitenoise.android.medialatency.test/"))
        self.assertFalse({"uninstall", "install", "clear"} & set(command))

    def test_expected_request_counts_follow_the_source_contract(self):
        """Uploads count every send once; GETs count contracted source downloads, one acquisition and each receipt."""
        self.assertEqual(22, checker.expected_uploads())
        downloads = sum(checker.REPETITIONS for v in checker.VARIANTS if checker.SOURCE[v]["download"])
        self.assertEqual(downloads + 1 + checker.REPETITIONS * len(checker.VARIANTS), checker.expected_gets())


if __name__ == "__main__":
    unittest.main()
