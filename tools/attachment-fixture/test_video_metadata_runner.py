"""Runner contracts: only a disposable emulator with both isolated packages reaches the instrumentation, and every
report states what the lane does not drive."""

import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import video_metadata_runner as runner


def device_replies(qemu="1", packages=(runner.APP, runner.APP + ".test"), sdk="34"):
    """Scripted adb answers for one device: emulator flag, platform and installed packages."""
    listed = "".join(f"package:{package}\n" for package in packages)
    return {("shell", "getprop", "ro.kernel.qemu"): qemu + "\n",
            ("shell", "getprop", "ro.build.version.sdk"): sdk + "\n",
            ("shell", "getprop", "ro.product.cpu.abi"): "x86_64\n",
            ("shell", "pm", "list", "packages", runner.APP): listed}


def run_scripted(passed=True, sdk="34"):
    """Runs the runner against a scripted emulator and fixture, returning the written report and every adb command."""
    replies = device_replies(sdk=sdk)
    commands = []

    def adb(_adb, _serial, *args):
        """Answers each adb call from the script and records it."""
        commands.append(args)
        return "" if args[0] == "reverse" else replies[args]

    server = mock.MagicMock(server_port=4001)
    server.ledger.snapshot.return_value = []
    with tempfile.TemporaryDirectory() as directory, \
            mock.patch.object(runner, "adb_command", side_effect=adb), \
            mock.patch("apk_physical_runner.adb_command", side_effect=adb), \
            mock.patch.object(runner, "FixtureServer", return_value=server), \
            mock.patch.object(runner, "FixtureRelay", return_value=mock.MagicMock(port=4002)), \
            mock.patch.object(runner, "instrument", return_value="OK (1 test)"), \
            mock.patch.object(runner, "check_video_metadata", return_value={"passed": passed}), \
            mock.patch.object(runner.time, "sleep"):
        output = Path(directory) / "out.json"
        try:
            runner.run("adb", "emulator-5554", Path(directory), output, "ci-api34-x86_64")
        except RuntimeError:
            pass  # An unqualified run raises after writing its report, which the caller judges.
        return json.loads(output.read_text()), commands


class VideoMetadataRunnerTest(unittest.TestCase):
    """Host-only guard rails. The Android behavior is qualified by the hosted emulator run, not by these."""

    def test_a_physical_serial_or_unknown_profile_is_refused_before_any_adb_call(self):
        """The fixture is for disposable emulators only, so nothing reaches adb for a refused device or profile."""
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(runner, "adb_command") as adb:
            output = Path(directory) / "out.json"
            for serial, profile in (("PHYSICAL000TEST", "ci-api34-x86_64"),
                                    ("emulator-5554", "pixel-api37-arm64"),
                                    ("emulator-5554", "no-such-profile")):
                with self.assertRaises(ValueError):
                    runner.run("adb", serial, Path(directory), output, profile)
            adb.assert_not_called()

    def test_a_device_that_is_not_an_emulator_or_lacks_the_test_package_is_refused(self):
        """A non-emulator, or a missing isolated test APK, is refused before the instrumentation runs."""
        for replies in (device_replies(qemu="0"), device_replies(packages=(runner.APP,))):
            with self.subTest(replies=replies[("shell", "getprop", "ro.kernel.qemu")]), \
                    tempfile.TemporaryDirectory() as directory, \
                    mock.patch.object(runner, "adb_command", side_effect=lambda _a, _s, *args: replies[args]), \
                    mock.patch("apk_physical_runner.adb_command", side_effect=lambda _a, _s, *args: replies[args]), \
                    mock.patch.object(runner, "instrument") as instrument:
                with self.assertRaises(ValueError):
                    runner.run("adb", "emulator-5554", Path(directory), Path(directory) / "out.json")
                instrument.assert_not_called()

    def test_the_instrumentation_command_targets_only_the_isolated_package_and_one_test(self):
        """The command names the isolated test package and the one fixture test, with no install or clear."""
        with mock.patch.object(runner.subprocess, "run") as run:
            run.return_value.stdout = "OK (1 test)"
            runner.instrument("adb", "emulator-5554", (4001, 4002))
        command = run.call_args.args[0]
        self.assertEqual(["adb", "-s", "emulator-5554"], command[:3])
        self.assertEqual(runner.TEST, command[command.index("class") + 1])
        self.assertEqual("true", command[command.index("allowControlledAttachmentProbe") + 1])
        self.assertTrue(command[-1].startswith("dev.ipf.whitenoise.android.medialatency.test/"))
        self.assertFalse({"uninstall", "install", "clear"} & set(command))

    def test_a_qualified_run_still_defers_every_entry_point_it_does_not_drive(self):
        """Even a passing run defers the system UI, a restored draft, a physical device and a camera recording."""
        report, commands = run_scripted()
        self.assertTrue(report["qualified"])
        self.assertEqual(list(runner.DEFERRED), report["deferred"])
        self.assertIn("force-stop-restored-draft", report["deferred"])
        self.assertIn("system-photo-picker-ui", report["deferred"])
        self.assertIn(("reverse", "--remove", "tcp:4001"), commands)
        self.assertFalse({"install", "uninstall", "clear"} & {word for command in commands for word in command})

    def test_a_failed_check_or_other_platform_does_not_qualify(self):
        """A checker violation, or a platform other than the declared profile, leaves the run unqualified."""
        for passed, sdk in ((False, "34"), (True, "33")):
            with self.subTest(passed=passed, sdk=sdk):
                report, _ = run_scripted(passed=passed, sdk=sdk)
                self.assertFalse(report["qualified"])


if __name__ == "__main__":
    unittest.main()
