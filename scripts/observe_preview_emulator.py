"""Retain host process exit metadata without collecting emulator memory or weakening preview checks."""

from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import resource
import shlex
import signal
import subprocess
import sys

REPORTS = Path(__file__).resolve().parents[1] / 'build/mdk-signed-preview'


def hosted_linux():
    """Refuse SDK launcher changes outside the disposable hosted Linux runner."""
    if os.environ.get('GITHUB_ACTIONS') != 'true' or os.environ.get('RUNNER_OS') != 'Linux':
        raise RuntimeError('Hosted Linux runner required for emulator supervision')


def timestamp():
    """Use UTC timestamps to order emulator termination against driver completion."""
    return datetime.now(timezone.utc).isoformat()


def oom_counters():
    """Read only cgroup v2 OOM counters, leaving unavailable host accounting explicit."""
    try:
        entry = next(line[3:] for line in Path('/proc/self/cgroup').read_text().splitlines()
                     if line.startswith('0::'))
        relative = Path(entry.lstrip('/'))
        if '..' in relative.parts:
            return None
        events = Path('/sys/fs/cgroup') / relative / 'memory.events'
        return {key: int(value) for key, value in (line.split() for line in events.read_text().splitlines())
                if key in {'oom', 'oom_kill', 'oom_group_kill'}}
    except (OSError, StopIteration, ValueError):
        return None


def disable_core_dumps():
    """Prevent the observed child from writing a kernel core dump containing fixture secrets."""
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))


def observe(command, reports, executable=None):
    """Wait for the real launcher, retain its signal/status and forward failure without restarting it."""
    reports.mkdir(parents=True, exist_ok=True)
    facts = {'started_at': timestamp(), 'oom_before': oom_counters()}
    child = subprocess.Popen(command, executable=executable, preexec_fn=disable_core_dumps)
    facts['pid'] = child.pid
    report = reports / 'emulator-process.json'
    report.write_text(json.dumps(facts, indent=2) + '\n')
    previous = {signum: signal.getsignal(signum) for signum in (signal.SIGINT, signal.SIGTERM)}

    def terminate(signum, frame):
        """Forward cancellation to the observed process while retaining its terminal status."""
        child.send_signal(signum)

    for signum in previous:
        signal.signal(signum, terminate)
    try:
        result = child.wait()
    finally:
        for signum, handler in previous.items():
            signal.signal(signum, handler)
    facts.update({'ended_at': timestamp(), 'returncode': result,
                  'signal': signal.Signals(-result).name if result < 0 else None,
                  'max_rss_kib': resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss,
                  'oom_after': oom_counters(),
                  'driver_finished_before_exit': (reports / 'driver-finished.json').exists()})
    report.write_text(json.dumps(facts, indent=2) + '\n')
    return result if result >= 0 else 128 - result


def install():
    """Wrap only this runner's SDK launcher, preserving its directory, bytes, arguments and environment."""
    hosted_linux()
    sdk = Path(os.environ['ANDROID_HOME']).resolve() / 'emulator'
    launcher = sdk / 'emulator'
    original = sdk / 'emulator.observed-original'
    if launcher.is_symlink() or not launcher.is_file() or original.exists() or original.is_symlink():
        raise RuntimeError('Expected one unmodified SDK emulator launcher')
    revision = re.findall(r'^Pkg\.Revision\s*=\s*([0-9.]+)\s*$',
                          (sdk / 'source.properties').read_text(), re.M)
    if len(revision) != 1:
        raise RuntimeError('SDK emulator revision unavailable')
    REPORTS.mkdir(parents=True, exist_ok=True)
    (REPORTS / 'emulator-version.txt').write_text(revision[0] + '\n')
    (REPORTS / 'emulator-options.txt').write_text(os.environ['PREVIEW_EMULATOR_OPTIONS'] + '\n')
    mode = launcher.stat().st_mode
    launcher.rename(original)
    try:
        launcher.write_text('#!/bin/sh\nexec ' + shlex.join(
            [sys.executable, str(Path(__file__).resolve()), 'emulator', str(REPORTS), str(original)]) + ' "$@"\n')
        launcher.chmod(mode)
    except BaseException:
        original.replace(launcher)
        raise


def driver():
    """Run the unchanged qualification driver and mark completion before normal action teardown."""
    hosted_linux()
    result = subprocess.call([sys.executable, str(Path(__file__).with_name('qualify_signed_preview.py'))])
    REPORTS.mkdir(parents=True, exist_ok=True)
    (REPORTS / 'driver-finished.json').write_text(json.dumps(
        {'finished_at': timestamp(), 'returncode': result}) + '\n')
    return result if result >= 0 else 128 - result


def main():
    """Expose only setup, emulator observation and the existing qualification driver on hosted Linux."""
    hosted_linux()
    if sys.argv[1:] == ['install']:
        install()
        return 0
    if sys.argv[1:] == ['driver']:
        return driver()
    if len(sys.argv) >= 4 and sys.argv[1] == 'emulator':
        original = Path(sys.argv[3])
        return observe([str(original.with_name('emulator')), *sys.argv[4:]],
                       Path(sys.argv[2]), executable=str(original))
    raise RuntimeError('Expected install, driver or emulator command')


if __name__ == '__main__':
    sys.exit(main())
