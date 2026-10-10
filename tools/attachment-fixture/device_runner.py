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
from phases_checker import check_phases
from resume_checker import check_resume
from unknown_length_checker import check_unknown_length
from background_checker import check_background
from automatic_resume_checker import check_automatic_resume

APP = "dev.ipf.whitenoise.android.medialatency"
PROBE = "dev.ipf.whitenoise.android.media.MediaAttachmentLatencyProbe#measureControlledReceivedAttachment"
LEDGER_COMPLETION_TIMEOUT_SECONDS = 5


def adb_command(adb, serial, *args):
    """Use only the explicit verified device; never fall back to the default device."""
    return subprocess.run([adb, "-s", serial, *args], check=True, capture_output=True, text=True, timeout=180).stdout


def failure_diagnostics(error, stage, adb, serial, physical):
    """Keep fixed failure categories; raw subprocess output and crash messages remain in memory."""
    details = {"stage": stage, "timed_out": isinstance(error, subprocess.TimeoutExpired)}
    code = getattr(error, "returncode", None)
    if type(code) is int:
        details["adb_returncode"] = code
    transcript = "\n".join(value for value in (getattr(error, "stdout", None), getattr(error, "stderr", None))
                           if isinstance(value, str))
    statuses = (("INSTRUMENTATION_RESULT: shortMsg=Process crashed.", "process-crashed"),
                ("Unable to find instrumentation info", "missing-instrumentation"),
                ("error: device offline", "device-offline"),
                (f"error: device '{serial}' not found", "device-missing"),
                ("error: no devices/emulators found", "device-missing"),
                ("error: closed", "transport-closed"), ("error: protocol fault", "transport-protocol-fault"),
                ("INSTRUMENTATION_ABORTED", "instrumentation-aborted"),
                ("INSTRUMENTATION_FAILED", "instrumentation-failed"))
    details["subprocess_status"] = next((status for marker, status in statuses if marker in transcript), "unknown")
    if stage not in ("instrumentation", "restart-instrumentation") or physical:
        return details
    try:
        capture = subprocess.run([adb, "-s", serial, "logcat", "-b", "crash", "-d", "-t", "100", "-v", "brief"],
                                 check=True, capture_output=True, text=True, timeout=10)
        # Recent buffer evidence is diagnostic, not proof that a prior crash belongs to this invocation.
        blocks = [block for block in capture.stdout.split("FATAL EXCEPTION:")[1:]
                  if f"Process: {APP}, PID:" in block]
        details["crash_capture"] = "captured"
        details["recent_app_crash"] = bool(blocks) or f">>> {APP} <<<" in capture.stdout
        classes = ("kotlin.UninitializedPropertyAccessException", "java.lang.OutOfMemoryError",
                   "java.lang.NullPointerException", "java.lang.IllegalStateException",
                   "java.lang.SecurityException", "java.lang.UnsatisfiedLinkError")
        details["recent_app_crash_class"] = next((name for name in classes if any(name in b for b in blocks)), "unknown")
    except (subprocess.SubprocessError, OSError):
        details["crash_capture"] = "unavailable"
    return details


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


def wait_for_phase_completion(ledger, start):
    """Wait until both genuine uploads and every counted request, including permanent misses, have terminal events."""
    deadline = time.monotonic() + LEDGER_COMPLETION_TIMEOUT_SECONDS
    while True:
        events = ledger.snapshot()[start:]
        requests = {e["seq"] for e in events if e["kind"] in ("get", "head")}
        completed = {e["request"] for e in events if e["kind"] == "complete"}
        uploads = {e["seq"] for e in events if e["kind"] == "upload"}
        uploaded = {e["request"] for e in events if e["kind"] == "upload_complete"}
        if len(uploads) == 2 and uploads <= uploaded and requests and requests <= completed:
            return events, True
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return events, False
        time.sleep(min(0.01, remaining))


def run(adb, serial, root, output, private_debug=False, budget_profile="reference-api30-arm64", android_send_controller=False, held_cancellation=False, process_restart=False, physical_fixture_serial=None, transport_resume=None, unknown_length=False, platform_background=False, platform_lock=False, automatic_resume=False, native_phases=False):
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
    if transport_resume is not None and (
        transport_resume not in ("compatible", "changed-validator")
        or not android_send_controller or held_cancellation or process_restart
    ):
        raise ValueError("transport resume requires a separate genuine-controller scenario")
    if automatic_resume and (physical or not android_send_controller or held_cancellation or process_restart or transport_resume or unknown_length or platform_background or platform_lock or native_phases):
        raise ValueError("automatic resume requires a separate generated-controller emulator scenario")
    if platform_lock and not platform_background:
        raise ValueError("screen-lock qualification requires the separate background scenario")
    if platform_background and (physical or not android_send_controller or held_cancellation or process_restart or transport_resume or unknown_length):
        raise ValueError("platform background requires a separate generated-controller emulator scenario")
    if unknown_length and (not android_send_controller or held_cancellation or process_restart or transport_resume):
        raise ValueError("unknown length requires a separate genuine-controller scenario")
    if native_phases and (not android_send_controller or held_cancellation or process_restart or transport_resume
                          or unknown_length or platform_background or automatic_resume):
        raise ValueError("native phases require a separate genuine-controller scenario")
    session = str(uuid.uuid4()) if process_restart else None
    # Native group fallback uses hash.bin. Match the published locator only for
    # held-body qualification so native fallback cannot silently replace its locator.
    server = FixtureServer(root, upload_extension=".bin") if transport_resume or unknown_length or platform_background or automatic_resume else FixtureServer(root)
    relay = FixtureRelay()
    forwards = []
    threads = []
    report = {"schema": 1, "scope": "android-host-resolver-packaged-native", "metrics": [], "qualified": False,
              "android_send_controller_requested": android_send_controller,
              "device_kind": "physical" if physical else "emulator"}
    before = server.ledger.snapshot()
    failure = None
    stage = "fixture-services"
    started_services = []
    try:
        for service in (server, relay):
            thread = threading.Thread(target=service.serve_forever, daemon=True)
            thread.start()
            threads.append(thread)
            started_services.append(service)
        stage = "reverse-binding"
        for port in (server.server_port, relay.port):
            # --no-rebind fails instead of overwriting an existing reverse owned by another task.
            adb_command(adb, serial, "reverse", "--no-rebind", f"tcp:{port}", f"tcp:{port}")
            forwards.append(port)
        stage = "instrumentation"
        result = adb_command(
            adb, serial, "shell", "am", "instrument", "-w", "-r",
            "-e", "class", ("dev.ipf.whitenoise.android.media.UnknownLengthAttachmentDeviceTest#nativeUnknownLengthProgressHasAccessibleByteOnlyControl" if unknown_length else PROBE),
            "-e", "allowControlledAttachmentProbe", "true",
            "-e", "fixtureBlobPort", str(server.server_port),
            "-e", "fixtureRelayPort", str(relay.port),
            *(["-e", "fixtureUseAndroidSendController", "true"] if android_send_controller else []),
            *(["-e", "fixtureCancellation", "true"] if held_cancellation else []),
            *(["-e", "fixtureTransportResume", transport_resume] if transport_resume else []),
            *(["-e", "fixtureUnknownLength", "true"] if unknown_length else []),
            *(["-e", "fixtureFunctionalBodyCase", "automatic-platform-resume"] if automatic_resume else []),
            *(["-e", "fixtureNativePhases", "true"] if native_phases else []),
            *(["-e", "fixtureFunctionalBodyCase", "platform-lock" if platform_lock else "platform-background"] if platform_background else []),
            *(["-e", "fixtureRestartRole", "prepare", "-e", "fixtureRestartSession", session] if process_restart else []),
            APP + ".test/androidx.test.runner.AndroidJUnitRunner",
        )
        results = [result]
        if process_restart and "OK (1 test)" in result and "FAILURES!!!" not in result:
            stage = "restart-stop"
            adb_command(adb, serial, "shell", "am", "force-stop", APP)
            stage = "restart-instrumentation"
            results.append(adb_command(
                adb, serial, "shell", "am", "instrument", "-w", "-r",
                "-e", "class", PROBE, "-e", "allowControlledAttachmentProbe", "true",
                "-e", "fixtureBlobPort", str(server.server_port),
                "-e", "fixtureRelayPort", str(relay.port),
                "-e", "fixtureRestartRole", "read", "-e", "fixtureRestartSession", session,
                APP + ".test/androidx.test.runner.AndroidJUnitRunner",
            ))
            result = "\n".join(results)
        stage = "metrics-parse"
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
        stage = "device-environment"
        report["environment"] = {
            "api": adb_command(adb, serial, "shell", "getprop", "ro.build.version.sdk").strip(),
            "abi": adb_command(adb, serial, "shell", "getprop", "ro.product.cpu.abi").strip(),
        }
    except (subprocess.SubprocessError, OSError, ValueError) as error:
        failure = error
        report["failure_class"] = type(error).__name__
        report["failure_diagnostics"] = failure_diagnostics(error, stage, adb, serial, physical)
    finally:
        # Retain failure/timeout evidence before cleanup; never discard failed attempts.
        if native_phases:
            events, report["ledger_finalized"] = wait_for_phase_completion(server.ledger, len(before))
        elif held_cancellation or transport_resume or automatic_resume:
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
        if transport_resume:
            report["scope"] = "android-host-resolver-packaged-native-transport-resume"
            report["transport_resume_check"] = check_resume(report["metrics"], events, transport_resume)
            # The unchanged 1 KiB performance ceilings do not describe this
            # 4 MiB functional probe. Keep measured peaks/timing and defer SLOs.
            report["budget_check"] = {"applicable": False, "passed": False,
                                     "reason": "representative performance remains unqualified"}
            report["qualified"] = (
                report.get("instrumentation_passed", False) and report["ledger_finalized"]
                and report["transport_resume_check"]["passed"]
                and report.get("environment") == PROFILES[budget_profile]
            )
            report["deferred"] = ["platform-worker-lifetime", "external-handoff", "process-restart-offline",
                                  "mdk-large-received-sender", "physical-device"]
        if automatic_resume:
            report["scope"] = "ordinary-android-work-stop-and-native-resume"
            report["automatic_resume_check"] = check_automatic_resume(report["metrics"], events)
            report["budget_check"] = {"applicable": False, "passed": False, "reason": "functional scheduler qualification only"}
            report["qualified"] = (report.get("instrumentation_passed", False) and report["ledger_finalized"]
                                   and report["automatic_resume_check"]["passed"]
                                   and report.get("environment") == PROFILES[budget_profile])
            report["deferred"] = ["external-handoff", "process-restart-offline", "mdk-large-received-sender", "physical-device"]
        if unknown_length:
            report["scope"] = "android-host-resolver-packaged-native-unknown-length"
            report["unknown_length_check"] = check_unknown_length(report["metrics"], events)
            report["budget_check"] = {"applicable": False, "passed": False,
                                     "reason": "representative performance remains unqualified"}
            report["qualified"] = (
                report.get("instrumentation_passed", False) and report["ledger_finalized"]
                and report["unknown_length_check"]["passed"]
                and report.get("environment") == PROFILES[budget_profile]
            )
            report["deferred"] = ["platform-worker-lifetime", "external-handoff", "process-restart-offline",
                                  "mdk-large-received-sender", "physical-device"]
        if native_phases:
            report["scope"] = "android-host-resolver-packaged-native-phase-observer"
            report["native_phases_check"] = check_phases(report["metrics"], events)
            # The unchanged 1 KiB ceilings do not describe this 32 MiB functional probe.
            report["budget_check"] = {"applicable": False, "passed": False,
                                     "reason": "representative performance remains unqualified"}
            report["qualified"] = (
                report.get("instrumentation_passed", False) and report["ledger_finalized"]
                and report["native_phases_check"]["passed"]
                and report.get("environment") == PROFILES[budget_profile]
            )
            report["deferred"] = ["platform-worker-lifetime", "external-handoff", "process-restart-offline",
                                  "mdk-large-received-sender", "physical-device"]
        if platform_background:
            report["scope"] = "android-scheduled-interactive-background-continuation"
            report["platform_background_check"] = check_background(report["metrics"], events, report.get("environment"), locked=platform_lock)
            report["budget_check"] = {"applicable": False, "passed": False,
                                     "reason": "representative performance remains unqualified"}
            report["qualified"] = (
                report.get("instrumentation_passed", False) and report["ledger_finalized"]
                and report["platform_background_check"]["passed"]
                and report.get("environment") == PROFILES[budget_profile]
            )
            report["deferred"] = ["platform-job-stop-resume", "screen-lock", "external-handoff", "process-restart-offline",
                                  "mdk-large-received-sender", "physical-device"]
        if platform_lock and platform_background and report["qualified"]:
            report["deferred"].remove("screen-lock")
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
    parser.add_argument("--transport-resume", choices=("compatible", "changed-validator"), help="Qualify native retry after a held 4 MiB body is interrupted; Android scheduler lifetime stays deferred")
    parser.add_argument("--unknown-length", action="store_true", help="Require native byte-only progress without Content-Length")
    parser.add_argument("--platform-background", action="store_true", help="Require thirty seconds behind Home with a real elevated Android scheduler")
    parser.add_argument("--automatic-resume", action="store_true", help="Stop and automatically resume the same ordinary Android WorkSpec with native compatible-prefix recovery")
    parser.add_argument("--platform-lock", action="store_true", help="Require the emulator keyguard and screen-off during sustained background transfer")
    parser.add_argument("--native-phases", action="store_true", help="Observe a paced known-length transfer and a permanent-miss recovery through the production progress feed")
    args = parser.parse_args()
    run(args.adb, args.serial, args.root, args.output, args.private_debug, args.budget_profile, args.android_send_controller, args.held_cancellation, args.process_restart, args.physical_fixture_serial, args.transport_resume, args.unknown_length, args.platform_background, args.platform_lock, args.automatic_resume, args.native_phases)


if __name__ == "__main__":
    main()
