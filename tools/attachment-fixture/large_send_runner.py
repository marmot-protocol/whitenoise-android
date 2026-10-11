"""Run the large file-backed send fixture on a disposable emulator, or on one explicitly authorized physical
device. Failed attempts stay in the report.

The fixture server keeps every completed upload under the run root, so each run leaves about 576 MiB there.
Use a fresh private root per run and remove it afterwards. The report records the device's free storage before
and after the run, so a run that leaves files behind on the device shows it.

A physical run needs the device's serial twice, as --serial and --physical-fixture-serial, and the Pixel
profile. The runner never installs, uninstalls or clears anything: update the isolated measurement identity
in place first with `apk_physical_runner.py install`, which checks names and signers and keeps a backup.
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
from large_send_checker import CEILING_BYTES, check_large_send
from media_lifecycle_runner import metrics_of, passed

TEST = ("dev.ipf.whitenoise.android.ui.conversation.media.FileBackedLargeSendDeviceTest"
        "#largeSendStaysBoundedAndRecoversThroughCancelAndRetry")
INSTRUMENT_TIMEOUT_SECONDS = 1500
# The ledger records every 16 KiB of an upload, so the report keeps totals rather than tens of thousands of rows.
REPORTED_KINDS = ("upload", "upload_complete", "upload_disconnect")
MIB = 1024 ** 2
# At its peak a send holds the staged copy, MDK's private copy and the ciphertext, so about three times the
# file. MDK keeps a fourth, retained copy only when it finds room for one. The margin covers that and keeps a
# phone that is nearly full above Android's own low-storage threshold.
STORAGE_MARGIN_BYTES = 2048 * MIB


def required_free_bytes(large_send_bytes=None):
    """Free space the run needs on the device's data partition before it starts."""
    return 3 * (large_send_bytes or CEILING_BYTES) + STORAGE_MARGIN_BYTES


def parse_free_kib(df_output):
    """The available KiB from `df -k` output: the column just before the use percentage, even when a long
    filesystem name wraps the row onto two lines."""
    tokens = " ".join(df_output.strip().splitlines()[1:]).split()
    for index, token in enumerate(tokens):
        if token.endswith("%") and token[:-1].isdigit() and index > 0 and tokens[index - 1].isdigit():
            return int(tokens[index - 1])
    raise ValueError("could not read free storage on the device")


def free_data_bytes(adb, serial):
    """Available bytes on the device's data partition, from `df`, read-only."""
    return parse_free_kib(adb_command(adb, serial, "shell", "df", "-k", "/data")) * 1024


def verify_device(adb, serial, budget_profile, physical_fixture_serial):
    """Confirm the device is the one this run may use and return its environment. Refusals precede any adb call."""
    if physical_fixture_serial is not None:
        return "physical", verify_physical_device(adb, serial, physical_fixture_serial, budget_profile)
    if not serial.startswith("emulator-") or budget_profile == PHYSICAL_PROFILE or budget_profile not in PROFILES:
        raise ValueError("large send fixture requires a disposable emulator and a declared profile")
    if adb_command(adb, serial, "shell", "getprop", "ro.kernel.qemu").strip() != "1":
        raise ValueError("device is not an emulator")
    return "emulator", None


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


def run(adb, serial, root, output, budget_profile="reference-api36-arm64", large_send_bytes=None,
        physical_fixture_serial=None):
    """Refuse any device this run may not use, run the fixture once and write a redacted, checked report."""
    device_kind, _ = verify_device(adb, serial, budget_profile, physical_fixture_serial)
    if installed_identity(adb, serial) != IDENTITY:
        raise ValueError("install the isolated measurement app and test APKs in place before running")
    free_before = free_data_bytes(adb, serial)
    if free_before < required_free_bytes(large_send_bytes):
        raise ValueError("not enough free storage on the device for the large send")
    server, relay = FixtureServer(root), FixtureRelay()
    report = {"schema": 1, "scope": "android-large-file-backed-send", "device_kind": device_kind,
              "qualified": False, "metrics": [], "free_data_bytes_before": free_before}
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
        # Only a qualified run on the authorized phone stops deferring the physical device.
        report["deferred"] = ["receive-side-at-ceiling", "representative-performance"]
        if not (device_kind == "physical" and report["qualified"]):
            report["deferred"].append("physical-device")
        try:
            report["free_data_bytes_after"] = free_data_bytes(adb, serial)
        except (subprocess.SubprocessError, OSError, ValueError):
            report["free_data_bytes_after"] = None
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
    parser.add_argument("--physical-fixture-serial",
                        help="Repeat the same serial, with the Pixel profile, to opt into one physical device")
    args = parser.parse_args()
    run(args.adb, args.serial, args.root, args.output, args.budget_profile, args.large_send_bytes,
        args.physical_fixture_serial)


if __name__ == "__main__":
    main()
