"""Run the two-process genuine media lifecycle fixture on a disposable emulator; failed attempts stay in the report."""

import argparse
import json
from pathlib import Path
import re
import subprocess
import threading
import time
import uuid

from budget_checker import PROFILES
from device_runner import APP, adb_command
from fixture_relay import FixtureRelay
from fixture_server import FixtureServer
from media_checker import EXPECTED, check_media

PROBE = "dev.ipf.whitenoise.android.media.MediaAttachmentLatencyProbe#measureControlledMediaLifecycle"
TILES = ("dev.ipf.whitenoise.android.ui.conversation.media.RetainedMediaTilesDeviceTest"
         "#retainedMediaRendersLocallyWithAutomaticDownloadsOff")
STATUS_PREFIX = "INSTRUMENTATION_STATUS: controlled_attachment_json="
INSTRUMENT_TIMEOUT_SECONDS = 900
LEDGER_COMPLETION_TIMEOUT_SECONDS = 10


def instrument(adb, serial, ports, role, session, target=PROBE, preserve=False):
    """Run one probe process; the timeout is long because the large video uploads and downloads are real."""
    command = [
        adb, "-s", serial, "shell", "am", "instrument", "-w", "-r",
        "-e", "class", target, "-e", "allowControlledAttachmentProbe", "true",
        "-e", "fixtureBlobPort", str(ports[0]), "-e", "fixtureRelayPort", str(ports[1]),
        "-e", "fixtureMediaLifecycle", "true", "-e", "fixtureRestartRole", role,
        *(["-e", "fixtureMediaPreserve", "true"] if preserve else []),
        "-e", "fixtureRestartSession", session, APP + ".test/androidx.test.runner.AndroidJUnitRunner",
    ]
    return subprocess.run(command, check=True, capture_output=True, text=True,
                          timeout=INSTRUMENT_TIMEOUT_SECONDS).stdout


def metrics_of(result):
    """Collect only the closed JSON metrics the probe emits; raw instrumentation text is never kept."""
    return [json.loads(line[len(STATUS_PREFIX):]) for line in result.splitlines() if line.startswith(STATUS_PREFIX)]


def passed(result):
    """The JUnit outcome of one process, independent of any metric it reported."""
    return "OK (1 test)" in result and "FAILURES!!!" not in result


def failure_kind(result):
    """Retain only a closed category from the actual stack header; never native messages or identities."""
    if passed(result):
        return None
    match = re.search(
        r"^INSTRUMENTATION_STATUS: stack=(?:[\w$]+\.)*"
        r"(OutOfMemoryError|TimeoutCancellationException|TimeoutException|AssertionError)(?=[:\s]|$)",
        result, re.MULTILINE,
    )
    categories = {"OutOfMemoryError": "out-of-memory", "TimeoutCancellationException": "timeout",
                  "TimeoutException": "timeout", "AssertionError": "assertion"}
    return categories[match.group(1)] if match else "instrumentation-failure"


def wait_for_completion(ledger, start, uploads=len(EXPECTED), gets=len(EXPECTED),
                        timeout=LEDGER_COMPLETION_TIMEOUT_SECONDS, terminal_kinds=("complete",)):
    """Wait until every expected upload and request has a committed terminal event, or keep the timeout evidence.

    A request is terminal when it carries one of ``terminal_kinds``. A run that cancels a body on purpose passes
    ``disconnect`` as well, because the cancelled attempt ends with the client's disconnect and never completes.
    """
    deadline = time.monotonic() + timeout
    while True:
        events = ledger.snapshot()[start:]
        requests = {e["seq"] for e in events if e["kind"] in ("get", "head")}
        completed = {e["request"] for e in events if e["kind"] in terminal_kinds}
        uploaded = {e["request"] for e in events if e["kind"] == "upload_complete"}
        sent = {e["seq"] for e in events if e["kind"] == "upload"}
        if len(sent) == uploads and sent <= uploaded and len(requests) == gets and requests <= completed:
            return events, True
        if time.monotonic() >= deadline:
            return events, False
        time.sleep(0.01)


def run(adb, serial, root, output, budget_profile="reference-api30-arm64"):
    """Prepare in one process, force-stop only the isolated fixture package, then verify offline in a new one."""
    if not serial.startswith("emulator-") or budget_profile == "pixel-api37-arm64" or budget_profile not in PROFILES:
        raise ValueError("media lifecycle fixture requires a disposable emulator and a declared profile")
    if adb_command(adb, serial, "shell", "getprop", "ro.kernel.qemu").strip() != "1":
        raise ValueError("device is not an emulator")
    if f"package:{APP}" not in adb_command(adb, serial, "shell", "pm", "list", "packages", APP).splitlines():
        raise ValueError("install the isolated measurement APK in place before running")
    server, relay = FixtureServer(root), FixtureRelay()
    session = str(uuid.uuid4())
    report = {"schema": 1, "scope": "android-media-lifecycle-process-restart", "qualified": False,
              "prepare_metrics": [], "read_metrics": [], "tiles_metrics": []}
    start = len(server.ledger.snapshot())
    forwards, threads, started, failure = [], [], [], None
    boundary = None
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
        first = instrument(adb, serial, ports, "prepare", session)
        report["prepare_metrics"] = metrics_of(first)
        events, report["prepare_ledger_finalized"] = wait_for_completion(server.ledger, start)
        boundary = len(events)
        report["prepare_passed"] = passed(first)
        report["prepare_failure_kind"] = failure_kind(first)
        if report["prepare_passed"]:
            adb_command(adb, serial, "shell", "am", "force-stop", APP)
            second = instrument(adb, serial, ports, "read", session, preserve=True)
            report["read_metrics"] = metrics_of(second)
            report["read_passed"] = passed(second)
            report["read_failure_kind"] = failure_kind(second)
            if report["read_passed"]:
                # A third process renders the real tiles over the same restored runtime, then cleans up.
                adb_command(adb, serial, "shell", "am", "force-stop", APP)
                third = instrument(adb, serial, ports, "read", session, target=TILES)
                report["tiles_metrics"] = metrics_of(third)
                report["tiles_passed"] = passed(third)
                report["tiles_failure_kind"] = failure_kind(third)
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
        report["process_boundary_ledger_events"] = boundary
        report["http_get_requests"] = sum(e["kind"] == "get" for e in events)
        report["http_get_requests_after_restart"] = (
            None if boundary is None else sum(e["kind"] in ("get", "head") for e in events[boundary:]))
        report["http_upload_requests"] = sum(e["kind"] == "upload" for e in events)
        report["uploaded_ciphertext_bytes"] = sum(e["value"] for e in events if e["kind"] == "upload_bytes")
        report["successful_ciphertext_body_write_bytes"] = sum(e["value"] for e in events if e["kind"] == "body_bytes")
        report["media_check"] = check_media(report["prepare_metrics"], report["read_metrics"], events, boundary,
                                            report["tiles_metrics"])
        # The unchanged 1 KiB ceilings do not describe these multi-megabyte media; sampled peaks stay in the report.
        report["budget_check"] = {"applicable": False, "passed": False,
                                  "reason": "representative performance remains unqualified"}
        report["qualified"] = (
            report.get("prepare_passed", False) and report.get("read_passed", False)
            and report.get("tiles_passed", False)
            and report.get("prepare_ledger_finalized", False) and report["media_check"]["passed"]
            and report.get("environment") == PROFILES[budget_profile] and failure is None
        )
        report["deferred"] = ["conversation-ui-tiles", "above-64mib-media", "physical-device",
                              "automatic-downloads-off-policy", "representative-performance"]
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
        raise RuntimeError("media lifecycle probe failed; see the redacted report, not a closure claim")


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
