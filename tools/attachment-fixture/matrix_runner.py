"""Run the controlled attachment latency matrix on a disposable emulator; failed attempts stay in the report."""

import argparse
import json
from pathlib import Path
import subprocess
import threading
import time
import uuid

from budget_checker import PROFILES
from device_runner import APP, adb_command
from fixture_relay import FixtureRelay
from fixture_server import FixtureServer
from matrix_report import aggregate, check_matrix
from media_lifecycle_runner import metrics_of, passed

PROBE = "dev.ipf.whitenoise.android.media.MediaAttachmentLatencyProbe#measureControlledMatrix"
INSTRUMENT_TIMEOUT_SECONDS = 3600
KIB, MIB = 1024, 1024 * 1024
# Declared links: (down kbit/s, up kbit/s, latency ms) and the (size, repetitions) plan each can afford.
LINKS = {
    "unshaped": ((0, 0, 0), [(64 * KIB, 20), (MIB, 10), (8 * MIB, 5), (30 * MIB, 3)]),
    "wifi": ((50_000, 20_000, 20), [(64 * KIB, 10), (MIB, 5), (8 * MIB, 3), (30 * MIB, 1)]),
    "constrained": ((4_000, 1_000, 120), [(64 * KIB, 5), (MIB, 3), (8 * MIB, 1), (30 * MIB, 1)]),
}
QUICK = [(64 * KIB, 3), (MIB, 2), (8 * MIB, 1)]


def plan_argument(plan):
    """The probe's size:repetitions list."""
    return ",".join(f"{size}:{reps}" for size, reps in plan)


def instrument(adb, serial, ports, stage, session, plan=None):
    """Run one probe stage; the host owns link shaping and the force-stop between stages."""
    command = [
        adb, "-s", serial, "shell", "am", "instrument", "-w", "-r",
        "-e", "class", PROBE, "-e", "allowControlledAttachmentProbe", "true",
        "-e", "fixtureBlobPort", str(ports[0]), "-e", "fixtureRelayPort", str(ports[1]),
        "-e", "fixtureMatrixStage", stage, "-e", "fixtureRestartSession", session,
        *(["-e", "fixtureMatrixPlan", plan_argument(plan)] if plan else []),
        APP + ".test/androidx.test.runner.AndroidJUnitRunner",
    ]
    return subprocess.run(command, check=True, capture_output=True, text=True,
                          timeout=INSTRUMENT_TIMEOUT_SECONDS).stdout


def run_profile(adb, serial, ports, server, name, quick):
    """One declared link: send and read in the foreground, force-stop the isolated package, then read offline."""
    shape, plan = LINKS[name]
    plan = QUICK if quick else plan
    start = len(server.ledger.snapshot())
    session = str(uuid.uuid4())
    server.set_shape(*shape)
    first = instrument(adb, serial, ports, "foreground", session, plan)
    boundary = len(server.ledger.snapshot()) - start
    # Restart reads are offline and unshaped: any acquisition after this point is a retention failure.
    server.set_shape(0, 0, 0)
    server.acquisition_unavailable.set()
    server.ledger.event(None, "control", "acquisition_unavailable")
    result = {"name": name, "shape": {"down_kbps": shape[0], "up_kbps": shape[1], "latency_ms": shape[2]},
              "plan": [[s, r] for s, r in plan], "foreground_metrics": metrics_of(first),
              "foreground_passed": passed(first), "recreated_metrics": [], "recreated_passed": False}
    try:
        if result["foreground_passed"]:
            adb_command(adb, serial, "shell", "am", "force-stop", APP)
            second = instrument(adb, serial, ports, "recreated", session)
            result["recreated_metrics"] = metrics_of(second)
            result["recreated_passed"] = passed(second)
    finally:
        server.acquisition_unavailable.clear()
        server.ledger.event(None, "control", "restore_acquisition")
        server.set_shape(0, 0, 0)
        result["ledger"] = server.ledger.snapshot()[start:]
        result["boundary"] = boundary
    return result


def run(adb, serial, root, output, budget_profile="reference-api30-arm64", profiles=("unshaped",), quick=False):
    """Run each requested link on one emulator and write the raw, aggregate and checked report."""
    if not serial.startswith("emulator-") or budget_profile == "pixel-api37-arm64" or budget_profile not in PROFILES:
        raise ValueError("matrix requires a disposable emulator and a declared profile")
    if not profiles or any(p not in LINKS for p in profiles):
        raise ValueError("unknown matrix profile")
    if adb_command(adb, serial, "shell", "getprop", "ro.kernel.qemu").strip() != "1":
        raise ValueError("device is not an emulator")
    if f"package:{APP}" not in adb_command(adb, serial, "shell", "pm", "list", "packages", APP).splitlines():
        raise ValueError("install the isolated measurement APK in place before running")
    server, relay = FixtureServer(root), FixtureRelay()
    report = {"schema": 1, "scope": "android-controlled-attachment-latency-matrix", "qualified": False, "raw": None}
    forwards, threads, started, failure = [], [], [], None
    results = []
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
        for name in profiles:
            results.append(run_profile(adb, serial, ports, server, name, quick))
        environment = {"api": adb_command(adb, serial, "shell", "getprop", "ro.build.version.sdk").strip(),
                       "abi": adb_command(adb, serial, "shell", "getprop", "ro.product.cpu.abi").strip()}
        report["raw"] = {"schema": 1, "environment": environment, "profiles": results}
    except (subprocess.SubprocessError, OSError, ValueError) as error:
        failure = error
        report["failure_class"] = type(error).__name__
        report["raw"] = {"schema": 1, "environment": None, "profiles": results}
    finally:
        # Failed and partial attempts stay in the ledger and report; nothing is reset.
        time.sleep(0.5)
        raw = report["raw"]
        report["matrix_check"] = check_matrix(raw) if raw and raw["profiles"] else {"passed": False}
        report["aggregate"] = aggregate(raw) if report["matrix_check"]["passed"] else None
        report["stages_passed"] = all(p["foreground_passed"] and p["recreated_passed"] for p in results) and bool(results)
        report["qualified"] = (report["stages_passed"] and report["matrix_check"]["passed"] and failure is None
                               and raw is not None and raw.get("environment") == PROFILES[budget_profile])
        report["deferred"] = ["physical-device-wifi", "representative-production-network", "target-budgets"]
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
        output.write_text(json.dumps(report) + "\n")
    if failure is not None or not report["qualified"]:
        raise RuntimeError("matrix failed; see the redacted report, not a closure claim")


def main():
    """Require explicit device and report paths; never install, uninstall or clear an app."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--budget-profile", choices=PROFILES, default="reference-api30-arm64")
    parser.add_argument("--profiles", default="unshaped", help="Comma list of " + ",".join(LINKS))
    parser.add_argument("--quick", action="store_true", help="Run a small plan to prove the harness, not to measure")
    args = parser.parse_args()
    run(args.adb, args.serial, args.root, args.output, args.budget_profile, tuple(args.profiles.split(",")), args.quick)


if __name__ == "__main__":
    main()
