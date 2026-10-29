"""Runner contracts: only an emulator with the isolated package reaches the instrumentation."""

import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

import background_receipt_runner as runner


class HostedWifiSetupTest(unittest.TestCase):
    """Reject misleading command results and keep privileged setup outside instrumentation."""

    def test_untrusted_environment_and_non_emulator_never_reach_adb(self):
        """The host precondition is unavailable to local/reference and physical-device callers."""
        for environment, serial in (({}, "emulator-5554"), ({"GITHUB_ACTIONS": "true"}, "PHYSICAL000TEST")):
            with mock.patch.dict(runner.os.environ, environment, clear=True), \
                    mock.patch.object(runner, "adb_command") as adb, self.assertRaises(ValueError):
                runner.prepare_ci_wifi("adb", serial, {})
            adb.assert_not_called()

    def test_wrong_image_or_false_emulator_never_changes_privileges_or_networks(self):
        """Read-only identity checks must complete before either root or Wi-Fi commands are admitted."""
        for property_name in ("ro.kernel.qemu", "ro.build.version.sdk", "ro.product.cpu.abi", "ro.debuggable"):
            with mock.patch.dict(runner.os.environ, GITHUB_ACTIONS="true"), \
                    mock.patch.object(runner, "adb_command") as adb, self.assertRaises(ValueError):
                expected = {"ro.kernel.qemu": "1", "ro.build.version.sdk": "34",
                            "ro.product.cpu.abi": "x86_64", "ro.debuggable": "1"}
                adb.side_effect = lambda _a, _s, *args, **_kw: "wrong" if args[-1] == property_name else expected[args[-1]]
                runner.prepare_ci_wifi("adb", "emulator-5554", {})
            self.assertTrue(all(call.args[2:4] == ("shell", "getprop") for call in adb.call_args_list))

    def test_single_association_restores_shell_without_claiming_network_qualification(self):
        """The request is sent once as root; normal shell and existing Android assertions own the subsequent run."""
        calls, report = [], {}
        uid = "2000"

        def adb(_adb, serial, *args, **kwargs):
            """Model only the fixed platform outputs, including the privilege transition."""
            nonlocal uid
            self.assertEqual("emulator-5554", serial)
            self.assertGreater(kwargs["timeout"], 0)
            self.assertLessEqual(kwargs["timeout"], 10)
            calls.append(args)
            if args[:2] == ("shell", "getprop"):
                return {"ro.kernel.qemu": "1", "ro.build.version.sdk": "34",
                        "ro.product.cpu.abi": "x86_64", "ro.debuggable": "1"}[args[2]]
            if args == ("root",):
                uid = "0"
            if args == ("unroot",):
                uid = "2000"
            if args == ("shell", "id", "-u"):
                return uid
            if "connect-network" in args:
                self.assertEqual("0", uid)
                return "Connection initiated \n"
            return ""

        with mock.patch.dict(runner.os.environ, GITHUB_ACTIONS="true"), \
                mock.patch.object(runner, "adb_command", side_effect=adb):
            runner.prepare_ci_wifi("adb", "emulator-5554", report)
        self.assertEqual("association-requested", report["wifi_setup"]["phase"])
        self.assertNotIn("qualified", report["wifi_setup"])
        self.assertTrue(report["wifi_setup"]["shell_restored"])
        self.assertEqual(1, calls.count(("shell", "cmd", "wifi", "connect-network", "AndroidWifi", "open")))
        self.assertFalse(any("data" in call or "airplane-mode" in call or "reverse" in call for call in calls))

    def test_zero_exit_failure_and_command_timeout_restore_shell_and_fail(self):
        """The Android command may exit zero after a failed callback; transport failures must also restore shell."""
        for response in ("Connection failed\n", "", subprocess.TimeoutExpired("adb", 10)):
            report, calls = {}, []
            uid = "2000"

            def adb(_adb, _serial, *args, **_kwargs):
                """Accept guards, then fail the association while tracking mandatory cleanup."""
                nonlocal uid
                calls.append(args)
                if args[:2] == ("shell", "getprop"):
                    return {"ro.kernel.qemu": "1", "ro.build.version.sdk": "34",
                            "ro.product.cpu.abi": "x86_64", "ro.debuggable": "1"}[args[2]]
                if args == ("root",):
                    uid = "0"
                if args == ("unroot",):
                    uid = "2000"
                if args == ("shell", "id", "-u"):
                    return uid
                if "connect-network" in args:
                    if isinstance(response, Exception):
                        raise response
                    return response
                return ""

            with mock.patch.dict(runner.os.environ, GITHUB_ACTIONS="true"), \
                    mock.patch.object(runner, "adb_command", side_effect=adb), \
                    self.assertRaises((ValueError, subprocess.TimeoutExpired)):
                runner.prepare_ci_wifi("adb", "emulator-5554", report)
            self.assertEqual(1, calls.count(("unroot",)))
            self.assertTrue(report["wifi_setup"]["shell_restored"])
            self.assertNotIn("qualified", report["wifi_setup"])
            self.assertNotIn(("shell", "dumpsys", "connectivity"), calls)


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


    def test_failure_diagnostics_retains_only_a_category_and_fixture_line_numbers(self):
        """A failed resume names its source assertion without retaining exception text, accounts or foreign frames."""
        output = (
            "INSTRUMENTATION_STATUS: stack=kotlinx.coroutines.TimeoutCancellationException: private-account\n"
            "    at dev.ipf.whitenoise.android.media.IncomingAttachmentBackgroundDownloadAndroidTest"
            "$runResume$2.invokeSuspend(IncomingAttachmentBackgroundDownloadAndroidTest.kt:445)\n"
            "    at dev.ipf.whitenoise.android.media.IncomingAttachmentBackgroundDownloadAndroidTest"
            ".runResume(IncomingAttachmentBackgroundDownloadAndroidTest.kt:447)\n"
            "    at private.Account.read(Private.kt:123)\n"
            "INSTRUMENTATION_RESULT: stream=private-account\nINSTRUMENTATION_CODE: -1\n"
        )
        diagnostic = runner.failure_diagnostics(output)
        self.assertEqual({"kind": "timeout", "fixture_lines": [445, 447]}, diagnostic)
        self.assertNotIn("private", json.dumps(diagnostic))

    def test_failure_diagnostics_does_not_infer_a_reason_without_a_stack_header(self):
        """A timeout mentioned in arbitrary output cannot stand in for the instrumentation's real exception."""
        diagnostic = runner.failure_diagnostics("private text says TimeoutException\nINSTRUMENTATION_CODE: -1\n")
        self.assertEqual({"kind": "instrumentation-failure", "fixture_lines": []}, diagnostic)

    def test_suspend_boundaries_are_closed_and_never_export_stack_messages(self):
        """Known suspend helpers retain bounded source positions; arbitrary frames and identities stay private."""
        output = (
            "INSTRUMENTATION_STATUS: stack=kotlinx.coroutines.TimeoutCancellationException: private-account\n"
            "    at dev.ipf.whitenoise.android.media.AndroidAttachmentFixtureSenderKt$awaitAndroidFixtureReferences$2"
            ".invokeSuspend(AndroidAttachmentFixtureSender.kt:232)\n"
            "    at dev.ipf.whitenoise.android.media.MediaLifecycleAttachmentProbe$projectRequests$2"
            ".invokeSuspend(MediaLifecycleAttachmentProbe.kt:422)\n"
            "    at private.Account.read(Private.kt:123)\n"
        )
        diagnostic = runner.failure_diagnostics(output)
        self.assertEqual(diagnostic, {"kind": "timeout", "fixture_lines": [], "boundary_lines": [
            {"boundary": "receiver-projection", "line": 422}, {"boundary": "sender-publication", "line": 232},
        ]})
        self.assertNotIn("private", json.dumps(diagnostic))

    def test_progress_boundaries_alone_never_qualify_the_fixture(self):
        """A closed progress stage is useful on failure but supplies none of the required device/checker facts."""
        from background_receipt_checker import check_background_receipt
        self.assertFalse(check_background_receipt(
            [{"phase": "background-receipt-stage", "stage": "network_policy_start"}], [],
        )["passed"])

    def test_setup_timeout_without_frames_retains_closed_metrics_and_fails_the_attempt(self):
        """Network boundaries and policy waits survive report writing while the original timeout still fails."""
        diagnostics = [
            {
                "phase": "background-receipt-policy-timeout", "case": "NetworkHeld", "stage": stage,
                "allowed_types": [], "expected_types": ["Video"], "wifi_only_types": ["Video"],
                "networks": ["WiFi", "Metered"], "account_state": "SignedIn", "paused": False,
                "validated_internet": True, "approval_applied": False,
            }
            for stage in ("HostPolicy", "NativeApproval")
        ] + [
            {"phase": "background-receipt-stage", "stage": stage}
            for stage in (
                "wifi_command_start", "wifi_command_complete", "wifi_snapshot_wait_start",
                "wifi_snapshot_confirmed", "wifi_validation_start", "wifi_validation_complete",
                "policy_update_start", "policy_update_complete",
            )
        ]
        for diagnostic in diagnostics:
            instrument_output = (
                "INSTRUMENTATION_STATUS: controlled_attachment_json=" + json.dumps(diagnostic) + "\n"
                "INSTRUMENTATION_STATUS: stack=kotlinx.coroutines.TimeoutCancellationException: private-account\n"
                "FAILURES!!!\nINSTRUMENTATION_CODE: -1\n"
            )
            server = mock.Mock(server_port=4001, ledger=mock.Mock(snapshot=mock.Mock(return_value=[])))
            relay = mock.Mock(port=4002)

            def adb(_adb, _serial, *args):
                """Only the runner's fixed emulator and package probes receive synthetic replies."""
                if "ro.kernel.qemu" in args:
                    return "1\n"
                if "list" in args:
                    return f"package:{runner.APP}\n"
                if "ro.build.version.sdk" in args:
                    return "30\n"
                return "arm64-v8a\n"

            with self.subTest(stage=diagnostic["stage"]), tempfile.TemporaryDirectory() as directory, \
                    mock.patch.object(runner, "adb_command", side_effect=adb), \
                    mock.patch.object(runner, "FixtureServer", return_value=server), \
                    mock.patch.object(runner, "FixtureRelay", return_value=relay), \
                    mock.patch.object(runner, "instrument", return_value=instrument_output) as instrument, \
                    mock.patch.object(runner.time, "sleep"):
                output = Path(directory) / "out.json"
                with self.assertRaises(RuntimeError):
                    runner.run("adb", "emulator-5554", Path(directory), output)
                report = json.loads(output.read_text())
            self.assertEqual([diagnostic], report["metrics"])
            self.assertEqual({"kind": "timeout", "fixture_lines": []}, report["stages"][0]["failure"])
            self.assertFalse(report["qualified"])
            self.assertFalse(report["instrumentation_passed"])
            self.assertFalse(report["process_ended_abruptly"])
            self.assertEqual(1, instrument.call_count, "a timeout never launches resume")
            self.assertNotIn("private-account", json.dumps(report))

    def test_only_the_held_request_ending_releases_the_hand_off(self):
        """An unrelated disconnect after the stop does not end the wait; the held request's own disconnect does."""
        events = [
            {"seq": 1, "request": 7, "kind": "held"},
            {"seq": 2, "request": None, "kind": "platform_stop_marker"},
            {"seq": 3, "request": 9, "kind": "disconnect"},
        ]
        self.assertFalse(runner.held_request_ended(events))
        self.assertTrue(runner.held_request_ended(events + [{"seq": 4, "request": 7, "kind": "hold_timeout"}]))
        self.assertFalse(runner.held_request_ended([{"seq": 1, "request": 7, "kind": "disconnect"}]))


    def test_an_interrupted_request_still_open_stops_the_attempt_before_resume(self):
        """A resume must not overlap the interrupted request, so an unseen disconnect ends the attempt as failed."""

        class Service:
            """A stand-in for the fixture server and relay: no sockets, an empty ledger."""

            server_port = 4001
            port = 4002

            def __init__(self, *_):
                self.ledger = mock.Mock(snapshot=mock.Mock(return_value=[]))

            def serve_forever(self):
                """Returns at once, as a stopped server would."""

            def shutdown(self):
                """Nothing to stop."""

            def server_close(self):
                """Nothing to close."""

        def adb(_adb, _serial, *args):
            """Answers the emulator, package and property probes the runner makes."""
            if "ro.kernel.qemu" in args:
                return "1\n"
            if "list" in args:
                return f"package:{runner.APP}\n"
            return ""

        stages = []
        with tempfile.TemporaryDirectory() as directory, \
                mock.patch.object(runner, "adb_command", side_effect=adb), \
                mock.patch.object(runner, "FixtureServer", Service), \
                mock.patch.object(runner, "FixtureRelay", Service), \
                mock.patch.object(runner, "instrument", side_effect=lambda *a: stages.append(a[3]) or "out"), \
                mock.patch.object(runner, "ended_abruptly", return_value=True), \
                mock.patch.object(runner, "release_held_body"), \
                mock.patch.object(runner, "await_held_disconnect", return_value=False), \
                mock.patch.object(runner, "metrics_of", return_value=[]), \
                mock.patch.object(runner, "result_lines", return_value=[]), \
                mock.patch.object(runner.time, "sleep"):
            output = Path(directory) / "out.json"
            with self.assertRaises(RuntimeError):
                runner.run("adb", "emulator-5554", Path(directory), output)
            report = json.loads(output.read_text())
        self.assertEqual(["main"], stages, "resume was never launched")
        self.assertFalse(report["instrumentation_passed"])
        self.assertEqual("held_request_still_open", report["stages"][-1]["skipped"])


if __name__ == "__main__":
    unittest.main()
