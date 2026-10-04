"""Recreation runner contracts: emulator only, the second process starts only after the first died, nothing is cleared."""

import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest import mock

import apk_installer_runner
import apk_recreation_runner as runner
from fixture_server import FixtureServer

FORBIDDEN = {"uninstall", "clear", "install", "force-stop", "disable", "disable-user", "-d", "-g"}
CRASH = "INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0\n"
HELD_STATUS = (
    "INSTRUMENTATION_STATUS: controlled_attachment_json="
    '{"phase": "apk-recreate-held", "case": "valid", "received_bytes": 1400000, "total_ciphertext_bytes": 4574292}\n'
    "INSTRUMENTATION_STATUS_CODE: 0\n"
)


class FakeAdb:
    """A scripted adb that records every command and answers like a disposable emulator."""

    def __init__(self, qemu="1", installed=True):
        """Configure the emulator facts a test needs."""
        self.calls, self.qemu, self.installed = [], qemu, installed

    def __call__(self, adb, serial, *args):
        """Record the command and answer the read-only queries the runner makes."""
        self.calls.append(args)
        if args[:2] == ("shell", "getprop"):
            return {"ro.kernel.qemu": self.qemu, "ro.build.version.sdk": "30", "ro.product.cpu.abi": "arm64-v8a"}[args[2]]
        if args[:3] == ("shell", "pm", "list"):
            return f"package:{runner.APP}\n" if self.installed else ""
        return ""

    def app_ops(self):
        """The install permission modes the runner set, in order."""
        return [c[-1] for c in self.calls if c[:3] == ("shell", "appops", "set")]


class RecreationRunnerTest(unittest.TestCase):
    """Host-only guard rails and ordering; Android behavior is qualified by the device run."""

    def test_real_device_unknown_profile_or_distribution_is_refused_before_any_adb_call(self):
        """The fixture is for disposable emulators only; a physical serial never reaches adb."""
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(runner, "adb_command") as adb:
            output = Path(directory) / "out.json"
            for serial, distribution, profile in (
                ("46131FDAS003CG", "Zapstore", "reference-api30-arm64"),
                ("emulator-5554", "Zapstore", "pixel-api37-arm64"),
                ("emulator-5554", "Other", "reference-api30-arm64"),
                ("emulator-5554", "Play", "no-such-profile"),
            ):
                with self.assertRaises(ValueError):
                    runner.run("adb", serial, Path(directory), output, distribution, profile)
            adb.assert_not_called()

    def test_a_non_emulator_or_missing_identity_is_refused_before_any_state_change(self):
        """A serial that merely looks like an emulator, or an absent isolated package, stops the run."""
        for fake in (FakeAdb(qemu="0"), FakeAdb(installed=False)):
            with tempfile.TemporaryDirectory() as directory, mock.patch.object(runner, "adb_command", fake):
                with self.assertRaises(ValueError):
                    runner.run("adb", "emulator-5554", Path(directory), Path(directory) / "out.json", "Play")
            self.assertEqual([], fake.app_ops())
            self.assertFalse([c for c in fake.calls if c[0] == "reverse"])

    def test_the_probe_command_names_only_the_isolated_package_and_the_requested_stage(self):
        """The instrumentation targets the isolated package, tolerates the crash, and carries no destructive flag."""
        with mock.patch.object(runner.subprocess, "run") as run:
            run.return_value.stdout = CRASH
            output = runner.instrument("adb", "emulator-5554", (4001, 4002), "recreate-hold",
                                       "3b6c9c1e-0000-4000-8000-000000000000")
        command = run.call_args.args[0]
        self.assertEqual(CRASH, output)
        self.assertFalse(run.call_args.kwargs["check"], "the first stage ends in a crash and must not raise")
        self.assertEqual(["adb", "-s", "emulator-5554"], command[:3])
        self.assertIn("recreate-hold", command)
        self.assertTrue(command[-1].startswith("dev.ipf.whitenoise.android.medialatency.test/"))
        self.assertFalse(FORBIDDEN & set(command))

    def test_only_a_non_passing_instrumentation_with_a_crash_marker_counts_as_an_abrupt_end(self):
        """A clean pass, a silent failure and an empty output are not process death."""
        self.assertTrue(runner.ended_abruptly(HELD_STATUS + CRASH))
        self.assertFalse(runner.ended_abruptly(CRASH), "a crash before the prefix was held is some other failure")
        self.assertFalse(runner.ended_abruptly("OK (1 test)\n"))
        self.assertFalse(runner.ended_abruptly("OK (1 test)\n" + CRASH))
        self.assertFalse(runner.ended_abruptly(HELD_STATUS))
        self.assertFalse(runner.ended_abruptly(""))

    def test_release_posts_to_the_fixture_and_the_disconnect_wait_reads_only_the_ledger(self):
        """The host releases the dead client's hold over HTTP and waits for the server's own disconnect event."""
        with tempfile.TemporaryDirectory() as directory:
            server = FixtureServer(directory)
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                start = len(server.ledger.snapshot())
                self.assertFalse(runner.await_disconnect(server, start, timeout=0.2))
                runner.release_held_body(server)
                self.assertEqual(1, sum(e["kind"] == "release_acquisition" for e in server.ledger.snapshot()[start:]))
                server.ledger.event(1, "t", "disconnect")
                self.assertTrue(runner.await_disconnect(server, start, timeout=2))
            finally:
                server.shutdown()
                server.server_close()
                thread.join(5)

    def execute(self, distribution, outputs):
        """Run to completion against scripted probe output, expecting the unqualified failure, and return the report."""
        fake = FakeAdb()
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "report.json"
            with mock.patch.object(runner, "adb_command", fake), \
                    mock.patch.object(apk_installer_runner, "adb_command", fake), \
                    mock.patch.object(runner, "instrument", side_effect=outputs) as instrument, \
                    mock.patch.object(runner, "release_held_body") as release, \
                    mock.patch.object(runner, "await_disconnect", return_value=True), \
                    mock.patch.object(runner, "wait_for_completion", return_value=([], True)), \
                    self.assertRaises(RuntimeError):
                runner.run("adb", "emulator-5554", Path(directory), output, distribution)
            return json.loads(output.read_text()), fake, instrument, release

    def test_the_second_process_starts_only_after_the_first_died_and_the_hold_was_released(self):
        """Zapstore sets the install permission to allow only between the two processes, then restores the default."""
        report, fake, instrument, release = self.execute("Zapstore", [HELD_STATUS + CRASH, "OK (1 test)\n"])
        self.assertEqual(["recreate-hold", "recreate-resume"], [c.args[3] for c in instrument.call_args_list])
        self.assertEqual(["default", "allow", "default"], fake.app_ops())
        release.assert_called_once()
        self.assertTrue(report["process_ended_abruptly"])
        self.assertTrue(report["interrupted_acquisition_ended"])
        self.assertFalse(report["recreation_check"]["passed"], "no ledger evidence was supplied")
        self.assertFalse(report["qualified"])
        self.assertEqual("apk-recreate-held", report["metrics"][0]["phase"])

    def test_a_crash_before_the_prefix_was_held_never_launches_the_resume_stage(self):
        """Being killed while starting is not the recreation under test, and the failed attempt keeps its reason."""
        report, _, instrument, release = self.execute("Zapstore", [CRASH])
        self.assertEqual(["recreate-hold"], [c.args[3] for c in instrument.call_args_list])
        release.assert_not_called()
        self.assertFalse(report["process_ended_abruptly"])
        self.assertIn("INSTRUMENTATION_RESULT: shortMsg=Process crashed.", report["stages"][0]["instrumentation"])

    def test_play_never_touches_the_install_permission_between_processes(self):
        """Play sets only the default before and after, and never allows installs."""
        _, fake, _, _ = self.execute("Play", [HELD_STATUS + CRASH, "OK (1 test)\n"])
        self.assertNotIn("allow", fake.app_ops())

    def test_a_first_process_that_exits_cleanly_never_reaches_the_resume_stage(self):
        """If the hold stage did not die abruptly there is nothing to recreate, so no second process is launched."""
        report, fake, instrument, release = self.execute("Zapstore", ["OK (1 test)\n"])
        self.assertEqual(["recreate-hold"], [c.args[3] for c in instrument.call_args_list])
        release.assert_not_called()
        self.assertFalse(report["process_ended_abruptly"])
        self.assertFalse(report["instrumentation_passed"])
        self.assertEqual(["default", "default"], fake.app_ops())

    def test_nothing_destructive_is_ever_sent_to_adb_and_cleanup_always_runs(self):
        """No uninstall, clear or force-stop is issued, the default permission is restored and reverses are removed."""
        _, fake, _, _ = self.execute("Zapstore", [HELD_STATUS + CRASH, "OK (1 test)\n"])
        for call in fake.calls:
            self.assertFalse(FORBIDDEN & set(call), call)
        reverses = [c[1] for c in fake.calls if c[0] == "reverse"]
        self.assertEqual(["--no-rebind", "--no-rebind", "--remove", "--remove"], reverses)

    def test_the_report_names_what_it_does_not_claim(self):
        """Installation, a physical device, system-initiated death and durable worker resume stay deferred."""
        report, _, _, _ = self.execute("Zapstore", [HELD_STATUS + CRASH, "OK (1 test)\n"])
        for gap in ("installation-confirmed", "physical-device", "system-initiated-recreation", "durable-worker-resume"):
            self.assertIn(gap, report["deferred"])


if __name__ == "__main__":
    unittest.main()
