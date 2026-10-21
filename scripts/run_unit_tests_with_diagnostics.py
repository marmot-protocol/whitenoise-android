#!/usr/bin/env python3
"""Bound one CI unit-suite invocation, retaining diagnostics before the job timeout."""

import argparse
import ctypes
import json
import math
import os
from pathlib import Path
import signal
import subprocess
import time


def process_snapshot():
    """Read stable Linux identities without assuming descendants keep a session."""
    processes = {}
    for entry in Path('/proc').iterdir():
        if not entry.name.isdigit():
            continue
        try:
            fields = (entry / 'stat').read_text().rsplit(')', 1)[1].split()
            processes[int(entry.name)] = {
                'pid': int(entry.name), 'start': fields[19], 'ppid': int(fields[1]),
                'session': int(fields[3]), 'state': fields[0],
                'command': (entry / 'cmdline').read_bytes().replace(b'\0', b' ').decode(errors='replace'),
            }
        except (OSError, ValueError, IndexError):
            continue
    return processes


class InvocationProcesses:
    """Track only this launch's descendants, retaining identities across setsid/reparenting."""

    def __init__(self, child):
        self.child = child
        self.known = {}

    def processes(self):
        snapshot = process_snapshot()
        root = snapshot.get(self.child.pid)
        if root is not None and self.child.poll() is None:
            self.known[root['pid']] = root['start']
        # supervise requires a dedicated single-threaded process with no prior
        # children. Every adopted direct child therefore belongs to this launch,
        # even if it calls setsid, clears its environment, or execs immediately.
        for process in snapshot.values():
            if process['ppid'] == os.getpid():
                self.known[process['pid']] = process['start']
        for _ in range(len(snapshot)):
            added = False
            for process in snapshot.values():
                parent = snapshot.get(process['ppid'])
                if (parent is not None and self.known.get(parent['pid']) == parent['start']
                        and self.known.get(process['pid']) != process['start']):
                    self.known[process['pid']] = process['start']
                    added = True
            if not added:
                break
        return [process for process in snapshot.values()
                if self.known.get(process['pid']) == process['start'] and process['state'] != 'Z']

    def reap(self):
        self.child.poll()
        snapshot = process_snapshot()
        for pid, start in self.known.items():
            if pid == self.child.pid or snapshot.get(pid, {}).get('start') != start:
                continue
            try:
                os.waitpid(pid, os.WNOHANG)
            except ChildProcessError:
                pass


def signal_owned(process, invocation, signum):
    """Pin a PID before checking its invocation identity again, avoiding PID reuse."""
    try:
        descriptor = os.pidfd_open(process['pid'])
    except ProcessLookupError:
        return
    try:
        if any(p['pid'] == process['pid'] and p['start'] == process['start']
               for p in invocation.processes()):
            signal.pidfd_send_signal(descriptor, signum)
    except ProcessLookupError:
        pass
    finally:
        os.close(descriptor)


def collect_diagnostics(invocation, directory, budget=30):
    processes = invocation.processes()
    (directory / 'processes.json').write_text(json.dumps(processes, indent=2) + '\n')
    workers = [p for p in processes if 'org.gradle.launcher.daemon.bootstrap.GradleDaemon' in p['command']
               or ('Gradle Test Executor' in p['command'] and 'GradleWorkerMain' in p['command'])]
    end = time.monotonic() + budget
    with (directory / 'thread-dumps.txt').open('w') as output:
        if not workers:
            output.write('No invocation-owned Gradle daemon or Test Executor observed.\n')
        for worker in workers:
            remaining = end - time.monotonic()
            if remaining <= 0:
                output.write('Aggregate diagnostics deadline reached.\n')
                break
            if not any(p['pid'] == worker['pid'] and p['start'] == worker['start']
                       for p in invocation.processes()):
                continue
            output.write(f"\nGradle daemon or Test Executor PID {worker['pid']} start {worker['start']}\n")
            output.flush()
            capture_start = output.tell()
            try:
                subprocess.run(['jcmd', str(worker['pid']), 'Thread.print'],
                               stdout=output, stderr=subprocess.STDOUT,
                               timeout=min(5, remaining), check=False)
            except (OSError, subprocess.TimeoutExpired) as error:
                output.write(f'Bounded thread dump unavailable: {error}\n')
            if not any(p['pid'] == worker['pid'] and p['start'] == worker['start']
                       for p in invocation.processes()):
                # jcmd accepts a numeric PID. Reject even a completed dump if
                # its target identity cannot still be established afterward.
                output.seek(capture_start)
                output.truncate()
                output.write('Thread dump discarded: target identity changed or exited.\n')


def cleanup(invocation, child, grace=30):
    end = time.monotonic() + grace
    kill_end = end + 5
    while True:
        processes = invocation.processes()
        invocation.reap()  # Includes adopted zombies discovered before the live set is filtered.
        if not processes:
            break
        signum = signal.SIGKILL if time.monotonic() >= end else signal.SIGTERM
        for process in processes:
            signal_owned(process, invocation, signum)
        invocation.reap()  # Reap only known children without waiting for a hung worker.
        if signum == signal.SIGKILL and time.monotonic() >= kill_end:
            raise TimeoutError("Invocation children remained alive after bounded cleanup")
        time.sleep(0.05)
    child.wait(timeout=max(0, kill_end - time.monotonic()))
    invocation.reap()


def supervise(command, deadline, directory, diagnostic_budget=30, cleanup_grace=30):
    directory.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    metadata = {'command': command, 'started_monotonic': started,
                'deadline_monotonic': deadline, 'source': os.environ.get('GITHUB_SHA'),
                'started_utc': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())}
    interrupted = []
    libc = ctypes.CDLL(None, use_errno=True)
    previous_subreaper = ctypes.c_int()
    subreaper_set = False
    previous_handlers = {}

    def interrupted_by(signum, _frame):
        interrupted.append(signum)

    try:
        if not math.isfinite(deadline) or deadline <= started:
            metadata['result'] = 'deadline_exhausted_before_launch'
            return 124
        if len(list(Path('/proc/self/task').iterdir())) != 1:
            raise RuntimeError('Supervisor requires a dedicated single-threaded process')
        if any(p['ppid'] == os.getpid() for p in process_snapshot().values()):
            raise RuntimeError('Supervisor cannot adopt unrelated pre-existing children')
        # Single-use Gradle daemons call setsid. Own descendants by ancestry and
        # start identity, never by their name or membership in the wrapper session.
        if libc.prctl(37, ctypes.byref(previous_subreaper), 0, 0, 0) != 0:
            raise OSError(ctypes.get_errno(), 'Cannot read child subreaper state')
        if libc.prctl(36, 1, 0, 0, 0) != 0:
            raise OSError(ctypes.get_errno(), 'Cannot retain invocation orphans')
        subreaper_set = True
        for signum in (signal.SIGINT, signal.SIGTERM):
            previous_handlers[signum] = signal.signal(signum, interrupted_by)
        child = subprocess.Popen(command, start_new_session=True)
        invocation = InvocationProcesses(child)

        def finish_cleanup():
            try:
                cleanup(invocation, child, cleanup_grace)
            except Exception as error:
                metadata['cleanup_error'] = str(error)
                metadata['cleanup_residual_processes'] = invocation.processes()
                raise  # Cleanup failure remains a nonzero supervisor failure.
        metadata['session'] = child.pid
        while child.poll() is None and time.monotonic() < deadline and not interrupted:
            invocation.processes()
            time.sleep(min(0.1, max(0, deadline - time.monotonic())))
        result = child.poll()
        if result is not None and not interrupted:
            metadata['result'] = 'completed'
            metadata['child_exit'] = result
            # Also clean invocation-owned children if a wrapper exits before them.
            finish_cleanup()
            return result if result >= 0 else 128 - result
        metadata['result'] = 'interrupted' if interrupted else 'deadline_expired'
        try:
            collect_diagnostics(invocation, directory, diagnostic_budget)
        except OSError as error:
            metadata['diagnostic_error'] = str(error)
        finally:
            finish_cleanup()
        return 128 + interrupted[0] if interrupted else 124
    finally:
        if subreaper_set:
            libc.prctl(36, previous_subreaper.value, 0, 0, 0)
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
