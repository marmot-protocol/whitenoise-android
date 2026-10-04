"""Runner contracts: only an emulator and declared links reach adb, and the plan is bounded and well formed."""

from pathlib import Path
import tempfile
import unittest
from unittest import mock

import matrix_runner as runner


class MatrixRunnerTest(unittest.TestCase):
    """Host-only guard rails; Android behavior is qualified by the device run, not by these."""

    def test_real_device_unknown_profile_or_link_is_refused_before_any_adb_call(self):
        """The matrix is for disposable emulators only; a physical serial never reaches adb."""
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(runner, "adb_command") as adb:
            output = Path(directory) / "out.json"
            for serial, profile, links in (("PHYSICAL000TEST", "reference-api30-arm64", ("unshaped",)),
                                           ("emulator-5554", "pixel-api37-arm64", ("unshaped",)),
                                           ("emulator-5554", "no-such-profile", ("unshaped",)),
                                           ("emulator-5554", "reference-api30-arm64", ("dialup",)),
                                           ("emulator-5554", "reference-api30-arm64", ())):
                with self.assertRaises(ValueError):
                    runner.run("adb", serial, Path(directory), output, profile, links)
            adb.assert_not_called()

    def test_every_declared_link_is_bounded_and_affordable(self):
        """Each plan stays within the sender's file limit and the repetition bound, and a constrained link is small."""
        for name, ((down, up, latency), plan) in runner.LINKS.items():
            self.assertTrue(all(0 < size <= 32 * 1024 * 1024 and 0 < reps <= 50 for size, reps in plan), name)
            self.assertTrue(all(v >= 0 for v in (down, up, latency)), name)
            if up:
                # Total upload time on the declared link stays under ten minutes.
                seconds = sum(size * 8 * reps for size, reps in plan) / (up * 1000)
                self.assertLess(seconds, 600, name)

    def test_plan_argument_round_trips_the_probe_format(self):
        """The probe parses size:repetitions pairs separated by commas."""
        self.assertEqual("65536:3,1048576:2", runner.plan_argument([(65536, 3), (1048576, 2)]))

    def test_the_probe_command_targets_only_the_isolated_package(self):
        """The instrumentation names the isolated fixture package, with no install, uninstall or clear flag."""
        with mock.patch.object(runner.subprocess, "run") as run:
            run.return_value.stdout = "OK (1 test)"
            runner.instrument("adb", "emulator-5554", (4001, 4002), "foreground", "3b6c9c1e-0000-4000-8000-000000000000",
                              [(65536, 1)])
        command = run.call_args.args[0]
        self.assertEqual(["adb", "-s", "emulator-5554"], command[:3])
        self.assertIn("fixtureMatrixPlan", command)
        self.assertIn("65536:1", command)
        self.assertTrue(command[-1].startswith("dev.ipf.whitenoise.android.medialatency.test/"))
        self.assertFalse({"uninstall", "install", "clear"} & set(command))


if __name__ == "__main__":
    unittest.main()
