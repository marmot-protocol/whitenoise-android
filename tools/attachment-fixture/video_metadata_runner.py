"""Run the #3376 video-metadata send-and-receive fixture on a disposable emulator. Failed attempts stay in the report.

`VideoMetadataFixtureDeviceTest` hands tagged videos to the shipping sender through its content-URI preparation entry
points, a share import and prepared draft injection, sends them through MDK to the loopback fixture, downloads each as
the generated receiver and reports closed facts. `video_metadata_checker.py` judges those facts and the fixture's own
upload ledger. The system Photo Picker, Files and share sheet UI, a force-stop and reopen restored draft, a physical
device and a camera recording are not driven, so the report always defers them.

The runner never installs, uninstalls or clears anything: `run-controlled-attachment-fixture.sh` installs the
isolated measurement identity in place first.
"""

import argparse
import json
from pathlib import Path
import subprocess
import threading
import time

from apk_physical_runner import IDENTITY, PHYSICAL_PROFILE, installed_identity
from budget_checker import PROFILES
from device_runner import APP, adb_command
from fixture_relay import FixtureRelay
from fixture_server import FixtureServer
from media_lifecycle_runner import metrics_of, passed
from video_metadata_checker import SCOPE, check_video_metadata

TEST = ("dev.ipf.whitenoise.android.ui.conversation.media.VideoMetadataFixtureDeviceTest"
        "#contentUriPreparationEntryPointsSendOnlyScrubbedVideosToTheReceiver")
INSTRUMENT_TIMEOUT_SECONDS = 1200
# The ledger records every 16 KiB of an upload, so the report keeps the upload and download outcomes, not the chunks.
REPORTED_KINDS = ("upload", "upload_complete", "upload_disconnect", "get", "status", "complete")
DEFERRED = ("system-photo-picker-ui", "system-files-ui", "system-share-sheet-ui", "force-stop-restored-draft",
            "physical-device", "camera-recording")


def verify_device(adb, serial, budget_profile):
    """Refuse anything but a disposable emulator with a declared emulator profile. Refusals precede any adb call."""
    if not serial.startswith("emulator-") or budget_profile == PHYSICAL_PROFILE or budget_profile not in PROFILES:
        raise ValueError("video metadata fixture requires a disposable emulator and a declared profile")
    if adb_command(adb, serial, "shell", "getprop", "ro.kernel.qemu").strip() != "1":
        raise ValueError("device is not an emulator")


def instrument(adb, serial, ports):
    """Run the one fixture process. Its timeout covers two sends above the in-memory limit and their downloads."""
    command = [
        adb, "-s", serial, "shell", "am", "instrument", "-w", "-r",
        "-e", "class", TEST, "-e", "allowControlledAttachmentProbe", "true",
        "-e", "fixtureBlobPort", str(ports[0]), "-e", "fixtureRelayPort", str(ports[1]),
        APP + ".test/androidx.test.runner.AndroidJUnitRunner",
    ]
    return subprocess.run(command, check=True, capture_output=True, text=True,
                          timeout=INSTRUMENT_TIMEOUT_SECONDS).stdout


def run(adb, serial, root, output, budget_profile="reference-api30-arm64"):
    """Refuse any device this run may not use, run the fixture once and write a redacted, checked report."""
    verify_device(adb, serial, budget_profile)
    if installed_identity(adb, serial) != IDENTITY:
        raise ValueError("install the isolated measurement app and test APKs in place before running")
    server, relay = FixtureServer(root), FixtureRelay()
    report = {"schema": 1, "scope": SCOPE, "device_kind": "emulator", "qualified": False, "metrics": [],
              "deferred": list(DEFERRED)}
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
        # Failed and partial attempts stay in the ledger, and nothing is reset.
        time.sleep(0.5)
        events = server.ledger.snapshot()[start:]
        report["ledger"] = [event for event in events if event["kind"] in REPORTED_KINDS]
        report["video_metadata_check"] = check_video_metadata(report["metrics"], events)
        report["qualified"] = (report.get("instrumentation_passed", False)
                               and report["video_metadata_check"]["passed"]
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
        output.write_text(json.dumps(report, indent=2) + "\n")
    if failure is not None or not report["qualified"]:
        raise RuntimeError("video metadata fixture failed; see the redacted report, not a closure claim")


def main():
    """Require explicit device and report paths. Never install, uninstall or clear an app."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--budget-profile", choices=PROFILES, default="reference-api30-arm64")
    args = parser.parse_args()
    run(args.adb, args.serial, args.root, args.output, args.budget_profile)


if __name__ == "__main__":
    main()
