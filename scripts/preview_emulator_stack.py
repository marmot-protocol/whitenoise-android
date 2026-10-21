"""Collect bounded host function frames on a disposable CI runner without retaining debugger output."""

import json
import os
from pathlib import Path
import resource
import signal
import subprocess
import time


def no_core_dump():
    """Prevent the diagnostic process from leaving a memory-bearing kernel core file."""
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))


def stop_debugger(debugger):
    """Interrupt only the sidecar, allowing its finally block to detach before bounded termination."""
    if debugger.poll() is None:
        debugger.send_signal(signal.SIGINT)
        try:
            debugger.wait(timeout=5)
        except subprocess.TimeoutExpired:
            debugger.terminate()
            try:
                debugger.wait(timeout=3)
            except subprocess.TimeoutExpired:
                debugger.kill()
                debugger.wait(timeout=3)


def start_debugger(pid, reports, expected_executable):
    """Attach only to the caller's verified host executable and wait for actual execution to resume."""
    if os.environ.get('GITHUB_ACTIONS') != 'true' or os.environ.get('RUNNER_OS') != 'Linux':
        raise RuntimeError('Disposable hosted Linux required')
    if pid <= 0 or Path(f'/proc/{pid}/exe').resolve(strict=True) != expected_executable.resolve(strict=True):
        raise RuntimeError('Unexpected host diagnostic executable')
    reports.mkdir(parents=True, exist_ok=True)
    ready = reports / f'emulator-stack-ready-{pid}.json'
    report = reports / f'emulator-stack-{pid}.json'
    if ready.exists() or report.exists():
        raise RuntimeError('Fresh diagnostic receipts required')
    env = {**os.environ, 'DEBUGINFOD_URLS': '', 'STACK_PID': str(pid),
           'STACK_READY': str(ready.resolve()), 'STACK_REPORT': str(report.resolve())}
    debugger = subprocess.Popen(
        ['gdb', '--batch', '--quiet', '--nx', '--nh',
         '-iex', 'set auto-load off', '-iex', 'set debuginfod enabled off',
         '-x', str(Path(__file__).with_name('preview_emulator_stack_gdb.py'))],
        env=env, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        preexec_fn=no_core_dump)
    try:
        deadline = time.monotonic() + 15
        while not ready.exists():
            if debugger.poll() is not None or time.monotonic() >= deadline:
                raise RuntimeError('Host debugger did not confirm resumed execution')
            time.sleep(0.05)
        if json.loads(ready.read_text()) != {'pid': pid, 'resumed': True}:
            raise RuntimeError('Invalid host debugger resume receipt')
        return debugger
    except BaseException:
        stop_debugger(debugger)
        raise


def attach_emulator(reports):
    """Select the single live observed AVD process and verify it is the SDK's real QEMU executable."""
    records = [json.loads(path.read_text()) for path in reports.glob('emulator-process-*.json')]
    primary = [record for record in records if record.get('avd_launch') and 'ended_at' not in record]
    if len(primary) != 1:
        raise RuntimeError('Exactly one live observed AVD process required')
    directory = (Path(os.environ['ANDROID_HOME']) / 'emulator/qemu/linux-x86_64').resolve(strict=True)
    executable = Path(f"/proc/{primary[0]['pid']}/exe").resolve(strict=True)
    if executable.parent != directory or executable.name not in {'qemu-system-x86_64', 'qemu-system-x86_64-headless'}:
        raise RuntimeError('Observed AVD process is not the expected SDK QEMU executable')
    return start_debugger(primary[0]['pid'], reports, executable)
