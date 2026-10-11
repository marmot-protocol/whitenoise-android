"""Runner contracts: only an emulator, or the one explicitly authorized phone, with both isolated packages and room
for the send reaches the instrumentation."""

import json
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
            for serial, profile in (("PHYSICAL000TEST", "reference-api36-arm64"),
                                    ("emulator-5554", "pixel-api37-arm64"),
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

    def test_a_physical_run_needs_the_repeated_serial_and_the_pixel_profile_before_any_adb_call(self):
        """A personal phone is used only when its serial is named twice and the Pixel profile is chosen."""
        with tempfile.TemporaryDirectory() as directory, \
                mock.patch.object(runner, "adb_command") as adb, \
                mock.patch("apk_physical_runner.adb_command") as physical_adb:
            output = Path(directory) / "out.json"
            for serial, physical, profile in (("PHYSICAL000TEST", "OTHER000TEST", "pixel-api37-arm64"),
                                              ("emulator-5554", "emulator-5554", "pixel-api37-arm64"),
                                              ("PHYSICAL000TEST", "PHYSICAL000TEST", "reference-api36-arm64")):
                with self.assertRaises(ValueError):
                    runner.run("adb", serial, Path(directory), output, profile, physical_fixture_serial=physical)
            adb.assert_not_called()
            physical_adb.assert_not_called()

    def test_a_device_without_room_for_the_send_is_refused_before_the_instrumentation(self):
        """A nearly full device is refused before anything is written to it."""
        replies = device_replies(qemu="1", available_kib=2_000_000)
        with tempfile.TemporaryDirectory() as directory, \
                mock.patch.object(runner, "adb_command", side_effect=lambda _adb, _serial, *args: replies[args]), \
                mock.patch("apk_physical_runner.adb_command", side_effect=lambda _adb, _serial, *args: replies[args]), \
                mock.patch.object(runner, "instrument") as instrument:
            with self.assertRaises(ValueError):
                runner.run("adb", "emulator-5554", Path(directory), Path(directory) / "out.json")
            instrument.assert_not_called()

    def test_a_device_missing_the_test_package_is_refused_before_the_instrumentation(self):
        """Both isolated packages must be installed, so a missing test APK is a clear refusal, not a failed run."""
        replies = device_replies(qemu="1", packages=(runner.APP,))
        with tempfile.TemporaryDirectory() as directory, \
                mock.patch.object(runner, "adb_command", side_effect=lambda _adb, _serial, *args: replies[args]), \
                mock.patch("apk_physical_runner.adb_command", side_effect=lambda _adb, _serial, *args: replies[args]), \
                mock.patch.object(runner, "instrument") as instrument:
            with self.assertRaises(ValueError):
                runner.run("adb", "emulator-5554", Path(directory), Path(directory) / "out.json")
            instrument.assert_not_called()

    def test_the_storage_requirement_covers_three_copies_and_a_two_gib_margin(self):
        """The run needs room for the staged copy, MDK's copy and the ciphertext, plus 2 GiB."""
        self.assertEqual(3 * (512 * 1024 ** 2 - 16) + 2 * 1024 ** 3, runner.required_free_bytes())
        self.assertEqual(3 * 300 * 1024 ** 2 + 2 * 1024 ** 3, runner.required_free_bytes(300 * 1024 ** 2))

    def test_free_storage_is_read_from_the_column_before_the_use_percentage(self):
        """A row wrapped after a long filesystem name still reads, and output with no such column is refused."""
        header = "Filesystem 1K-blocks Used Available Use% Mounted on\n"
        row = "/dev/block/dm-1 100000000 90000000 9000000 90% /data\n"
        self.assertEqual(9_000_000, runner.parse_free_kib(header + row))
        self.assertEqual(9_000_000, runner.parse_free_kib(header + "/dev/block/bootdevice/by-name/userdata\n"
                                                                   "    100000000 90000000 9000000 90% /data\n"))
        for output in ("", header, header + "df: /data: Permission denied\n"):
            with self.assertRaises(ValueError):
                runner.parse_free_kib(output)

    def test_a_qualified_physical_run_reports_its_kind_and_stops_deferring_the_physical_device(self):
        """A qualified run on the authorized phone says so, records free storage, and runs no install or clear."""
        report, commands = run_physical(sdk="37")
        self.assertTrue(report["qualified"])
        self.assertEqual("physical", report["device_kind"])
        self.assertNotIn("physical-device", report["deferred"])
        self.assertEqual(9_000_000 * 1024, report["free_data_bytes_before"])
        self.assertEqual(9_000_000 * 1024, report["free_data_bytes_after"])
        self.assertFalse({"install", "uninstall", "clear"} & {word for command in commands for word in command})

    def test_a_physical_run_on_a_different_platform_does_not_qualify(self):
        """A phone whose API differs from the Pixel profile at the end of the run keeps the physical device deferred."""
        report, _ = run_physical(sdk="37", final_sdk="36")
        self.assertFalse(report["qualified"])
        self.assertIn("physical-device", report["deferred"])


def device_replies(qemu, available_kib=9_000_000, packages=(runner.APP, runner.APP + ".test"), sdk="37"):
    """Scripted adb answers for one device: emulator flag, platform, installed packages and free storage."""
    listed = "".join(f"package:{package}\n" for package in packages)
    return {("shell", "getprop", "ro.kernel.qemu"): qemu + "\n",
            ("shell", "getprop", "ro.build.version.sdk"): sdk + "\n",
            ("shell", "getprop", "ro.product.cpu.abi"): "arm64-v8a\n",
            ("shell", "pm", "list", "packages", runner.APP): listed,
            ("shell", "df", "-k", "/data"): "Filesystem 1K-blocks Used Available Use% Mounted on\n"
                                            f"/dev/block/dm-1 100000000 90000000 {available_kib} 90% /data\n"}


def run_physical(sdk, final_sdk=None):
    """Runs the runner against a scripted phone and fixture, returning the written report and every adb command.
    With [final_sdk], the platform the runner reads after the instrumentation differs from the one gated on."""
    replies = device_replies(qemu="", sdk=sdk)
    commands = []
    instrumented = []

    def adb(_adb, _serial, *args):
        """Answers each adb call from the script, recording it, with the final platform after the run."""
        commands.append(args)
        if args[0] == "reverse":
            return ""
        if instrumented and final_sdk and args == ("shell", "getprop", "ro.build.version.sdk"):
            return final_sdk + "\n"
        return replies[args]

    def instrument(*_args, **_kwargs):
        """Stands in for the instrumentation and marks that it ran."""
        instrumented.append(True)
        return "OK (1 test)"

    server = mock.MagicMock(server_port=4001)
    server.ledger.snapshot.return_value = []
    with tempfile.TemporaryDirectory() as directory, \
            mock.patch.object(runner, "adb_command", side_effect=adb), \
            mock.patch("apk_physical_runner.adb_command", side_effect=adb), \
            mock.patch.object(runner, "FixtureServer", return_value=server), \
            mock.patch.object(runner, "FixtureRelay", return_value=mock.MagicMock(port=4002)), \
            mock.patch.object(runner, "instrument", side_effect=instrument), \
            mock.patch.object(runner, "check_large_send", return_value={"passed": True}), \
            mock.patch.object(runner.time, "sleep"):
        output = Path(directory) / "out.json"
        try:
            runner.run("adb", "PHYSICAL000TEST", Path(directory), output, "pixel-api37-arm64",
                       physical_fixture_serial="PHYSICAL000TEST")
        except RuntimeError:
            pass  # An unqualified run raises after writing its report, which the caller judges.
        return json.loads(output.read_text()), commands

if __name__ == "__main__":
    unittest.main()
