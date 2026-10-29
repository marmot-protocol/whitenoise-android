"""Run the background receipt fixture (#3308) on a disposable emulator in two Android processes; failed attempts
stay in the report."""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import threading
import time
import uuid

from apk_recreation_runner import CRASH_MARKERS, release_held_body, result_lines
from budget_checker import PROFILES
from device_runner import APP, adb_command
from fixture_relay import FixtureRelay
from fixture_server import FixtureServer
from media_lifecycle_runner import failure_kind, metrics_of, passed
from background_receipt_checker import check_background_receipt

TEST = ("dev.ipf.whitenoise.android.media.IncomingAttachmentBackgroundDownloadAndroidTest"
        "#receivedMediaDownloadsInTheBackgroundUnderItsOwnPolicy")
INSTRUMENT_TIMEOUT_SECONDS = 900
DISCONNECT_WAIT_SECONDS = 15
STAGES = ("main", "resume")


def prepare_ci_wifi(adb, serial, report):
    """Associate once with AndroidWifi on the hosted API 34 emulator before reverse ports exist.

    Android 14 separates radio enable from root-only association. Verify privilege transitions, restore
    shell in finally before any reverse port or instrumentation. `connect-network` waits only 500 ms
    for its callback and exits zero either way, so a silent reply is recorded as "none" rather than
    failing the campaign; only an explicit "Connection failed" stops here. No reply is network proof:
    the unchanged Android scenario must still observe actual validated default Wi-Fi within its own
    30-second waits, with cellular fallback and every policy assertion preserved.
    """
    setup = {"phase": "guard", "association": "none", "shell_restored": False}
    report["wifi_setup"] = setup
    if os.environ.get("GITHUB_ACTIONS") != "true" or re.fullmatch(r'emulator-[0-9]+', serial) is None:
        raise ValueError("Wi-Fi setup requires the hosted disposable emulator")
    expected = {"ro.kernel.qemu": "1", "ro.build.version.sdk": "34", "ro.product.cpu.abi": "x86_64",
                "ro.debuggable": "1"}
    for prop, value in expected.items():
        if adb_command(adb, serial, "shell", "getprop", prop, timeout=5).strip() != value:
            raise ValueError("unsupported hosted Wi-Fi setup image")
    setup["phase"] = "associate"
    try:
        adb_command(adb, serial, "root", timeout=10)
        adb_command(adb, serial, "wait-for-device", timeout=10)
        if adb_command(adb, serial, "shell", "id", "-u", timeout=5).strip() != "0":
            raise ValueError("hosted image did not admit root for Wi-Fi association")
        adb_command(adb, serial, "shell", "cmd", "wifi", "set-wifi-enabled", "enabled", timeout=10)
        result = adb_command(adb, serial, "shell", "cmd", "wifi", "connect-network", "AndroidWifi", "open", timeout=10)
        lines = {line.strip() for line in result.splitlines()}
        if "Connection failed" in lines:
            setup["association"] = "failed"
            raise ValueError("fixed virtual AP association was refused")
        if "Connection initiated" in lines:
            setup["association"] = "initiated"
    finally:
        adb_command(adb, serial, "unroot", timeout=10)
        adb_command(adb, serial, "wait-for-device", timeout=10)
        setup["shell_restored"] = adb_command(adb, serial, "shell", "id", "-u", timeout=5).strip() == "2000"
        if not setup["shell_restored"]:
            raise ValueError("hosted Wi-Fi setup did not restore shell privileges")
    setup["phase"] = "association-requested"


def instrument(adb, serial, ports, stage, session):
    """Run one fixture process for [stage] on the preserved [session]; the main stage ends in a crash by design."""
    if stage not in STAGES:
        raise ValueError("unknown fixture stage")
    command = [
        adb, "-s", serial, "shell", "am", "instrument", "-w", "-r",
        "-e", "class", TEST, "-e", "allowControlledAttachmentProbe", "true",
        "-e", "fixtureBlobPort", str(ports[0]), "-e", "fixtureRelayPort", str(ports[1]),
        "-e", "fixtureBackgroundStage", stage, "-e", "fixtureRestartSession", session,
        APP + ".test/androidx.test.runner.AndroidJUnitRunner",
    ]
    return subprocess.run(command, check=False, capture_output=True, text=True,
                          timeout=INSTRUMENT_TIMEOUT_SECONDS).stdout


def failure_diagnostics(output):
    """Keep a closed failure category and allowlisted fixture line numbers, never stack messages or identities."""
    frames = re.findall(
        r"^\s*at dev\.ipf\.whitenoise\.android\.media\."
        r"IncomingAttachmentBackgroundDownloadAndroidTest(?:\$[\w$]+)?\.[\w$]+"
        r"\(IncomingAttachmentBackgroundDownloadAndroidTest\.kt:(\d{1,5})\)$",
        output, re.MULTILINE,
    )
    result = {"kind": failure_kind(output), "fixture_lines": sorted({int(line) for line in frames})[:6]}
    # Suspend helper frames may omit the owning test frame; keep only these closed fixture boundaries.
    allowed = {"AndroidAttachmentFixtureSender": "sender-publication",
               "MediaLifecycleAttachmentProbe": "receiver-projection",
               "BackgroundReceiptFixtureSupport": "network-policy"}
    boundaries = []
    for source, label in allowed.items():
        lines = re.findall(
            rf"^\s*at dev\.ipf\.whitenoise\.android\.media\.{source}(?:Kt)?(?:\$[\w$]+)?"
            rf"\.[\w$]+\({source}\.kt:(\d{{1,5}})\)$", output, re.MULTILINE,
        )
        boundaries.extend({"boundary": label, "line": int(line)} for line in set(lines))
    if boundaries:
        result["boundary_lines"] = sorted(boundaries, key=lambda row: (row["boundary"], row["line"]))[:6]
    return result


def ended_abruptly(output):
    """The main stage handed off a stopped download: no pass, a crash marker and the held row.

    A crash with no held row is some other failure and must never be mistaken for the hand-off.
    """
    held = any(m.get("phase") == "background-receipt-held" for m in metrics_of(output))
    return not passed(output) and held and any(marker in output for marker in CRASH_MARKERS)


def held_request_ended(events):
    """Whether the held acquisition itself, matched by its request id, ended after the last platform stop marker."""
    stops = [e["seq"] for e in events if e["kind"] == "platform_stop_marker"]
    held = {e["request"] for e in events if e["kind"] == "held"}
    ended = ("disconnect", "hold_timeout")
    return bool(stops) and any(
        e["kind"] in ended and e["seq"] > stops[-1] and e["request"] in held for e in events
    )


def await_held_disconnect(server, start, timeout=DISCONNECT_WAIT_SECONDS):
    """Wait for the ended process's held request to end after the platform stop, so no two acquisitions overlap."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        events = server.ledger.snapshot()[start:]
        if held_request_ended(events):
            return True
        time.sleep(0.05)
    return False


def run(adb, serial, root, output, budget_profile="reference-api30-arm64"):
    """Refuse anything but an emulator, run the fixture once and write a redacted, checked report."""
    if not serial.startswith("emulator-") or budget_profile == "pixel-api37-arm64" or budget_profile not in PROFILES:
        raise ValueError("background receipt fixture requires a disposable emulator and a declared profile")
    if adb_command(adb, serial, "shell", "getprop", "ro.kernel.qemu").strip() != "1":
        raise ValueError("device is not an emulator")
    if f"package:{APP}" not in adb_command(adb, serial, "shell", "pm", "list", "packages", APP).splitlines():
        raise ValueError("install the isolated measurement APK in place before running")
    server, relay = FixtureServer(root), FixtureRelay()
    report = {"schema": 1, "scope": "android-background-receipt-scheduling", "qualified": False, "metrics": [],
              "stages": []}
    start = len(server.ledger.snapshot())
    forwards, threads, started, failure = [], [], [], None
    try:
        if budget_profile == "ci-api34-x86_64":
            prepare_ci_wifi(adb, serial, report)
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
        main_output = instrument(adb, serial, ports, "main", session)
        report["metrics"] += metrics_of(main_output)
        report["process_ended_abruptly"] = ended_abruptly(main_output)
        report["stages"].append({"stage": "main", "passed": report["process_ended_abruptly"],
                                 "instrumentation": result_lines(main_output),
                                 "failure": (None if report["process_ended_abruptly"]
                                             else failure_diagnostics(main_output))})
        if report["process_ended_abruptly"]:
            release_held_body(server)
            report["interrupted_acquisition_ended"] = await_held_disconnect(server, start)
            if report["interrupted_acquisition_ended"]:
                resume_output = instrument(adb, serial, ports, "resume", session)
                report["metrics"] += metrics_of(resume_output)
                report["stages"].append({"stage": "resume", "passed": passed(resume_output),
                                         "instrumentation": result_lines(resume_output),
                                         "failure": None if passed(resume_output) else failure_diagnostics(resume_output)})
            else:
                # A resume that overlapped the still-open interrupted request would contaminate the restart
                # experiment, so the attempt stops here and keeps its failed report.
                report["stages"].append({"stage": "resume", "passed": False, "skipped": "held_request_still_open"})
        report["instrumentation_passed"] = (len(report["stages"]) == 2 and all(s["passed"] for s in report["stages"])
                                            and report.get("interrupted_acquisition_ended", False))
        report["environment"] = {
            "api": adb_command(adb, serial, "shell", "getprop", "ro.build.version.sdk").strip(),
            "abi": adb_command(adb, serial, "shell", "getprop", "ro.product.cpu.abi").strip(),
        }
    except (subprocess.SubprocessError, OSError, ValueError) as error:
        failure = error
        report["failure_class"] = type(error).__name__
    finally:
        # Failed and partial attempts stay in the ledger and report; nothing is reset.
        time.sleep(0.5)
        events = server.ledger.snapshot()[start:]
        report["ledger"] = events
        report["http_get_requests"] = sum(e["kind"] == "get" for e in events)
        report["http_upload_requests"] = sum(e["kind"] == "upload" for e in events)
        report["receipt_check"] = check_background_receipt(report["metrics"], events)
        report["qualified"] = (report.get("instrumentation_passed", False) and report["receipt_check"]["passed"]
                               and report.get("environment") == PROFILES[budget_profile] and failure is None)
        report["deferred"] = ["system-initiated-recreation", "physical-device", "doze", "representative-performance"]
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
        raise RuntimeError("background receipt fixture failed; see the redacted report, not a closure claim")


def main():
    """Require explicit device and report paths; never install, uninstall or clear an app."""
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
