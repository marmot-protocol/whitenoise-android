"""Run the opt-in fixture on an emulator, preserving host counters across native clients."""

import argparse
import json
import os
from pathlib import Path
import subprocess
import threading
import time

from budget_checker import PROFILES, check_budget
from fixture_relay import FixtureRelay
from fixture_server import FixtureServer

APP = "dev.ipf.whitenoise.android.medialatency"
PROBE = "dev.ipf.whitenoise.android.media.MediaAttachmentLatencyProbe#measureControlledReceivedAttachment"
LEDGER_COMPLETION_TIMEOUT_SECONDS = 5


def adb_command(adb, serial, *args):
    """Use only the explicit verified emulator; never fall back to the default device."""
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


def run(adb, serial, root, output, private_debug=False, budget_profile="reference-api30-arm64"):
    """Count genuine uploaded ciphertext and received bodies without resetting failed attempts."""
    if not serial.startswith("emulator-") or adb_command(adb, serial, "shell", "getprop", "ro.kernel.qemu").strip() != "1":
        raise ValueError("fixture runner requires a disposable emulator")
    if f"package:{APP}" not in adb_command(adb, serial, "shell", "pm", "list", "packages", APP).splitlines():
        raise ValueError("install the isolated measurement APK in place before running")
    if budget_profile not in PROFILES:
        raise ValueError("unknown attachment budget profile")
    server = FixtureServer(root)
    relay = FixtureRelay()
    forwards = []
    threads = []
    report = {"schema": 1, "scope": "android-host-resolver-packaged-native", "metrics": [], "qualified": False}
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
            APP + ".test/androidx.test.runner.AndroidJUnitRunner",
        )
        if private_debug:
            # Explicit local debugging only; never archived by CI or copied into redacted reports.
            with os.fdopen(os.open(root / "instrumentation.txt", os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as capture:
                capture.write(result)
        for line in result.splitlines():
            prefix = "INSTRUMENTATION_STATUS: controlled_attachment_json="
            if line.startswith(prefix):
                report["metrics"].append(json.loads(line[len(prefix):]))
        report["instrumentation_passed"] = "OK (1 test)" in result and "FAILURES!!!" not in result
        report["environment"] = {
            "api": adb_command(adb, serial, "shell", "getprop", "ro.build.version.sdk").strip(),
            "abi": adb_command(adb, serial, "shell", "getprop", "ro.product.cpu.abi").strip(),
        }
    except (subprocess.SubprocessError, OSError, ValueError) as error:
        failure = error
        report["failure_class"] = type(error).__name__
    finally:
        # Retain failure/timeout evidence before cleanup; never discard failed attempts.
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
            report["budget_check"]["violations"].append("emulator API/ABI does not match budget profile")
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
        report["deferred"] = ["platform-worker-lifetime", "external-handoff", "process-restart-offline",
                              "android-controller-genuine-send", "mdk-large-received-sender", "physical-device"]
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
    """Require explicit emulator and report paths; never install, uninstall or clear an app."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--budget-profile", choices=PROFILES, default="reference-api30-arm64")
    parser.add_argument("--private-debug", action="store_true", help="Save raw test output in the private run root only")
    args = parser.parse_args()
    run(args.adb, args.serial, args.root, args.output, args.private_debug, args.budget_profile)


if __name__ == "__main__":
    main()
