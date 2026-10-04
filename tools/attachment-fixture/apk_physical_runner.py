"""Run the received-APK probe on one explicitly authorized physical device; `run` installs, uninstalls and clears nothing."""

import argparse
import json
import os
from pathlib import Path
import subprocess
import threading
import time
import uuid

from apk_checker import cases_for, check_apk
from apk_installer_runner import instrument, set_install_permission, stages_for
from apk_payload import MAX_BYTES, MIN_BYTES, has_manifest, sha256_of, signer_digest
from budget_checker import PROFILES
from device_runner import APP, adb_command
from fixture_relay import FixtureRelay
from fixture_server import FixtureServer
from media_lifecycle_runner import metrics_of, passed, wait_for_completion

SCOPE = "android-received-apk-platform-open-physical"
PHYSICAL_PROFILE = "pixel-api37-arm64"
TEST_APP = APP + ".test"
IDENTITY = (APP, TEST_APP)
LARGE_PAYLOAD_TOKEN = "large-apk"
INSTALL_OP = "REQUEST_INSTALL_PACKAGES"
OP_MODES = ("allow", "deny", "default")
INSTALL_TIMEOUT_SECONDS = 600
ALWAYS_DEFERRED = ("installation-confirmed", "process-recreation-during-download",
                   "genuine-no-installer-platform-state", "apk-above-32-mib", "representative-performance",
                   "manual-ui-flows")


def verify_physical_device(adb, serial, physical_serial, budget_profile):
    """Refuse before any adb call unless serial, opt-in serial and profile agree, then confirm the device itself."""
    if not serial or serial != physical_serial or serial.startswith("emulator-"):
        raise ValueError("the physical gate needs --serial and --physical-fixture-serial to name one non-emulator device")
    if budget_profile != PHYSICAL_PROFILE:
        raise ValueError("the physical gate requires the explicit Pixel profile")
    if adb_command(adb, serial, "shell", "getprop", "ro.kernel.qemu").strip() == "1":
        raise ValueError("device is an emulator, use the emulator runner")
    environment = {
        "api": adb_command(adb, serial, "shell", "getprop", "ro.build.version.sdk").strip(),
        "abi": adb_command(adb, serial, "shell", "getprop", "ro.product.cpu.abi").strip(),
    }
    if environment != PROFILES[budget_profile]:
        raise ValueError("device API/ABI does not match the physical profile")
    return environment


def installed_identity(adb, serial):
    """Which of the isolated app and test packages the device reports installed, read-only."""
    listed = adb_command(adb, serial, "shell", "pm", "list", "packages", APP).splitlines()
    return tuple(package for package in IDENTITY if f"package:{package}" in listed)


def require_confirmations(distribution, owner_present_device_idle, allow_installer_on_screen,
                          allow_install_app_op_toggle):
    """Each state change on the owner's phone needs its own flag, and Play must not carry the app-op flag."""
    if distribution not in ("Play", "Zapstore"):
        raise ValueError("distribution must be Play or Zapstore")
    missing = [flag for flag, given in (("--owner-present-device-idle", owner_present_device_idle),
                                        ("--allow-installer-on-screen", allow_installer_on_screen)) if not given]
    if distribution == "Zapstore" and not allow_install_app_op_toggle:
        missing.append("--allow-install-app-op-toggle")
    if distribution == "Play" and allow_install_app_op_toggle:
        raise ValueError("a Play build has no install app-op to toggle, drop --allow-install-app-op-toggle")
    if missing:
        raise ValueError("refusing without explicit owner confirmation: " + " ".join(missing))


def install_app_op(adb, serial):
    """The isolated package's current install-unknown-apps mode, read-only, so the run can restore it."""
    printed = adb_command(adb, serial, "shell", "appops", "get", APP, INSTALL_OP)
    for line in printed.splitlines():
        if line.strip().startswith(INSTALL_OP + ":"):
            mode = line.split(":", 1)[1].strip().split(";", 1)[0].strip()
            return mode if mode in OP_MODES else "default"
    return "default"


def probe_arguments(cancel_retry=False, large=False, no_installer=False):
    """Closed `-e` selectors for the gaps a run opts into; absent selectors leave the probe unchanged."""
    extra = []
    if cancel_retry:
        extra += ["-e", "fixtureApkCancelRetry", "true"]
    if large:
        extra += ["-e", "fixtureApkLargePayload", LARGE_PAYLOAD_TOKEN]
    if no_installer:
        extra += ["-e", "fixtureApkNoInstaller", "true"]
    return extra


def deferred_for(cancel_retry, large, no_installer):
    """What this run still does not qualify, so a report can never read as issue closure."""
    deferred = list(ALWAYS_DEFERRED)
    if not cancel_retry:
        deferred.append("cancel-retry")
    if not large:
        deferred.append("large-apk-30-50mib")
    if not no_installer:
        deferred.append("no-installer-branch")
    return deferred


def validate_large_apk(path, build_tools=None):
    """Refuse a payload that is not a validly signed APK-shaped archive inside the sender-safe 30 to 31 MiB range.

    The attachment-open path checks the binary manifest but not the signature, so an unsigned or invalidly signed payload
    would otherwise qualify, which is why apksigner must accept it before anything touches the device.
    """
    path = Path(path)
    if path.is_symlink() or not path.is_file() or not MIN_BYTES <= path.stat().st_size <= MAX_BYTES \
            or not has_manifest(path):
        raise ValueError("--large-apk must be a 30 to 31 MiB APK-shaped archive built by apk_payload.py")
    if build_tools is None:
        raise ValueError("--build-tools is required with --large-apk so its signature can be verified")
    try:
        signer_digest(Path(build_tools) / "apksigner", path)
    except (subprocess.SubprocessError, OSError, ValueError) as error:
        raise ValueError("--large-apk failed apksigner verification") from error
    return path


def _capture(root, stage, result):
    """Keep one raw transcript per stage in the private run root only, never in the redacted report."""
    path = Path(root) / f"instrumentation-{stage}.txt"
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as capture:
        capture.write(result)


def _stages(adb, serial, ports, distribution, extra, report, root, private_debug):
    """Toggle the app-op only for a self-update build, run each stage in its own process, stop at the first failure."""
    session = str(uuid.uuid4())
    for stage, permission in stages_for(distribution):
        if distribution == "Zapstore":
            set_install_permission(adb, serial, permission)
        result = instrument(adb, serial, ports, stage, session, extra)
        if private_debug:
            _capture(root, stage, result)
        report["metrics"] += metrics_of(result)
        report["stages"].append({"stage": stage, "permission": permission, "passed": passed(result)})
        if not passed(result):
            break
    report["instrumentation_passed"] = (
        len(report["stages"]) == len(stages_for(distribution)) and all(s["passed"] for s in report["stages"]))


def run(adb, serial, root, output, distribution, physical_fixture_serial=None, budget_profile=None,
        owner_present_device_idle=False, allow_installer_on_screen=False, allow_install_app_op_toggle=False,
        cancel_retry=False, large_apk=None, no_installer_branch=False, private_debug=False, build_tools=None):
    """Drive the probe on the confirmed device, restore the app-op and reverses, and keep every attempt in the report."""
    require_confirmations(distribution, owner_present_device_idle, allow_installer_on_screen,
                          allow_install_app_op_toggle)
    if no_installer_branch and distribution != "Zapstore":
        raise ValueError("the no-installer branch is reachable only on a self-update build")
    payload = validate_large_apk(large_apk, build_tools) if large_apk is not None else None
    environment = verify_physical_device(adb, serial, physical_fixture_serial, budget_profile)
    if installed_identity(adb, serial) != IDENTITY:
        raise ValueError("install the isolated app and test identity in place first, see the install command")
    server, relay = FixtureServer(root), FixtureRelay()
    if payload is not None:
        server.add_payload(LARGE_PAYLOAD_TOKEN, payload)
    report = {"schema": 1, "scope": SCOPE, "device_kind": "physical", "qualified": False,
              "distribution": distribution, "environment": environment, "metrics": [], "stages": [],
              "cancel_retry": cancel_retry, "large_apk": payload is not None,
              "large_apk_sha256": sha256_of(payload) if payload is not None else None,
              "no_installer_simulated": no_installer_branch, "install_app_op_original": None}
    start = len(server.ledger.snapshot())
    forwards, threads, started, failure = [], [], [], None
    try:
        for service in (server, relay):
            thread = threading.Thread(target=service.serve_forever, daemon=True)
            thread.start()
            threads.append(thread)
            started.append(service)
        ports = (server.server_port, relay.port)
        for port in ports:
            # --no-rebind fails instead of overwriting a reverse owned by another task on this phone.
            adb_command(adb, serial, "reverse", "--no-rebind", f"tcp:{port}", f"tcp:{port}")
            forwards.append(port)
        if distribution == "Zapstore":
            report["install_app_op_original"] = install_app_op(adb, serial)
        _stages(adb, serial, ports, distribution, probe_arguments(cancel_retry, payload is not None,
                                                                  no_installer_branch), report, root, private_debug)
    except (subprocess.SubprocessError, OSError, ValueError) as error:
        failure = error
        report["failure_class"] = type(error).__name__
    finally:
        # Failed and partial attempts stay in the ledger and report, nothing is reset.
        cases = cases_for(payload is not None)
        # The deliberately cancelled attempt ends with the client's disconnect, so only that run counts it as terminal.
        events, report["ledger_finalized"] = wait_for_completion(
            server.ledger, start, len(cases), len(cases) + int(cancel_retry),
            terminal_kinds=("complete", "disconnect") if cancel_retry else ("complete",))
        time.sleep(0.5)
        events = server.ledger.snapshot()[start:]
        report["ledger"] = events
        report["http_get_requests"] = sum(e["kind"] == "get" for e in events)
        report["http_upload_requests"] = sum(e["kind"] == "upload" for e in events)
        report["uploaded_ciphertext_bytes"] = sum(e["value"] for e in events if e["kind"] == "upload_bytes")
        report["successful_ciphertext_body_write_bytes"] = sum(e["value"] for e in events if e["kind"] == "body_bytes")
        report["apk_check"] = check_apk(report["metrics"], events, distribution, cancel_retry=cancel_retry,
                                        large=payload is not None, no_installer=no_installer_branch)
        report["budget_check"] = {"applicable": False, "passed": False,
                                  "reason": "representative performance remains unqualified"}
        report["deferred"] = deferred_for(cancel_retry, payload is not None, no_installer_branch)
        report["install_app_op_restored"] = distribution != "Zapstore"
        if distribution == "Zapstore":
            try:
                set_install_permission(adb, serial, report["install_app_op_original"] or "default")
                report["install_app_op_restored"] = True
            except (subprocess.SubprocessError, OSError, ValueError):
                report["install_app_op_restored"] = False
        report["reverse_cleanup_failed"] = False
        for port in forwards:
            try:
                adb_command(adb, serial, "reverse", "--remove", f"tcp:{port}")
            except (subprocess.SubprocessError, OSError):
                report["reverse_cleanup_failed"] = True
        for service in (server, relay):
            if service in started:
                service.shutdown()
            service.server_close()
        for thread in threads:
            thread.join(5)
        report["qualified"] = (
            report.get("instrumentation_passed", False) and report["ledger_finalized"]
            and report["apk_check"]["passed"] and failure is None
            and report["install_app_op_restored"] and not report["reverse_cleanup_failed"]
        )
        output.write_text(json.dumps(report, indent=2) + "\n")
    if failure is not None or not report["qualified"]:
        raise RuntimeError("physical APK probe failed or is incomplete; see the redacted report, not a closure claim")


def package_name(aapt2, apk, run_command=subprocess.run):
    """The manifest package of an APK according to aapt2, so a wrong file can never ride on a confirmation flag."""
    printed = run_command([str(aapt2), "dump", "packagename", str(apk)], check=True, capture_output=True,
                          text=True).stdout
    return printed.strip().splitlines()[0].strip() if printed.strip() else ""


def _private_directory(path):
    """A private, non-symlink backup directory, created on demand like the fixture run root."""
    path = Path(path)
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    if path.is_symlink() or path.stat().st_mode & 0o077:
        raise ValueError("backup directory must be private and not a symlink")
    return path


def _retain_installed(adb, serial, apksigner, package, backup_dir, candidate_digest, run_command):
    """Pull the installed APK as the restore copy and refuse when its signer differs from the candidate's."""
    printed = adb_command(adb, serial, "shell", "pm", "path", package)
    remote = next(line.removeprefix("package:").strip() for line in printed.splitlines() if line.startswith("package:"))
    backup = backup_dir / f"{package}.previous.apk"
    adb_command(adb, serial, "pull", remote, str(backup))
    os.chmod(backup, 0o600)
    if signer_digest(apksigner, backup, run_command) != candidate_digest:
        raise ValueError(f"installed {package} is signed by another key, an in-place update is impossible "
                         "and this tool never uninstalls")
    return {"remote": remote, "sha256": sha256_of(backup), "backup": str(backup)}


def _install_one(adb, serial, package, apk, run_command):
    """Replace or add only this isolated package for user 0 with -r -t, then prove the device holds the same bytes."""
    command = [adb, "-s", serial, "install", "-r", "-t", "--user", "0", str(apk)]
    printed = run_command(command, check=True, capture_output=True, text=True,
                          timeout=INSTALL_TIMEOUT_SECONDS).stdout
    if "Success" not in printed:
        raise RuntimeError(f"install of {package} did not report Success")
    paths = adb_command(adb, serial, "shell", "pm", "path", package)
    remote = next(line.removeprefix("package:").strip() for line in paths.splitlines() if line.startswith("package:"))
    device_digest = adb_command(adb, serial, "shell", "sha256sum", remote).split()[0]
    host_digest = sha256_of(apk)
    if device_digest != host_digest:
        raise RuntimeError(f"installed {package} bytes differ from the candidate")
    return {"remote": remote, "sha256": host_digest}


def install_isolated(adb, serial, physical_fixture_serial, app_apk, test_apk, backup_dir, tools,
                     confirm_in_place_update=False, allow_fresh_install=False, run_command=subprocess.run):
    """Update only the isolated identity in place after name and signer checks; never uninstall, clear or downgrade."""
    if not confirm_in_place_update:
        raise ValueError("refusing to install without --confirm-in-place-update")
    verify_physical_device(adb, serial, physical_fixture_serial, PHYSICAL_PROFILE)
    aapt2, apksigner = Path(tools) / "aapt2", Path(tools) / "apksigner"
    if package_name(aapt2, app_apk, run_command) != APP or package_name(aapt2, test_apk, run_command) != TEST_APP:
        raise ValueError("candidate APKs are not the isolated app and test identity")
    candidate = signer_digest(apksigner, app_apk, run_command)
    if signer_digest(apksigner, test_apk, run_command) != candidate:
        raise ValueError("app and test APKs are signed by different keys")
    installed = installed_identity(adb, serial)
    backup_dir = _private_directory(backup_dir)
    receipt = {"schema": 1, "retained": {}, "installed": {}, "fresh": [p for p in IDENTITY if p not in installed]}
    if receipt["fresh"] and not allow_fresh_install:
        raise ValueError("absent on the device: " + " ".join(receipt["fresh"])
                         + ", pass --allow-fresh-install-of-isolated-identity to add it for the first time")
    for package in installed:
        receipt["retained"][package] = _retain_installed(adb, serial, apksigner, package, backup_dir, candidate,
                                                         run_command)
    for package, apk in zip(IDENTITY, (app_apk, test_apk)):
        receipt["installed"][package] = _install_one(adb, serial, package, apk, run_command)
    path = backup_dir / "install-receipt.json"
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as handle:
        json.dump(receipt, handle, indent=2)
    return receipt


def preflight(adb, serial, physical_fixture_serial, distribution):
    """Read-only readiness facts for the runbook: device profile, isolated identity presence and app-op mode."""
    facts = {"environment": verify_physical_device(adb, serial, physical_fixture_serial, PHYSICAL_PROFILE)}
    installed = installed_identity(adb, serial)
    facts["installed_identity"] = list(installed)
    facts["identity_complete"] = installed == IDENTITY
    facts["existing_reverses"] = [line for line in adb_command(adb, serial, "reverse", "--list").splitlines()
                                  if line.strip()]
    if distribution == "Zapstore" and APP in installed:
        facts["install_app_op"] = install_app_op(adb, serial)
    facts["ready_for_run"] = facts["identity_complete"]
    return facts


def _add_device_arguments(parser):
    """The explicit device selection every command shares."""
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--physical-fixture-serial", required=True,
                        help="Repeat the same serial to opt into a personal physical device")


def main():
    """Separate read-only preflight, explicit in-place install and explicit run; none of them ever uninstalls or clears."""
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    check = commands.add_parser("preflight", help="Read-only device and identity facts")
    _add_device_arguments(check)
    check.add_argument("--distribution", choices=("Play", "Zapstore"), required=True)
    install = commands.add_parser("install", help="Update only the isolated identity in place")
    _add_device_arguments(install)
    install.add_argument("--app-apk", type=Path, required=True)
    install.add_argument("--test-apk", type=Path, required=True)
    install.add_argument("--backup-dir", type=Path, required=True)
    install.add_argument("--build-tools", type=Path, required=True, help="Directory holding aapt2 and apksigner")
    install.add_argument("--confirm-in-place-update", action="store_true")
    install.add_argument("--allow-fresh-install-of-isolated-identity", action="store_true")
    execute = commands.add_parser("run", help="Run the probe stages on the confirmed device")
    _add_device_arguments(execute)
    execute.add_argument("--root", type=Path, required=True)
    execute.add_argument("--output", type=Path, required=True)
    execute.add_argument("--distribution", choices=("Play", "Zapstore"), required=True)
    execute.add_argument("--budget-profile", choices=PROFILES, required=True)
    execute.add_argument("--owner-present-device-idle", action="store_true")
    execute.add_argument("--allow-installer-on-screen", action="store_true")
    execute.add_argument("--allow-install-app-op-toggle", action="store_true")
    execute.add_argument("--cancel-retry", action="store_true")
    execute.add_argument("--large-apk", type=Path)
    execute.add_argument("--build-tools", type=Path, help="Directory holding apksigner, required with --large-apk")
    execute.add_argument("--no-installer-branch", action="store_true")
    execute.add_argument("--private-debug", action="store_true")
    args = parser.parse_args()
    if args.command == "preflight":
        print(json.dumps(preflight(args.adb, args.serial, args.physical_fixture_serial, args.distribution), indent=2))
    elif args.command == "install":
        receipt = install_isolated(args.adb, args.serial, args.physical_fixture_serial, args.app_apk, args.test_apk,
                                   args.backup_dir, args.build_tools, args.confirm_in_place_update,
                                   args.allow_fresh_install_of_isolated_identity)
        print(json.dumps(receipt, indent=2))
    else:
        run(args.adb, args.serial, args.root, args.output, args.distribution, args.physical_fixture_serial,
            args.budget_profile, args.owner_present_device_idle, args.allow_installer_on_screen,
            args.allow_install_app_op_toggle, args.cancel_retry, args.large_apk, args.no_installer_branch,
            args.private_debug, args.build_tools)


if __name__ == "__main__":
    main()
