"""Run process recreation during a received-APK download on a disposable emulator; failed attempts stay in the report."""

import argparse
import json
from pathlib import Path
import subprocess
import threading
import time
import urllib.request
import uuid

from apk_installer_runner import INSTRUMENT_TIMEOUT_SECONDS, PROBE, set_install_permission
from apk_recreation_checker import SCOPE, check_recreation
from budget_checker import PROFILES
from device_runner import APP, adb_command
from fixture_relay import FixtureRelay
from fixture_server import FixtureServer
from media_lifecycle_runner import metrics_of, passed, wait_for_completion

DISCONNECT_WAIT_SECONDS = 15
CRASH_MARKERS = ("Process crashed", "INSTRUMENTATION_RESULT: shortMsg=")


def instrument(adb, serial, ports, stage, session):
    """Run one probe process for a stage and return its output; the first stage ends in a crash by design."""
    command = [
        adb, "-s", serial, "shell", "am", "instrument", "-w", "-r",
        "-e", "class", PROBE, "-e", "allowControlledAttachmentProbe", "true",
        "-e", "fixtureBlobPort", str(ports[0]), "-e", "fixtureRelayPort", str(ports[1]),
        "-e", "fixtureApkStage", stage, "-e", "fixtureRestartSession", session,
        APP + ".test/androidx.test.runner.AndroidJUnitRunner",
    ]
    return subprocess.run(command, check=False, capture_output=True, text=True,
                          timeout=INSTRUMENT_TIMEOUT_SECONDS).stdout


def ended_abruptly(output):
    """The probe process died after holding a real partial body: no pass, a crash marker and the held-prefix row.

    A crash with no held-prefix row is some other failure, such as the process being killed while it started, and
    must never be mistaken for the recreation this run exists to cause.
    """
    held = any(m.get("phase") == "apk-recreate-held" for m in metrics_of(output))
    return not passed(output) and held and any(marker in output for marker in CRASH_MARKERS)


def result_lines(output):
    """The instrumentation's own result lines, bounded, so a failed stage keeps a closed reason in the report."""
    lines = [line for line in output.splitlines() if line.startswith(("INSTRUMENTATION_RESULT:", "INSTRUMENTATION_CODE:"))]
    return [line[:300] for line in lines[:6]]


def release_held_body(server):
    """Release the fixture's hold so the dead client's handler ends and the replacement request is not held."""
    request = urllib.request.Request(f"http://127.0.0.1:{server.server_port}/__release-acquisition", method="POST")
    with urllib.request.urlopen(request, timeout=5) as response:
        response.read()


def await_disconnect(server, start, timeout=DISCONNECT_WAIT_SECONDS):
    """Wait for the interrupted acquisition's own disconnect event, so the two acquisitions can never overlap."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if any(e["kind"] == "disconnect" for e in server.ledger.snapshot()[start:]):
            return True
        time.sleep(0.05)
    return False


def run(adb, serial, root, output, distribution, budget_profile="reference-api30-arm64"):
    """Hold a real download, end the app process mid-body, relaunch it and verify the single completed transfer."""
    if not serial.startswith("emulator-") or budget_profile == "pixel-api37-arm64" or budget_profile not in PROFILES:
        raise ValueError("process recreation fixture requires a disposable emulator and a declared profile")
    if distribution not in ("Play", "Zapstore"):
        raise ValueError("distribution must be Play or Zapstore")
    if adb_command(adb, serial, "shell", "getprop", "ro.kernel.qemu").strip() != "1":
        raise ValueError("device is not an emulator")
    if f"package:{APP}" not in adb_command(adb, serial, "shell", "pm", "list", "packages", APP).splitlines():
        raise ValueError("install the isolated measurement APK in place before running")
    server, relay = FixtureServer(root), FixtureRelay()
    report = {"schema": 1, "scope": SCOPE, "qualified": False, "distribution": distribution, "metrics": [],
              "stages": []}
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
        set_install_permission(adb, serial, "default")
        hold = instrument(adb, serial, ports, "recreate-hold", session)
        report["metrics"] += metrics_of(hold)
        report["process_ended_abruptly"] = ended_abruptly(hold)
        report["stages"].append({"stage": "recreate-hold", "passed": report["process_ended_abruptly"],
                                 "instrumentation": result_lines(hold)})
        if report["process_ended_abruptly"]:
            release_held_body(server)
            report["interrupted_acquisition_ended"] = await_disconnect(server, start)
            if distribution == "Zapstore":
                set_install_permission(adb, serial, "allow")
            resume = instrument(adb, serial, ports, "recreate-resume", session)
            report["metrics"] += metrics_of(resume)
            report["stages"].append({"stage": "recreate-resume", "passed": passed(resume),
                                     "instrumentation": result_lines(resume)})
        report["instrumentation_passed"] = len(report["stages"]) == 2 and all(s["passed"] for s in report["stages"])
        report["environment"] = {
            "api": adb_command(adb, serial, "shell", "getprop", "ro.build.version.sdk").strip(),
            "abi": adb_command(adb, serial, "shell", "getprop", "ro.product.cpu.abi").strip(),
        }
    except (subprocess.SubprocessError, OSError, ValueError) as error:
        failure = error
        report["failure_class"] = type(error).__name__
    finally:
        # Failed and partial attempts stay in the ledger and report; nothing is reset.
        events, report["ledger_finalized"] = wait_for_completion(
            server.ledger, start, 1, 2, terminal_kinds=("complete", "disconnect"))
        time.sleep(0.5)
        events = server.ledger.snapshot()[start:]
        report["ledger"] = events
        report["http_get_requests"] = sum(e["kind"] == "get" for e in events)
        report["http_upload_requests"] = sum(e["kind"] == "upload" for e in events)
        report["uploaded_ciphertext_bytes"] = sum(e["value"] for e in events if e["kind"] == "upload_bytes")
        report["successful_ciphertext_body_write_bytes"] = sum(e["value"] for e in events if e["kind"] == "body_bytes")
        report["recreation_check"] = check_recreation(report["metrics"], events, distribution,
                                                      report.get("process_ended_abruptly"))
        report["qualified"] = (
            report.get("instrumentation_passed", False) and report["ledger_finalized"]
            and report["recreation_check"]["passed"] and report.get("environment") == PROFILES[budget_profile]
            and failure is None
        )
        report["deferred"] = ["installation-confirmed", "physical-device", "representative-performance",
                              "system-initiated-recreation", "durable-worker-resume"]
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
        raise RuntimeError("process recreation probe failed; see the redacted report, not a closure claim")


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
