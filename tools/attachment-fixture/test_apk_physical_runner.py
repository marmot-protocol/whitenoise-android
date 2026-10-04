"""Physical runner contracts: refusal before adb, state changes scoped to the isolated identity, restoration, redaction."""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock
import zipfile

import apk_installer_runner
import apk_physical_runner as runner

SERIAL = "PHYSICAL000TEST"
PROFILE = runner.PHYSICAL_PROFILE
CONFIRMED = {"owner_present_device_idle": True, "allow_installer_on_screen": True,
             "allow_install_app_op_toggle": True}
PLAY_CONFIRMED = {"owner_present_device_idle": True, "allow_installer_on_screen": True}
FORBIDDEN = {"uninstall", "clear", "-d", "-g", "install", "force-stop", "disable", "disable-user"}


class FakeDevice:
    """A scripted adb that records every command and answers read-only queries like a Pixel on API 37."""

    def __init__(self, installed=runner.IDENTITY, qemu="", api="37", abi="arm64-v8a",
                 op_line="REQUEST_INSTALL_PACKAGES: default", paths=None, digests=None):
        """Configure the device facts a test needs; everything else answers an empty string."""
        self.installed, self.qemu, self.api, self.abi, self.op_line = installed, qemu, api, abi, op_line
        self.paths = paths or {p: f"/data/app/~~x/{p}-y/base.apk" for p in runner.IDENTITY}
        self.digests = digests or {}
        self.calls = []

    def adb(self, _, serial, *args):
        """Answer one adb invocation and remember it."""
        self.calls.append((serial,) + args)
        if args[:3] == ("shell", "getprop", "ro.kernel.qemu"):
            return self.qemu + "\n"
        if args[:3] == ("shell", "getprop", "ro.build.version.sdk"):
            return self.api + "\n"
        if args[:3] == ("shell", "getprop", "ro.product.cpu.abi"):
            return self.abi + "\n"
        if args[:4] == ("shell", "pm", "list", "packages"):
            return "".join(f"package:{p}\n" for p in self.installed)
        if args[:3] == ("shell", "pm", "path"):
            return f"package:{self.paths[args[3]]}\n"
        if args[:3] == ("shell", "appops", "get"):
            return self.op_line + "\n"
        if args[:2] == ("shell", "sha256sum"):
            return f"{self.digests.get(args[2], '0' * 64)}  {args[2]}\n"
        if args[:1] == ("pull",):
            Path(args[2]).write_bytes(b"previous-" + args[1].encode())
        return ""

    def states(self):
        """Every command that could change the phone, for assertions that none or only the expected ones ran."""
        return [c for c in self.calls if c[1] in ("install", "uninstall", "pull")
                or (c[1] == "reverse" and c[2] != "--list")
                or (c[1] == "shell" and c[2] in ("appops", "am", "pm") and c[3] in ("set", "instrument", "clear",
                                                                                     "uninstall", "install"))]


def patched(device, instrument=None):
    """Patch both modules' adb binding plus the probe launcher; the shared helpers see the same scripted device."""
    launcher = instrument or mock.Mock(return_value="OK (1 test)")
    return (mock.patch.object(runner, "adb_command", side_effect=device.adb),
            mock.patch.object(apk_installer_runner, "adb_command", side_effect=device.adb),
            mock.patch.object(runner, "instrument", side_effect=launcher))


def large_payload(directory):
    """A 30 MiB APK-shaped archive, enough for the runner's shape and range validation."""
    path = Path(directory) / "large.apk"
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("AndroidManifest.xml", bytes(1000))
        info = zipfile.ZipInfo("assets/pad.bin")
        info.compress_type = zipfile.ZIP_STORED
        with archive.open(info, "w") as entry:
            for _ in range(30):
                entry.write(bytes(1024 * 1024))
    return path


class RefusalTest(unittest.TestCase):
    """Nothing reaches adb until every explicit owner confirmation and the device selection are correct."""

    def run_with(self, device, **overrides):
        """Invoke the runner with the standard confirmed Zapstore arguments plus overrides."""
        serial = overrides.pop("serial", SERIAL)
        arguments = {"distribution": "Zapstore", "physical_fixture_serial": SERIAL, "budget_profile": PROFILE,
                     **CONFIRMED, **overrides}
        with tempfile.TemporaryDirectory() as directory:
            patches = patched(device)
            with patches[0], patches[1], patches[2]:
                runner.run("adb", serial, Path(directory) / "root", Path(directory) / "report.json", **arguments)

    def test_each_missing_confirmation_is_refused_before_any_adb_call(self):
        """Dropping any one flag, or giving Play the app-op flag, refuses without touching the device."""
        device = FakeDevice()
        for missing in CONFIRMED:
            with self.assertRaises(ValueError, msg=missing):
                self.run_with(device, **{missing: False})
        with self.assertRaises(ValueError):
            self.run_with(device, distribution="Play", allow_install_app_op_toggle=True)
        with self.assertRaises(ValueError):
            self.run_with(device, distribution="Play", allow_install_app_op_toggle=False, no_installer_branch=True)
        with self.assertRaises(ValueError):
            self.run_with(device, distribution="Other")
        self.assertEqual([], device.calls)

    def test_emulator_serial_mismatch_or_wrong_profile_is_refused_before_adb(self):
        """The physical gate never falls back to a default device, an emulator or an emulator profile."""
        device = FakeDevice()
        for overrides in ({"serial": "emulator-5554", "physical_fixture_serial": "emulator-5554"},
                          {"physical_fixture_serial": "other"}, {"physical_fixture_serial": None},
                          {"budget_profile": "reference-api30-arm64"}, {"budget_profile": None}):
            with self.assertRaises(ValueError, msg=overrides):
                self.run_with(device, **overrides)
        self.assertEqual([], device.calls)

    def test_device_kind_profile_and_identity_are_verified_before_any_state_change(self):
        """An emulator answering, a wrong API level or a missing isolated identity stop the run while still read-only."""
        for device in (FakeDevice(qemu="1"), FakeDevice(api="36"), FakeDevice(installed=(runner.TEST_APP,)),
                       FakeDevice(installed=())):
            with self.assertRaises(ValueError):
                self.run_with(device)
            self.assertEqual([], device.states(), device.calls)
            self.assertFalse(any(FORBIDDEN & set(call) for call in device.calls))

    def test_large_apk_is_validated_before_adb(self):
        """A payload outside the shape or range is refused before the device is contacted."""
        device = FakeDevice()
        with tempfile.TemporaryDirectory() as directory:
            small = Path(directory) / "small.apk"
            with zipfile.ZipFile(small, "w") as archive:
                archive.writestr("AndroidManifest.xml", b"x")
            for path in (small, Path(directory) / "absent.apk"):
                with self.assertRaises(ValueError):
                    self.run_with(device, large_apk=path, build_tools=Path("build-tools"))
        self.assertEqual([], device.calls)

    def test_large_apk_must_pass_apksigner_before_adb(self):
        """An unsigned or invalidly signed payload, or a run without build-tools to verify it, never reaches the device."""
        device = FakeDevice()
        with tempfile.TemporaryDirectory() as directory:
            payload = large_payload(directory)
            with self.assertRaises(ValueError):
                self.run_with(device, large_apk=payload)
            for failure in (subprocess.CalledProcessError(1, "apksigner"), ValueError("expected exactly one signer"),
                            OSError("apksigner missing")):
                with mock.patch.object(runner, "signer_digest", side_effect=failure), self.assertRaises(ValueError):
                    self.run_with(device, large_apk=payload, build_tools=Path("build-tools"))
            with mock.patch.object(runner, "signer_digest", return_value="a" * 64) as verified:
                self.assertEqual(payload, runner.validate_large_apk(payload, Path("build-tools")))
            self.assertEqual(Path("build-tools") / "apksigner", verified.call_args.args[0])
        self.assertEqual([], device.calls)


class RunTest(unittest.TestCase):
    """A confirmed run changes only the isolated package's app-op and its own reverses, and restores both."""

    def execute(self, device, distribution, directory, instrument=None, **overrides):
        """Run to completion, expecting the unqualified failure, and return the written report."""
        confirmed = CONFIRMED if distribution == "Zapstore" else PLAY_CONFIRMED
        output = Path(directory) / "report.json"
        patches = patched(device, instrument)
        if overrides.get("large_apk") is not None:
            overrides.setdefault("build_tools", Path("build-tools"))
        with patches[0], patches[1], patches[2], mock.patch.object(
            runner, "signer_digest", return_value="a" * 64
        ), self.assertRaises(RuntimeError):
            runner.run("adb", SERIAL, Path(directory) / "root", output, distribution, SERIAL, PROFILE, **confirmed,
                       **overrides)
        return json.loads(output.read_text())

    def test_zapstore_run_toggles_only_the_isolated_app_op_and_restores_the_original_mode(self):
        """The app-op moves default, deny, allow for the stages, then back to the recorded original, on APP only."""
        device = FakeDevice(op_line="REQUEST_INSTALL_PACKAGES: allow; time=+1d2h3m4s ago")
        with tempfile.TemporaryDirectory() as directory:
            report = self.execute(device, "Zapstore", directory)
        sets = [c for c in device.calls if c[1:4] == ("shell", "appops", "set")]
        self.assertEqual(["default", "deny", "allow", "allow"], [c[-1] for c in sets])
        self.assertTrue(all(c[4] == runner.APP and c[5] == runner.INSTALL_OP for c in sets))
        self.assertEqual("allow", report["install_app_op_original"])
        self.assertTrue(report["install_app_op_restored"])
        self.assertEqual(["prepare", "dispatch-denied", "dispatch-allowed"], [s["stage"] for s in report["stages"]])
        reverses = [c for c in device.calls if c[1] == "reverse"]
        self.assertEqual(["--no-rebind", "--no-rebind", "--remove", "--remove"], [c[2] for c in reverses])
        self.assertFalse(report["reverse_cleanup_failed"])
        self.assertFalse(any(FORBIDDEN & set(call) for call in device.calls))

    def test_report_is_redacted_unqualified_and_lists_unselected_gaps_as_deferred(self):
        """Without probe metrics the report fails closed, names the physical scope and never carries the serial."""
        device = FakeDevice()
        with tempfile.TemporaryDirectory() as directory:
            report = self.execute(device, "Zapstore", directory)
            text = (Path(directory) / "report.json").read_text()
        self.assertNotIn(SERIAL, text)
        self.assertEqual(("physical", runner.SCOPE, False), (report["device_kind"], report["scope"], report["qualified"]))
        self.assertFalse(report["apk_check"]["passed"])
        self.assertFalse(report["budget_check"]["applicable"])
        for gap in ("cancel-retry", "large-apk-30-50mib", "no-installer-branch", "process-recreation-during-download",
                    "installation-confirmed", "genuine-no-installer-platform-state", "apk-above-32-mib"):
            self.assertIn(gap, report["deferred"])
        self.assertEqual({"api": "37", "abi": "arm64-v8a"}, report["environment"])

    def test_only_a_cancel_retry_run_treats_a_disconnect_as_a_terminal_request(self):
        """The cancelled attempt never completes, so only the run that cancels on purpose waits for its disconnect."""
        for cancel_retry, expected in ((False, ("complete",)), (True, ("complete", "disconnect"))):
            device = FakeDevice()
            with tempfile.TemporaryDirectory() as directory, mock.patch.object(
                runner, "wait_for_completion", return_value=([], True)
            ) as wait:
                self.execute(device, "Zapstore", directory, cancel_retry=cancel_retry)
            self.assertEqual(expected, wait.call_args.kwargs["terminal_kinds"], cancel_retry)

    def test_selected_gaps_reach_the_probe_as_closed_selectors_and_leave_deferred(self):
        """Cancel-retry, the host payload and the no-installer branch are passed as `-e` pairs and recorded."""
        device = FakeDevice()
        launcher = mock.Mock(return_value="OK (1 test)")
        with tempfile.TemporaryDirectory() as directory:
            payload = large_payload(directory)
            report = self.execute(device, "Zapstore", directory, launcher, cancel_retry=True, large_apk=payload,
                                  no_installer_branch=True)
        extra = launcher.call_args.args[5]
        self.assertEqual(["-e", "fixtureApkCancelRetry", "true", "-e", "fixtureApkLargePayload", "large-apk",
                          "-e", "fixtureApkNoInstaller", "true"], extra)
        self.assertTrue(report["cancel_retry"] and report["large_apk"] and report["no_installer_simulated"])
        self.assertEqual(64, len(report["large_apk_sha256"]))
        for gap in ("cancel-retry", "large-apk-30-50mib", "no-installer-branch"):
            self.assertNotIn(gap, report["deferred"])
        self.assertIn("process-recreation-during-download", report["deferred"])

    def test_play_run_never_touches_app_ops(self):
        """A Play build has no installer, so no app-op is read, set or restored."""
        device = FakeDevice()
        with tempfile.TemporaryDirectory() as directory:
            report = self.execute(device, "Play", directory)
        self.assertFalse(any(c[1:3] == ("shell", "appops") for c in device.calls))
        self.assertEqual(["prepare", "dispatch-na"], [s["stage"] for s in report["stages"]])
        self.assertIsNone(report["install_app_op_original"])
        self.assertTrue(report["install_app_op_restored"])

    def test_a_setup_failure_before_the_app_op_is_captured_never_rewrites_it(self):
        """If the reverses cannot be set up, the owner's existing install permission is left exactly as it was."""

        class FailingReverse(FakeDevice):
            """A device whose reverse mapping is refused, as when another task already owns the port."""

            def adb(self, adb, serial, *args):
                """Refuse every reverse that adds a mapping, answer the rest as the scripted Pixel does."""
                if args[:1] == ("reverse",) and "--no-rebind" in args:
                    self.calls.append((serial,) + args)
                    raise subprocess.CalledProcessError(1, "adb")
                return super().adb(adb, serial, *args)

        device = FailingReverse(op_line="REQUEST_INSTALL_PACKAGES: allow; time=+1d ago")
        with tempfile.TemporaryDirectory() as directory:
            report = self.execute(device, "Zapstore", directory)
        self.assertEqual([], [c for c in device.calls if c[1:4] == ("shell", "appops", "set")])
        self.assertIsNone(report["install_app_op_original"])
        self.assertTrue(report["install_app_op_restored"])
        self.assertEqual([], report["stages"])

    def test_a_failed_stage_stops_the_sequence_but_still_restores_everything(self):
        """A failing prepare process ends the stages, yet the app-op and the reverses are restored."""
        device = FakeDevice()
        with tempfile.TemporaryDirectory() as directory:
            report = self.execute(device, "Zapstore", directory, mock.Mock(return_value="FAILURES!!!"))
        self.assertEqual([{"stage": "prepare", "permission": "default", "passed": False}], report["stages"])
        self.assertFalse(report["instrumentation_passed"])
        sets = [c[-1] for c in device.calls if c[1:4] == ("shell", "appops", "set")]
        self.assertEqual(["default", "default"], sets)
        self.assertEqual(2, sum(c[1:3] == ("reverse", "--remove") for c in device.calls))

    def test_private_debug_keeps_raw_transcripts_only_in_the_run_root(self):
        """Raw instrumentation text lands as private files per stage and never inside the report."""
        device = FakeDevice()
        with tempfile.TemporaryDirectory() as directory:
            report = self.execute(device, "Play", directory, mock.Mock(return_value="OK (1 test)\nsecret-line"),
                                  private_debug=True)
            transcripts = sorted(p.name for p in (Path(directory) / "root").glob("instrumentation-*.txt"))
            self.assertEqual(["instrumentation-dispatch-na.txt", "instrumentation-prepare.txt"], transcripts)
            self.assertEqual(0o600, (Path(directory) / "root" / "instrumentation-prepare.txt").stat().st_mode & 0o777)
        self.assertNotIn("secret-line", json.dumps(report))


class HelperTest(unittest.TestCase):
    """Pure helpers behave the same whether or not a device is attached."""

    def test_install_app_op_parses_known_modes_and_defaults_otherwise(self):
        """allow, deny and default are recognized, anything else is treated as default."""
        for printed, expected in (("REQUEST_INSTALL_PACKAGES: allow; time=+1s ago", "allow"),
                                  ("REQUEST_INSTALL_PACKAGES: deny", "deny"), ("No operations.", "default"),
                                  ("REQUEST_INSTALL_PACKAGES: ignore", "default"), ("", "default")):
            device = FakeDevice(op_line=printed)
            with mock.patch.object(runner, "adb_command", side_effect=device.adb):
                self.assertEqual(expected, runner.install_app_op("adb", SERIAL), printed)

    def test_probe_arguments_and_deferred_follow_the_selection(self):
        """No selection means no selectors and every gap deferred; each selection removes exactly its gap."""
        self.assertEqual([], runner.probe_arguments())
        self.assertEqual(set(runner.ALWAYS_DEFERRED) | {"cancel-retry", "large-apk-30-50mib", "no-installer-branch"},
                         set(runner.deferred_for(False, False, False)))
        self.assertEqual(set(runner.ALWAYS_DEFERRED), set(runner.deferred_for(True, True, True)))
        self.assertEqual(["-e", "fixtureApkLargePayload", "large-apk"], runner.probe_arguments(large=True))

    def test_preflight_is_read_only(self):
        """Preflight issues only property, package, reverse-list and app-op reads."""
        device = FakeDevice(installed=(runner.TEST_APP,))
        with mock.patch.object(runner, "adb_command", side_effect=device.adb):
            facts = runner.preflight("adb", SERIAL, SERIAL, "Zapstore")
        self.assertEqual([], device.states())
        self.assertEqual(([runner.TEST_APP], False, False), (facts["installed_identity"], facts["identity_complete"],
                                                             facts["ready_for_run"]))
        self.assertNotIn("install_app_op", facts)
        self.assertTrue(all(c[1:3] in (("shell", "getprop"), ("shell", "pm"), ("reverse", "--list"), ("shell", "appops"))
                            for c in device.calls))


class InstallTest(unittest.TestCase):
    """The install command replaces only the isolated identity in place, after name and signer checks."""

    def setUp(self):
        """Candidate APK files and a private backup directory."""
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.app = self.root / "app.apk"
        self.test = self.root / "test.apk"
        self.app.write_bytes(b"app-bytes")
        self.test.write_bytes(b"test-bytes")
        self.backup = self.root / "backup"
        self.names = {str(self.app): runner.APP, str(self.test): runner.TEST_APP}

    def tearDown(self):
        """Remove candidates and receipts."""
        self.directory.cleanup()

    def install(self, device, names=None, digests=None, run_command=None, **flags):
        """Invoke install with patched aapt2 and apksigner readers and a scripted adb."""
        names = names or self.names
        digests = digests or {}
        command = run_command or mock.Mock(return_value=mock.Mock(stdout="Performing Streamed Install\nSuccess\n"))
        with mock.patch.object(runner, "adb_command", side_effect=device.adb), \
                mock.patch.object(runner, "package_name", side_effect=lambda _, apk, __: names.get(str(apk), "other")), \
                mock.patch.object(runner, "signer_digest", side_effect=lambda _, apk, __: digests.get(str(apk), "aa" * 32)):
            return runner.install_isolated("adb", SERIAL, SERIAL, self.app, self.test, self.backup, "/tools",
                                           run_command=command, **flags), command

    def test_install_refuses_without_confirmation_wrong_package_or_foreign_signer_and_never_uninstalls(self):
        """Each refusal happens before any install command; a foreign installed signer is never worked around."""
        device = FakeDevice()
        with self.assertRaises(ValueError):
            self.install(device)
        self.assertEqual([], device.calls)
        with self.assertRaises(ValueError):
            self.install(device, names={str(self.app): "dev.ipf.whitenoise.android.dev", str(self.test): runner.TEST_APP},
                         confirm_in_place_update=True)
        with self.assertRaises(ValueError):
            self.install(device, digests={str(self.test): "bb" * 32}, confirm_in_place_update=True)
        foreign = {str(self.backup / f"{runner.APP}.previous.apk"): "cc" * 32}
        with self.assertRaises(ValueError):
            self.install(device, digests=foreign, confirm_in_place_update=True)
        absent = FakeDevice(installed=(runner.TEST_APP,))
        with self.assertRaises(ValueError):
            self.install(absent, confirm_in_place_update=True)
        for scripted in (device, absent):
            self.assertFalse(any(c[1] == "install" for c in scripted.calls), scripted.calls)
            self.assertFalse(any(FORBIDDEN & set(call) for call in scripted.calls))

    def test_an_occupied_backup_directory_is_refused_before_any_copy_or_install(self):
        """Reusing a backup directory would overwrite the earlier restore copies, so nothing is touched."""
        self.backup.mkdir(mode=0o700)
        earlier = self.backup / f"{runner.APP}.previous.apk"
        earlier.write_bytes(b"earlier restore copy")
        device = FakeDevice()
        with self.assertRaises(ValueError):
            self.install(device, confirm_in_place_update=True)
        self.assertEqual(b"earlier restore copy", earlier.read_bytes())
        self.assertEqual([], [c for c in device.calls if c[1] in ("install", "pull")])
        self.assertEqual([earlier.name], [p.name for p in self.backup.iterdir()])

    def test_install_updates_app_then_test_in_place_for_user_zero_and_proves_the_bytes(self):
        """Exactly -r -t --user 0 per isolated package, previous APKs retained, device digests compared, receipt saved."""
        digests = {f"/data/app/~~x/{p}-y/base.apk": runner.sha256_of(apk)
                   for p, apk in zip(runner.IDENTITY, (self.app, self.test))}
        device = FakeDevice(digests=digests)
        receipt, command = self.install(device, confirm_in_place_update=True)
        installs = [call.args[0] for call in command.call_args_list]
        self.assertEqual([["adb", "-s", SERIAL, "install", "-r", "-t", "--user", "0", str(self.app)],
                          ["adb", "-s", SERIAL, "install", "-r", "-t", "--user", "0", str(self.test)]], installs)
        self.assertFalse(any({"-d", "-g", "uninstall", "clear"} & set(c) for c in installs))
        self.assertEqual(2, sum(c[1] == "pull" for c in device.calls))
        self.assertEqual(set(runner.IDENTITY), set(receipt["retained"]))
        self.assertEqual([], receipt["fresh"])
        self.assertEqual(runner.sha256_of(self.app), receipt["installed"][runner.APP]["sha256"])
        saved = self.backup / "install-receipt.json"
        self.assertEqual(0o600, saved.stat().st_mode & 0o777)
        self.assertEqual(receipt, json.loads(saved.read_text()))
        self.assertEqual(0o700, self.backup.stat().st_mode & 0o777)

    def test_fresh_install_of_an_absent_identity_needs_its_own_flag_and_retains_what_exists(self):
        """With the app absent, the extra flag allows the first install while the present test APK is still retained."""
        digests = {f"/data/app/~~x/{p}-y/base.apk": runner.sha256_of(apk)
                   for p, apk in zip(runner.IDENTITY, (self.app, self.test))}
        device = FakeDevice(installed=(runner.TEST_APP,), digests=digests)
        receipt, command = self.install(device, confirm_in_place_update=True, allow_fresh_install=True)
        self.assertEqual([runner.APP], receipt["fresh"])
        self.assertEqual([runner.TEST_APP], list(receipt["retained"]))
        self.assertEqual(2, len(command.call_args_list))

    def test_a_device_digest_mismatch_or_missing_success_is_an_error(self):
        """The receipt is only written when adb reported Success and the device holds the candidate bytes."""
        with self.assertRaises(RuntimeError):
            self.install(FakeDevice(), confirm_in_place_update=True)
        self.assertFalse((self.backup / "install-receipt.json").exists())
        # Every attempt needs its own backup directory, so the earlier attempt's restore copies are never overwritten.
        self.backup = self.root / "backup-second-attempt"
        failing = mock.Mock(return_value=mock.Mock(stdout="Failure [INSTALL_FAILED_TEST_ONLY]"))
        with self.assertRaises(RuntimeError):
            self.install(FakeDevice(), run_command=failing, confirm_in_place_update=True)
        self.assertFalse((self.backup / "install-receipt.json").exists())


if __name__ == "__main__":
    unittest.main()
