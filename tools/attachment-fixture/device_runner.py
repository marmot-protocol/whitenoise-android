"""Run an isolated opt-in fixture, preserving host counters across native clients."""

import argparse
import json
import os
from pathlib import Path
import subprocess
import threading
import time
import uuid

from budget_checker import PROFILES, check_budget
from cancellation_checker import check_cancellation
from fixture_relay import FixtureRelay
from fixture_server import FixtureServer

APP = "dev.ipf.whitenoise.android.medialatency"
PROBE = "dev.ipf.whitenoise.android.media.MediaAttachmentLatencyProbe#measureControlledReceivedAttachment"
LEDGER_COMPLETION_TIMEOUT_SECONDS = 5


def adb_command(adb, serial, *args):
    """Use only the explicit verified device; never fall back to the default device."""
    return subprocess.run([adb, "-s", serial, *args], check=True, capture_output=True, text=True, timeout=180).stdout


def wait_for_ledger_completion(ledger, start, timeout=LEDGER_COMPLETION_TIMEOUT_SECONDS):
    """Return durable events once the upload and acquisition finalize, or retain timeout evidence."""
    deadline = time.monotonic() + timeout
    while True:
        events = ledger.snapshot()[start:]
        requests = {e["seq"] for e in events if e["kind"] in ("get", "head")}
        completed = {e["request"] for e in events if e["kind"] == "complete"}
        uploads = {e["seq"] for e in events if e["kind"] == "upload"}
        uploaded = {e["request"] for e in events if e["kind"] == "upload_complete"}
        if len(requests) == 1 and len(uploads) == 1 and requests <= completed and uploads <= uploaded:
            return events, True
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return events, False
        time.sleep(min(0.01, remaining))


def wait_for_cancellation_completion(ledger, start):
    """Wait for both distinct terminal events while retaining failed or incomplete requests."""
    deadline = time.monotonic() + LEDGER_COMPLETION_TIMEOUT_SECONDS
    while True:
        events = ledger.snapshot()[start:]
        completed = any(e["kind"] == "complete" for e in events)
        disconnected = any(e["kind"] == "disconnect" for e in events)
        if completed and disconnected:
            return events, True
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return events, False
        time.sleep(min(0.01, remaining))


def run(adb, serial, root, output, private_debug=False, budget_profile="reference-api30-arm64", android_send_controller=False, held_cancellation=False, process_restart=False, physical_fixture_serial=None):
    """Count genuine uploaded ciphertext and received bodies without resetting failed attempts."""
    physical = physical_fixture_serial is not None
    if physical:
        if serial != physical_fixture_serial or serial.startswith("emulator-") or budget_profile != "pixel-api37-arm64":
            raise ValueError("physical fixture requires an explicit matching serial and Pixel profile")
    elif not serial.startswith("emulator-") or budget_profile == "pixel-api37-arm64":
        raise ValueError("fixture runner requires a disposable emulator unless physical testing is explicitly selected")
    emulated = adb_command(adb, serial, "shell", "getprop", "ro.kernel.qemu").strip() == "1"
    if emulated == physical:
        raise ValueError("device type does not match the explicit fixture selection")
    if f"package:{APP}" not in adb_command(adb, serial, "shell", "pm", "list", "packages", APP).splitlines():
        raise ValueError("install the isolated measurement APK in place before running")
    if budget_profile not in PROFILES:
        raise ValueError("unknown attachment budget profile")
    if held_cancellation and not android_send_controller:
        raise ValueError("held cancellation requires the Android send controller")
    if held_cancellation and process_restart:
        raise ValueError("cancellation and process-restart scenarios require separate ledgers")
    session = str(uuid.uuid4()) if process_restart else None
    server = FixtureServer(root)
    relay = FixtureRelay()
    forwards = []
    threads = []
    report = {"schema": 1, "scope": "android-host-resolver-packaged-native", "metrics": [], "qualified": False,
              "android_send_controller_requested": android_send_controller,
              "device_kind": "physical" if physical else "emulator"}
    before = server.ledger.snapshot()
    failure = None
    started_services = []
    try:
        for service in (server, relay):
            thread = threading.Thread(target=service.serve_forever, daemon=True)
            thread.start()
            threads.append(thread)
            started_services.append(service)
        for port in (server.server_port, relay.port):
            # --no-rebind fails instead of overwriting an existing reverse owned by another task.
            adb_command(adb, serial, "reverse", "--no-rebind", f"tcp:{port}", f"tcp:{port}")
            forwards.append(port)
        result = adb_command(
            adb, serial, "shell", "am", "instrument", "-w", "-r",
            "-e", "class", PROBE, "-e", "allowControlledAttachmentProbe", "true",
            "-e", "fixtureBlobPort", str(server.server_port),
            "-e", "fixtureRelayPort", str(relay.port),
            *(["-e", "fixtureUseAndroidSendController", "true"] if android_send_controller else []),
            *(["-e", "fixtureCancellation", "true"] if held_cancellation else []),
            *(["-e", "fixtureRestartRole", "prepare", "-e", "fixtureRestartSession", session] if process_restart else []),
            APP + ".test/androidx.test.runner.AndroidJUnitRunner",
        )
        results = [result]
        if process_restart and "OK (1 test)" in result and "FAILURES!!!" not in result:
            adb_command(adb, serial, "shell", "am", "force-stop", APP)
            results.append(adb_command(
                adb, serial, "shell", "am", "instrument", "-w", "-r",
                "-e", "class", PROBE, "-e", "allowControlledAttachmentProbe", "true",
                "-e", "fixtureBlobPort", str(server.server_port),
                "-e", "fixtureRelayPort", str(relay.port),
                "-e", "fixtureRestartRole", "read", "-e", "fixtureRestartSession", session,
                APP + ".test/androidx.test.runner.AndroidJUnitRunner",
            ))
            result = "\n".join(results)
        if private_debug:
            # Explicit local debugging only; never archived by CI or copied into redacted reports.
            with os.fdopen(os.open(root / "instrumentation.txt", os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as capture:
                capture.write(result)
        for line in result.splitlines():
            prefix = "INSTRUMENTATION_STATUS: controlled_attachment_json="
            if line.startswith(prefix):
                report["metrics"].append(json.loads(line[len(prefix):]))
        report["instrumentation_passed"] = all("OK (1 test)" in run and "FAILURES!!!" not in run for run in results)
        if process_restart:
            report["instrumentation_passed"] = report["instrumentation_passed"] and len(results) == 2
        report["environment"] = {
            "api": adb_command(adb, serial, "shell", "getprop", "ro.build.version.sdk").strip(),
            "abi": adb_command(adb, serial, "shell", "getprop", "ro.product.cpu.abi").strip(),
        }
    except (subprocess.SubprocessError, OSError, ValueError) as error:
        failure = error
        report["failure_class"] = type(error).__name__
    finally:
        # Retain failure/timeout evidence before cleanup; never discard failed attempts.
        if held_cancellation:
            events, report["ledger_finalized"] = wait_for_cancellation_completion(server.ledger, len(before))
        else:
            events, report["ledger_finalized"] = wait_for_ledger_completion(server.ledger, len(before))
        report["ledger"] = events
        report["http_acquisition_requests"] = sum(e["kind"] in ("get", "head") for e in events)
        report["http_get_requests"] = sum(e["kind"] == "get" for e in events)
        report["http_head_requests"] = sum(e["kind"] == "head" for e in events)
        report["http_upload_requests"] = sum(e["kind"] == "upload" for e in events)
        report["uploaded_ciphertext_bytes"] = sum(e["value"] for e in events if e["kind"] == "upload_bytes")
        report["successful_ciphertext_body_write_bytes"] = sum(e["value"] for e in events if e["kind"] == "body_bytes")
        report["budget_check"] = check_budget(report["metrics"], budget_profile)
        if report.get("environment") != PROFILES[budget_profile]:
            report["budget_check"]["passed"] = False
            report["budget_check"]["violations"].append("device API/ABI does not match budget profile")
        received = [m for m in report["metrics"] if isinstance(m, dict) and str(m.get("phase", "")).startswith("received-")]
        requests = {e["seq"] for e in events if e["kind"] in ("get", "head")}
        completed = {e["request"] for e in events if e["kind"] == "complete"}
        report["qualified"] = (
            report["ledger_finalized"] and report["budget_check"]["passed"] and report.get("instrumentation_passed", False) and len(received) == 12 and all(m.get("success") is True for m in received)
            and sum(e["kind"] == "acquisition_unavailable" for e in events) == 1
            and report["http_acquisition_requests"] == 1
            and report["http_upload_requests"] == 1 and requests <= completed
            and report["successful_ciphertext_body_write_bytes"] == report["uploaded_ciphertext_bytes"] > 0
        )
        outgoing = [m for m in report["metrics"] if isinstance(m, dict) and m.get("phase") == "genuine-native-send-retention"]
        report["android_send_controller_qualified"] = len(outgoing) == 1 and all(
            outgoing[0].get(field) is True for field in ("available", "exact_native_lease_bytes",
                "native_runtime_reopen_exact_bytes", "android_send_controller_qualified"))
        report["process_restart_qualified"] = len(outgoing) == 1 and (
            all(outgoing[0].get(field) is True for field in ("available", "exact_native_lease_bytes",
                "native_runtime_reopen_exact_bytes", "process_restart_offline_qualified"))
            and outgoing[0].get("retained_reads_per_direction_after_process_restart") == 10)
        if process_restart and not report["process_restart_qualified"]:
            report["qualified"] = False
        if android_send_controller and not report["android_send_controller_qualified"]:
            report["qualified"] = False
        report["deferred"] = ["platform-worker-lifetime", "external-handoff", "process-restart-offline",
                              "android-controller-genuine-send", "mdk-large-received-sender", "physical-device"]
        if report["android_send_controller_qualified"]:
            report["deferred"].remove("android-controller-genuine-send")
        if process_restart and report["process_restart_qualified"]:
            report["deferred"].remove("process-restart-offline")
        if held_cancellation:
            report["scope"] = "android-host-cancel-packaged-native-held-body"
            report["budget_check"] = check_cancellation(report["metrics"], events,
                report.get("environment"), PROFILES[budget_profile])
            report["qualified"] = report.get("instrumentation_passed", False) and report["budget_check"]["passed"]
            report["deferred"] = ["platform-worker-lifetime", "external-handoff", "process-restart-offline",
                                  "mdk-large-received-sender", "physical-device"]
        if physical and "physical-device" in report["deferred"]:
            report["deferred"].remove("physical-device")
        report["deferred"].extend(["manual-ui-flows", "representative-large-file-performance"])
        report["reverse_cleanup_failed"] = False
        for port in forwards:
            try:
                adb_command(adb, serial, "reverse", "--remove", f"tcp:{port}")
            except (subprocess.SubprocessError, OSError):
                report["reverse_cleanup_failed"] = True
        for service in (server, relay):
            if service in started_services:
                service.shutdown()
            service.server_close()
        for thread in threads:
            thread.join(5)
        report["qualified"] = report["qualified"] and failure is None and not report["reverse_cleanup_failed"]
        output.write_text(json.dumps(report, indent=2) + "\n")
    if failure is not None or not report["qualified"] or report["reverse_cleanup_failed"]:
        raise RuntimeError("controlled received probe failed; see redacted report, not a closure claim")


def main():
    """Require explicit device and report paths; never install, uninstall or clear an app."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--budget-profile", choices=PROFILES, default="reference-api30-arm64")
    parser.add_argument("--private-debug", action="store_true", help="Save raw test output in the private run root only")
    parser.add_argument("--android-send-controller", action="store_true", help="Require genuine shipping-controller send and exact retained reads")
    parser.add_argument("--held-cancellation", action="store_true", help="Require bounded native cancel, server disconnect and quiet interval")
    parser.add_argument("--process-restart", action="store_true", help="Force-stop the isolated fixture between retained-read processes")
    parser.add_argument("--physical-fixture-serial", help="Explicitly opt into the matching physical serial with the Pixel profile; install only the isolated fixture in place after a private backup")
    args = parser.parse_args()
    run(args.adb, args.serial, args.root, args.output, args.private_debug, args.budget_profile, args.android_send_controller, args.held_cancellation, args.process_restart, args.physical_fixture_serial)


if __name__ == "__main__":
    main()
