"""Run the small text attachment forward fixture on a disposable emulator, or on one explicitly authorized physical
device. Failed attempts stay in the report.

A physical run needs the device's serial twice, as --serial and --physical-fixture-serial, and the Pixel profile.
The runner never installs, uninstalls or clears anything: update the isolated measurement identity in place first
with `apk_physical_runner.py install`, which checks names and signers and keeps a backup. The device reaches the
loopback fixture servers over `adb reverse`, so the run measures the phone's own work against a responsive endpoint.
"""

import argparse
import json
from pathlib import Path
import subprocess
import threading
import time

from apk_physical_runner import IDENTITY, PHYSICAL_PROFILE, installed_identity, verify_physical_device
from budget_checker import PROFILES
from device_runner import APP, adb_command
from fixture_relay import FixtureRelay
from fixture_server import FixtureServer
from forward_checker import check_forward, expected_gets, expected_uploads
from media_lifecycle_runner import metrics_of, passed, wait_for_completion

PROBE = "dev.ipf.whitenoise.android.media.MediaAttachmentLatencyProbe#measureControlledForward"
INSTRUMENT_TIMEOUT_SECONDS = 1200


def instrument(adb, serial, ports):
    """Run the one probe process; the timeout covers five direct sends, fifteen forwards and every receipt."""
    command = [
        adb, "-s", serial, "shell", "am", "instrument", "-w", "-r",
        "-e", "class", PROBE, "-e", "allowControlledAttachmentProbe", "true",
        "-e", "fixtureBlobPort", str(ports[0]), "-e", "fixtureRelayPort", str(ports[1]),
        "-e", "fixtureForward", "true",
        APP + ".test/androidx.test.runner.AndroidJUnitRunner",
    ]
    return subprocess.run(command, check=True, capture_output=True, text=True,
                          timeout=INSTRUMENT_TIMEOUT_SECONDS).stdout


def verify_device(adb, serial, budget_profile, physical_fixture_serial):
    """Confirm the device is the one this run may use and return its kind. Refusals precede any adb call."""
    if physical_fixture_serial is not None:
        verify_physical_device(adb, serial, physical_fixture_serial, budget_profile)
        return "physical"
    if not serial.startswith("emulator-") or budget_profile == PHYSICAL_PROFILE or budget_profile not in PROFILES:
        raise ValueError("forward fixture requires a disposable emulator and a declared profile")
    if adb_command(adb, serial, "shell", "getprop", "ro.kernel.qemu").strip() != "1":
        raise ValueError("device is not an emulator")
    return "emulator"


def run(adb, serial, root, output, budget_profile="reference-api30-arm64", physical_fixture_serial=None):
    """Refuse any device this run may not use, run the fixture once and write a redacted, checked report."""
    device_kind = verify_device(adb, serial, budget_profile, physical_fixture_serial)
    if installed_identity(adb, serial) != IDENTITY:
        raise ValueError("install the isolated measurement app and test APKs in place before running")
    server, relay = FixtureServer(root), FixtureRelay()
    report = {"schema": 1, "scope": "android-forward-small-text-attachment-phases", "device_kind": device_kind,
              "qualified": False, "metrics": []}
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
        output_text = instrument(adb, serial, ports)
        report["metrics"] = metrics_of(output_text)
        report["instrumentation_passed"] = passed(output_text)
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
            server.ledger, start, uploads=expected_uploads(), gets=expected_gets())
        report["ledger"] = events
        report["http_get_requests"] = sum(e["kind"] == "get" for e in events)
        report["http_upload_requests"] = sum(e["kind"] == "upload" for e in events)
        report["uploaded_ciphertext_bytes"] = sum(e["value"] for e in events if e["kind"] == "upload_bytes")
        report["successful_ciphertext_body_write_bytes"] = sum(e["value"] for e in events if e["kind"] == "body_bytes")
        report["forward_check"] = check_forward(report["metrics"], events, physical=device_kind == "physical")
        # The unchanged 1 KiB received-read ceilings describe a download, not a forward; the forward ceilings live in
        # the forward check above.
        report["budget_check"] = {"applicable": False, "passed": False,
                                  "reason": "received-read budgets do not describe a forward"}
        report["qualified"] = (report.get("instrumentation_passed", False) and report["ledger_finalized"]
                               and report["forward_check"]["passed"]
                               and report.get("environment") == PROFILES[budget_profile] and failure is None)
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
        report["qualified"] = report["qualified"] and not report["reverse_cleanup_failed"]
        # The checker's physical verdict stands only if the whole run, not just the samples, was qualified.
        report["forward_check"]["physical_device_qualified"] &= report["qualified"]
        # Only a qualified run on the authorized phone stops deferring the physical device.
        report["deferred"] = ["known-responsive-public-infrastructure", "representative-performance",
                              "stalled-phase-bound"]
        if not (device_kind == "physical" and report["qualified"]):
            report["deferred"].insert(0, "physical-device")
        output.write_text(json.dumps(report, indent=2) + "\n")
    if failure is not None or not report["qualified"]:
        raise RuntimeError("forward fixture failed; see the redacted report, not a closure claim")


def main():
    """Require explicit device and report paths; never install, uninstall or clear an app."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--budget-profile", choices=PROFILES, default="reference-api30-arm64")
    parser.add_argument("--physical-fixture-serial",
                        help="Repeat the same serial, with the Pixel profile, to opt into one physical device")
    args = parser.parse_args()
    run(args.adb, args.serial, args.root, args.output, args.budget_profile, args.physical_fixture_serial)


if __name__ == "__main__":
    main()
