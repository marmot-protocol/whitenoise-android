"""Run the large file-backed send fixture on a disposable emulator. Failed attempts stay in the report.

The fixture server keeps every completed upload under the run root, so each run leaves about 576 MiB there.
Use a fresh private root per run and remove it afterwards.
"""

import argparse
import json
from pathlib import Path
import subprocess
import threading
import time

from budget_checker import PROFILES
from device_runner import APP, adb_command
from fixture_relay import FixtureRelay
from fixture_server import FixtureServer
from large_send_checker import check_large_send
from media_lifecycle_runner import metrics_of, passed

TEST = ("dev.ipf.whitenoise.android.ui.conversation.media.FileBackedLargeSendDeviceTest"
        "#largeSendStaysBoundedAndRecoversThroughCancelAndRetry")
INSTRUMENT_TIMEOUT_SECONDS = 1500
# The ledger records every 16 KiB of an upload, so the report keeps totals rather than tens of thousands of rows.
REPORTED_KINDS = ("upload", "upload_complete", "upload_disconnect")


def instrument(adb, serial, ports, large_send_bytes=None):
    """Run the one fixture process. Its timeout covers a 512 MiB send, a cancelled send and a retried one."""
    command = [
        adb, "-s", serial, "shell", "am", "instrument", "-w", "-r",
        "-e", "class", TEST, "-e", "allowControlledAttachmentProbe", "true",
        "-e", "fixtureBlobPort", str(ports[0]), "-e", "fixtureRelayPort", str(ports[1]),
        *(["-e", "fixtureLargeSendBytes", str(large_send_bytes)] if large_send_bytes else []),
        APP + ".test/androidx.test.runner.AndroidJUnitRunner",
    ]
    return subprocess.run(command, check=True, capture_output=True, text=True,
                          timeout=INSTRUMENT_TIMEOUT_SECONDS).stdout


def run(adb, serial, root, output, budget_profile="reference-api36-arm64", large_send_bytes=None):
    """Refuse anything but an emulator, run the fixture once and write a redacted, checked report."""
    if not serial.startswith("emulator-") or budget_profile == "pixel-api37-arm64" or budget_profile not in PROFILES:
        raise ValueError("large send fixture requires a disposable emulator and a declared profile")
    if adb_command(adb, serial, "shell", "getprop", "ro.kernel.qemu").strip() != "1":
        raise ValueError("device is not an emulator")
    if f"package:{APP}" not in adb_command(adb, serial, "shell", "pm", "list", "packages", APP).splitlines():
        raise ValueError("install the isolated measurement APK in place before running")
    server, relay = FixtureServer(root), FixtureRelay()
    report = {"schema": 1, "scope": "android-large-file-backed-send", "qualified": False, "metrics": []}
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
        output_text = instrument(adb, serial, ports, large_send_bytes)
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
        # Failed and partial attempts stay in the ledger, and nothing is reset.
        time.sleep(0.5)
        events = server.ledger.snapshot()[start:]
        report["ledger"] = [event for event in events if event["kind"] in REPORTED_KINDS]
        report["uploaded_ciphertext_bytes"] = sum(e["value"] for e in events if e["kind"] == "upload_bytes")
        report["large_send_check"] = check_large_send(report["metrics"], events)
        report["qualified"] = (report.get("instrumentation_passed", False) and report["large_send_check"]["passed"]
                               and report.get("environment") == PROFILES[budget_profile] and failure is None)
        report["deferred"] = ["receive-side-at-ceiling", "physical-device", "representative-performance"]
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
        output.write_text(json.dumps(report, indent=2) + "\n")
    if failure is not None or not report["qualified"]:
        raise RuntimeError("large send fixture failed; see the redacted report, not a closure claim")


def main():
    """Require explicit device and report paths. Never install, uninstall or clear an app."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--budget-profile", choices=PROFILES, default="reference-api36-arm64")
    parser.add_argument("--large-send-bytes", type=int, default=None)
    args = parser.parse_args()
    run(args.adb, args.serial, args.root, args.output, args.budget_profile, args.large_send_bytes)


if __name__ == "__main__":
    main()
