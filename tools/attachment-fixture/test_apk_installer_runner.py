"""Runner contracts: only an emulator and a declared distribution reach adb, and nothing is installed or cleared."""

from pathlib import Path
import tempfile
import unittest
from unittest import mock

import apk_installer_runner as runner


class ApkInstallerRunnerTest(unittest.TestCase):
    """Host-only guard rails; Android behavior is qualified by the device run, not by these."""

    def test_real_device_unknown_profile_or_distribution_is_refused_before_any_adb_call(self):
        """The fixture is for disposable emulators only; a physical serial never reaches adb."""
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(runner, "adb_command") as adb:
            output = Path(directory) / "out.json"
            for serial, distribution, profile in (
                ("PHYSICAL000TEST", "Zapstore", "reference-api30-arm64"),
                ("emulator-5554", "Zapstore", "pixel-api37-arm64"),
                ("emulator-5554", "Other", "reference-api30-arm64"),
                ("emulator-5554", "Play", "no-such-profile"),
            ):
                with self.assertRaises(ValueError):
                    runner.run("adb", serial, Path(directory), output, distribution, profile)
            adb.assert_not_called()

    def test_the_probe_command_names_only_the_isolated_package_and_controlled_ports(self):
        """The instrumentation targets the isolated fixture package, with no install, uninstall or clear flag."""
        with mock.patch.object(runner.subprocess, "run") as run:
            run.return_value.stdout = "OK (1 test)"
            runner.instrument("adb", "emulator-5554", (4001, 4002), "prepare", "3b6c9c1e-0000-4000-8000-000000000000")
        command = run.call_args.args[0]
        self.assertEqual(["adb", "-s", "emulator-5554"], command[:3])
        self.assertIn(runner.PROBE, command)
        self.assertTrue(command[-1].startswith("dev.ipf.whitenoise.android.medialatency.test/"))
        self.assertFalse({"uninstall", "install", "clear"} & set(command))
        self.assertIn("fixtureApkStage", command)

    def test_extra_selectors_sit_before_the_runner_component_and_default_to_none(self):
        """Gap selectors are plain `-e` pairs placed before the instrumentation component, absent by default."""
        with mock.patch.object(runner.subprocess, "run") as run:
            run.return_value.stdout = "OK (1 test)"
            runner.instrument("adb", "emulator-5554", (4001, 4002), "prepare", "3b6c9c1e-0000-4000-8000-000000000000",
                              ["-e", "fixtureApkCancelRetry", "true"])
            with_extra = run.call_args.args[0]
            runner.instrument("adb", "emulator-5554", (4001, 4002), "prepare", "3b6c9c1e-0000-4000-8000-000000000000")
            plain = run.call_args.args[0]
        self.assertEqual(["-e", "fixtureApkCancelRetry", "true"], with_extra[-4:-1])
        self.assertEqual(with_extra[-1], plain[-1])
        self.assertNotIn("fixtureApkCancelRetry", plain)

    def test_permission_is_set_only_on_the_isolated_package_with_a_known_mode(self):
        """The host toggles one app-op on the fixture package; anything else is refused before adb."""
        with mock.patch.object(runner, "adb_command") as adb:
            runner.set_install_permission("adb", "emulator-5554", "deny")
            adb.assert_called_once_with("adb", "emulator-5554", "shell", "appops", "set", runner.APP,
                                        "REQUEST_INSTALL_PACKAGES", "deny")
            with self.assertRaises(ValueError):
                runner.set_install_permission("adb", "emulator-5554", "grant-all")

    def test_each_distribution_runs_only_the_stages_it_can_reach(self):
        """A self-update build is exercised denied then allowed; Play has no installer stage to reach."""
        self.assertEqual(["prepare", "dispatch-denied", "dispatch-allowed"],
                         [stage for stage, _ in runner.stages_for("Zapstore")])
        self.assertEqual(["prepare", "dispatch-na"], [stage for stage, _ in runner.stages_for("Play")])


if __name__ == "__main__":
    unittest.main()
