#!/usr/bin/env python3
"""Bound one CI unit-suite invocation, retaining diagnostics before the job timeout."""

import argparse
import json
import math
import os
from pathlib import Path
import signal
import subprocess
import time


def session_processes(session):
    """Read Linux session identities, including children reparented after launch."""
    processes = []
    for entry in Path('/proc').iterdir():
        if not entry.name.isdigit():
            continue
        try:
            fields = (entry / 'stat').read_text().rsplit(')', 1)[1].split()
            if int(fields[3]) != session or fields[0] == 'Z':
                continue
            processes.append({
                'pid': int(entry.name), 'start': fields[19],
                'command': (entry / 'cmdline').read_bytes().replace(b'\0', b' ').decode(errors='replace'),
            })
        except (OSError, ValueError, IndexError):
            continue  # Processes can exit while /proc is being read.
    return processes


def signal_owned(process, session, signum):
    """Pin a PID before checking its identity again, avoiding PID-reuse kills."""
    try:
        descriptor = os.pidfd_open(process['pid'])
    except ProcessLookupError:
        return
    try:
        if any(p['pid'] == process['pid'] and p['start'] == process['start']
               for p in session_processes(session)):
            signal.pidfd_send_signal(descriptor, signum)
    except ProcessLookupError:
        pass
    finally:
        os.close(descriptor)


def collect_diagnostics(session, directory, budget=30):
    processes = session_processes(session)
    (directory / 'processes.json').write_text(json.dumps(processes, indent=2) + '\n')
    workers = [p for p in processes if 'Gradle Test Executor' in p['command']
               and 'GradleWorkerMain' in p['command']]
    end = time.monotonic() + budget
    with (directory / 'thread-dumps.txt').open('w') as output:
        if not workers:
            output.write('No invocation-owned Gradle Test Executor observed.\n')
        for worker in workers:
            remaining = end - time.monotonic()
            if remaining <= 0:
                output.write('Aggregate diagnostics deadline reached.\n')
                break
            if not any(p['pid'] == worker['pid'] and p['start'] == worker['start']
                       for p in session_processes(session)):
                continue
            output.write(f"\nGradle Test Executor PID {worker['pid']} start {worker['start']}\n")
            output.flush()
            try:
                subprocess.run(['jcmd', str(worker['pid']), 'Thread.print'],
                               stdout=output, stderr=subprocess.STDOUT,
                               timeout=min(5, remaining), check=False)
            except (OSError, subprocess.TimeoutExpired) as error:
                output.write(f'Bounded thread dump unavailable: {error}\n')


def cleanup(session, child, grace=30):
    end = time.monotonic() + grace
    while True:
        processes = session_processes(session)
        if not processes:
            break
        signum = signal.SIGKILL if time.monotonic() >= end else signal.SIGTERM
        for process in processes:
            signal_owned(process, session, signum)
        child.poll()  # Reap the direct child without waiting for a hung worker.
        if signum == signal.SIGKILL:
            break
        time.sleep(0.05)
    child.wait(timeout=5)


def supervise(command, deadline, directory, diagnostic_budget=30, cleanup_grace=30):
    directory.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    metadata = {'command': command, 'started_monotonic': started,
                'deadline_monotonic': deadline, 'source': os.environ.get('GITHUB_SHA'),
                'started_utc': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())}
    interrupted = []
    previous_handlers = {}

    def interrupted_by(signum, _frame):
        interrupted.append(signum)

    try:
        if not math.isfinite(deadline) or deadline <= started:
            metadata['result'] = 'deadline_exhausted_before_launch'
            return 124
        # Gradle's --no-daemon keeps its single-use daemon in this owned session.
        # Never attach to or terminate cached daemons belonging to another invocation.
        for signum in (signal.SIGINT, signal.SIGTERM):
            previous_handlers[signum] = signal.signal(signum, interrupted_by)
        child = subprocess.Popen(command, start_new_session=True)
        metadata['session'] = child.pid
        while child.poll() is None and time.monotonic() < deadline and not interrupted:
            time.sleep(min(0.1, max(0, deadline - time.monotonic())))
        result = child.poll()
        if result is not None and not interrupted:
            metadata['result'] = 'completed'
            metadata['child_exit'] = result
            # Also clean invocation-owned children if a wrapper exits before them.
            cleanup(child.pid, child, cleanup_grace)
            return result if result >= 0 else 128 - result
        metadata['result'] = 'interrupted' if interrupted else 'deadline_expired'
        try:
            collect_diagnostics(child.pid, directory, diagnostic_budget)
        except OSError as error:
            metadata['diagnostic_error'] = str(error)
        finally:
            cleanup(child.pid, child, cleanup_grace)
        return 128 + interrupted[0] if interrupted else 124
    finally:
        for signum, handler in previous_handlers.items():
            signal.signal(signum, handler)
        metadata['finished_monotonic'] = time.monotonic()
        (directory / 'invocation.json').write_text(json.dumps(metadata, indent=2) + '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ['--'] else args.command
    if not command:
        parser.error('a command is required')
    try:
        deadline = float(os.environ['UNIT_TEST_DEADLINE_MONOTONIC'])
    except (KeyError, ValueError):
        parser.error('UNIT_TEST_DEADLINE_MONOTONIC must be the job-start deadline')
    return supervise(command, deadline, Path('app/build/reports/unit-test-diagnostics'))


if __name__ == '__main__':
    raise SystemExit(main())
