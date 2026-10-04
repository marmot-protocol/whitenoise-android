"""Run the received-APK fixture on a disposable emulator; failed attempts stay in the report."""

import argparse
import json
from pathlib import Path
import subprocess
import threading
import time
import uuid

from apk_checker import CASES, check_apk
from budget_checker import PROFILES
from device_runner import APP, adb_command
from fixture_relay import FixtureRelay
from fixture_server import FixtureServer
from media_lifecycle_runner import metrics_of, passed, wait_for_completion

PROBE = "dev.ipf.whitenoise.android.media.MediaAttachmentLatencyProbe#measureControlledApkInstaller"
INSTRUMENT_TIMEOUT_SECONDS = 900


def instrument(adb, serial, ports, stage, session, extra=()):
    """Run one probe process for a stage with optional closed `-e` gap selectors; it never confirms an installation."""
    command = [
        adb, "-s", serial, "shell", "am", "instrument", "-w", "-r",
        "-e", "class", PROBE, "-e", "allowControlledAttachmentProbe", "true",
        "-e", "fixtureBlobPort", str(ports[0]), "-e", "fixtureRelayPort", str(ports[1]),
        "-e", "fixtureApkStage", stage, "-e", "fixtureRestartSession", session,
        *extra,
        APP + ".test/androidx.test.runner.AndroidJUnitRunner",
    ]
    return subprocess.run(command, check=True, capture_output=True, text=True,
                          timeout=INSTRUMENT_TIMEOUT_SECONDS).stdout


def set_install_permission(adb, serial, mode):
    """Set the install-unknown-apps app-op for only the isolated fixture package, which restarts its process."""
    if mode not in ("allow", "deny", "default"):
        raise ValueError("unknown install permission mode")
    adb_command(adb, serial, "shell", "appops", "set", APP, "REQUEST_INSTALL_PACKAGES", mode)


def stages_for(distribution):
    """Each stage with the permission the host sets first; a self-update build is exercised denied, then allowed."""
    if distribution == "Zapstore":
        return (("prepare", "default"), ("dispatch-denied", "deny"), ("dispatch-allowed", "allow"))
    return (("prepare", "default"), ("dispatch-na", "default"))


def run(adb, serial, root, output, distribution, budget_profile="reference-api30-arm64"):
    """Send, receive and open each case on one emulator, preserving every request in the report."""
    if not serial.startswith("emulator-") or budget_profile == "pixel-api37-arm64" or budget_profile not in PROFILES:
        raise ValueError("APK fixture requires a disposable emulator and a declared profile")
    if distribution not in ("Play", "Zapstore"):
        raise ValueError("distribution must be Play or Zapstore")
    if adb_command(adb, serial, "shell", "getprop", "ro.kernel.qemu").strip() != "1":
        raise ValueError("device is not an emulator")
    if f"package:{APP}" not in adb_command(adb, serial, "shell", "pm", "list", "packages", APP).splitlines():
        raise ValueError("install the isolated measurement APK in place before running")
    server, relay = FixtureServer(root), FixtureRelay()
    report = {"schema": 1, "scope": "android-received-apk-platform-open", "qualified": False,
              "distribution": distribution, "metrics": []}
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
            adb_command(adb, serial, "reverse", "--no-rebind", f"tcp:{port}", f"tcp:{port}")
            forwards.append(port)
        session = str(uuid.uuid4())
        report["stages"] = []
        for stage, permission in stages_for(distribution):
            set_install_permission(adb, serial, permission)
            result = instrument(adb, serial, ports, stage, session)
            report["metrics"] += metrics_of(result)
            report["stages"].append({"stage": stage, "permission": permission, "passed": passed(result)})
            if not passed(result):
                break
        report["instrumentation_passed"] = (
            len(report["stages"]) == len(stages_for(distribution)) and all(s["passed"] for s in report["stages"]))
        report["environment"] = {
            "api": adb_command(adb, serial, "shell", "getprop", "ro.build.version.sdk").strip(),
            "abi": adb_command(adb, serial, "shell", "getprop", "ro.product.cpu.abi").strip(),
        }
    except (subprocess.SubprocessError, OSError, ValueError) as error:
        failure = error
        report["failure_class"] = type(error).__name__
    finally:
        # Failed and partial attempts stay in the ledger and report; nothing is reset.
        events, report["ledger_finalized"] = wait_for_completion(server.ledger, start, len(CASES), len(CASES))
        time.sleep(0.5)
        events = server.ledger.snapshot()[start:]
        report["ledger"] = events
        report["http_get_requests"] = sum(e["kind"] == "get" for e in events)
        report["http_upload_requests"] = sum(e["kind"] == "upload" for e in events)
        report["uploaded_ciphertext_bytes"] = sum(e["value"] for e in events if e["kind"] == "upload_bytes")
        report["successful_ciphertext_body_write_bytes"] = sum(e["value"] for e in events if e["kind"] == "body_bytes")
        report["apk_check"] = check_apk(report["metrics"], events, distribution)
        report["budget_check"] = {"applicable": False, "passed": False,
                                  "reason": "representative performance remains unqualified"}
        report["qualified"] = (
            report.get("instrumentation_passed", False) and report["ledger_finalized"]
            and report["apk_check"]["passed"] and report.get("environment") == PROFILES[budget_profile]
            and failure is None
        )
        report["deferred"] = ["installation-confirmed", "physical-device", "large-apk-30-50mib",
                              "process-recreation-during-download", "representative-performance"]
        report["permission_reset_failed"] = False
        try:
            set_install_permission(adb, serial, "default")
        except (subprocess.SubprocessError, OSError, ValueError):
            report["permission_reset_failed"] = True
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
        report["qualified"] = (report["qualified"] and not report["reverse_cleanup_failed"]
                               and not report["permission_reset_failed"])
        output.write_text(json.dumps(report, indent=2) + "\n")
    if failure is not None or not report["qualified"]:
        raise RuntimeError("APK probe failed; see the redacted report, not a closure claim")


def main():
    """Require explicit device and report paths; never install, uninstall or clear an app."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--distribution", choices=("Play", "Zapstore"), required=True)
    parser.add_argument("--budget-profile", choices=PROFILES, default="reference-api30-arm64")
    args = parser.parse_args()
    run(args.adb, args.serial, args.root, args.output, args.distribution, args.budget_profile)


if __name__ == "__main__":
    main()
