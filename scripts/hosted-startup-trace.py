#!/usr/bin/env python3
"""Finite hosted API30 capture. Raw trace never grants performance/security PASS."""
import fcntl
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import uuid

OUT = Path('app/build/reports/media-startup-trace')
STATE = OUT / 'state.json'
MAX_BYTES = 128 * 1024 * 1024
CONFIG = '''buffers { size_kb: 65536 fill_policy: DISCARD }
duration_ms: 120000
write_into_file: true
file_write_period_ms: 1000
max_file_size_bytes: 134217728
data_sources { config { name: "linux.ftrace" ftrace_config {
 atrace_categories: "am"
 atrace_categories: "dalvik"
 atrace_categories: "binder_driver"
 ftrace_events: "sched/sched_switch"
 ftrace_events: "sched/sched_waking"
 ftrace_events: "sched/sched_process_fork"
 ftrace_events: "sched/sched_process_exit"
 ftrace_events: "task/task_newtask"
 ftrace_events: "task/task_rename"
} } }
data_sources { config { name: "linux.process_stats" process_stats_config {
 scan_all_processes_on_start: true
} } }
'''
COMMAND = ['./gradlew', ':app:connectedDevZapstoreBenchmarkReleaseAndroidTest',
 '--init-script', 'scripts/hosted-startup-trace.init.gradle',
 '-Pandroid.testInstrumentationRunnerArguments.class=dev.ipf.whitenoise.android.media.sandbox.MediaWorkerHealthyBaselineDeviceTest#characterizeHealthyDirectStillTransportWithFixedSyntheticCorpus',
 '-Pandroid.testInstrumentationRunnerArguments.timeout_msec=120000',
 '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true',
 '--no-daemon', '--stacktrace']


def save(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


def command(argv, label, timeout=10, input_data=None):
    started = time.monotonic()
    try:
        r = subprocess.run(argv, capture_output=True, timeout=timeout, input=input_data)
        row = dict(code=r.returncode, stdout=r.stdout, stderr=r.stderr, timed_out=False)
    except subprocess.TimeoutExpired as e:
        row = dict(code=None, stdout=e.stdout or b'', stderr=e.stderr or b'', timed_out=True)
    for kind in ('stdout', 'stderr'):
        data = row.pop(kind)
        retained = data if len(data) <= 1024 * 1024 else data[:512 * 1024] + b'\n[bounded capture omitted middle]\n' + data[-512 * 1024:]
        (OUT / (label + '.' + kind)).write_bytes(retained)
        row[kind + '_sha256'] = hashlib.sha256(data).hexdigest()
        row[kind + '_bytes'] = len(data)
        row[kind + '_text'] = data[:8192].decode('utf-8', errors='replace')
    row['elapsed_seconds'] = time.monotonic() - started
    save(OUT / (label + '.json'), row)
    return row


def adb(args, label, timeout=10, input_data=None):
    return command(['adb', '-s', 'emulator-5554', *args], label, timeout, input_data)


def require_ok(row, reason):
    if row['code'] != 0 or row['timed_out']:
        raise RuntimeError(reason)
    return row['stdout_text'].strip()


def full_bounded_stdout(row, label):
    require_ok(row, 'required capture failed:' + label)
    if row['stdout_bytes'] > 1024 * 1024:
        raise RuntimeError('required full capture exceeds bound:' + label)
    data = (OUT / (label + '.stdout')).read_bytes()
    if len(data) != row['stdout_bytes'] or hashlib.sha256(data).hexdigest() != row['stdout_sha256']:
        raise RuntimeError('required full capture changed:' + label)
    try:
        return data.decode('utf-8')
    except UnicodeDecodeError as error:
        raise RuntimeError('required capture is not UTF8:' + label) from error


AV_CASE = 'dev.ipf.whitenoise.android.media.sandbox.MediaAvPerformanceDeviceTest#measureProductionCostsOnUnchangedCorpus'
LEGACY_CASE = 'dev.ipf.whitenoise.android.media.sandbox.MediaWorkerHealthyBaselineDeviceTest#characterizeHealthyDirectStillTransportWithFixedSyntheticCorpus'


def selection(mode='healthy', api='30'):
    if mode == 'healthy' and api == '30':
        return CONFIG, list(COMMAND), LEGACY_CASE
    if mode != 'av' or api not in ('30', '36'):
        raise ValueError('fixed trace mode/API required')
    config = CONFIG.replace('duration_ms: 120000', 'duration_ms: 600000').replace(
        'scan_all_processes_on_start: true', 'scan_all_processes_on_start: true\n proc_stats_poll_ms: 100')
    command = [value.replace('BenchmarkRelease', 'Debug').replace(LEGACY_CASE, AV_CASE).replace(
        'timeout_msec=120000', 'timeout_msec=600000') for value in COMMAND]
    command.insert(1, '-PmediaAvStartupTrace=true')
    return config, command, AV_CASE


def start():
    state = json.loads(STATE.read_text())
    config, _, _ = selection(state.get('trace_mode', 'healthy'), state.get('android_api', '30'))
    marker = OUT / 'start.intent'
    with marker.open('x') as f:
        f.write(state['key'])
    try:
        result = adb(['shell', 'perfetto', '--txt', '-c', '-', '-o', state['remote'],
                      '--detach=' + state['key']], 'start', input_data=config.encode('utf-8'))
        require_ok(result, 'trace start failed or uncertain; no retry')
    except Exception as error:
        save(OUT / 'start.failed', {'key': state['key'], 'error': str(error), 'error_class': type(error).__name__})
        raise
    (OUT / 'start.ack').write_text(state['key'])


def finish(state):
    issues = []
    state.update(capture_qualified=False, cleanup='HELD',
                 loss_gate='UNVERIFIED: offline trace stats/overrun and full interval review required',
                 phase_mapping_gate='UNVERIFIED: exact worker lifetime/clock association required')
    try:
        if not state.get('remote_admitted'):
            state['cleanup'] = 'NOT_OWNED'
            issues.append('remote output never admitted; no cleanup authorized')
            return issues
        if (OUT / 'start.failed').exists():
            state['start_failure'] = json.loads((OUT / 'start.failed').read_text())
            issues.append('trace start failed or uncertain; retain evidence, do not retry stop or cleanup')
            return issues
        if (OUT / 'start.intent').exists():
            if not (OUT / 'start.ack').exists():
                issues.append('trace start acknowledgement absent; retain evidence, do not retry')
                return issues
            # Flush gets its own ten-second bound; pull and cleanup cannot consume it.
            stopped = adb(['shell', 'perfetto', '--attach=' + state['key'], '--stop'], 'stop', 10)
            if stopped['code'] != 0 or stopped['timed_out']:
                issues.append('exact session stop/flush unverified; retain remote evidence, do not retry')
                return issues
            try:
                size = adb(['shell', 'stat', '-c', '%s', state['remote']], 'trace-size', 10)
                count = int(require_ok(size, 'trace size unavailable'))
                if not 0 < count < MAX_BYTES:
                    raise ValueError('empty/at-cap/oversized trace')
                pull_seconds = min(90, 10 + count / (2 * 1024 * 1024))
                state['pull_timeout_seconds'] = pull_seconds
                pulled = adb(['pull', state['remote'], str(OUT / 'startup.perfetto-trace')], 'pull', pull_seconds)
                require_ok(pulled, 'trace pull failed')
                local = OUT / 'startup.perfetto-trace'
                if local.stat().st_size != count:
                    raise ValueError('trace byte count mismatch')
                state['trace_sha256'] = hashlib.sha256(local.read_bytes()).hexdigest()
            except (RuntimeError, ValueError, OSError) as error:
                issues.append(str(error))
                return issues
        else:
            issues.append('connected-task capture never started')
        # Only this unique output. No remote config, directory or broad cleanup.
        removed = adb(['shell', 'rm', '-f', state['remote']], 'remove', 10)
        if removed['code'] == 0 and not removed['timed_out']:
            state['cleanup'] = 'CONFIRMED'
        else:
            issues.append('task-owned remote cleanup unverified')
    except Exception as error:
        state['finish_error'] = {'error_class': type(error).__name__, 'error': str(error)}
        issues.append('capture finalization failed; retain evidence')
    finally:
        state['issues'] = issues
        save(OUT / 'capture-receipt.json', state)
    return issues


def run(mode='healthy', api='30'):
    config, selected_command, required_case = selection(mode, api)
    if os.environ.get('GITHUB_EVENT_NAME') != 'workflow_dispatch':
        raise RuntimeError('hosted explicit dispatch required')
    OUT.mkdir(parents=True, exist_ok=False)
    lock = (OUT / 'capture.lock').open('x')
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    key = 'wnstartup' + uuid.uuid4().hex
    state = dict(schema=1, key=key, remote='/data/misc/perfetto-traces/' + key + '.perfetto-trace',
                 run_id=os.environ.get('GITHUB_RUN_ID'), run_attempt=os.environ.get('GITHUB_RUN_ATTEMPT'),
                 job_key=os.environ.get('GITHUB_JOB'), source=os.environ.get('GITHUB_SHA'),
                 effect='hosted-only raw diagnostic capture; no qualification',
                 trace_mode=mode, android_api=api, required_case=required_case,
                 memory_scope='sampled RSS only; no PSS or exact peak' if mode == 'av' else 'legacy trace')
    save(STATE, state)
    test_code = None
    try:
        actual_source = require_ok(command(['git', 'rev-parse', 'HEAD'], 'source'), 'checkout unavailable')
        if actual_source != state['source'] or len(actual_source) != 40:
            raise RuntimeError('checkout/source environment mismatch')
        require_ok(command(['git', 'rev-parse', 'HEAD^{tree}'], 'source-tree'), 'source tree unavailable')
        for prop, expected in [('ro.build.version.sdk', api), ('ro.product.cpu.abi', 'x86_64')]:
            actual = require_ok(adb(['shell', 'getprop', prop], prop), 'runtime unavailable')
            if actual != expected:
                raise RuntimeError('unsupported runtime:' + prop)
        for prop in ('ro.build.fingerprint', 'ro.build.version.incremental'):
            if not require_ok(adb(['shell', 'getprop', prop], prop), 'runtime metadata absent'):
                raise RuntimeError('empty runtime metadata')
        require_ok(adb(['shell', 'cat', '/proc/sys/kernel/random/boot_id'], 'boot-id'), 'boot evidence absent')
        require_ok(adb(['shell', 'cat', '/proc/uptime'], 'boot-clock'), 'clock evidence absent')
        events = full_bounded_stdout(adb(['shell', 'cat', '/sys/kernel/tracing/available_events'], 'available-events'), 'available-events')
        for event in ('sched:sched_switch', 'sched:sched_waking', 'sched:sched_process_fork', 'sched:sched_process_exit', 'task:task_newtask', 'task:task_rename'):
            if event not in events.split():
                raise RuntimeError('required kernel event unavailable:' + event)
        require_ok(adb(['shell', 'perfetto', '--query'], 'perfetto-query'), 'Perfetto unavailable')
        categories = require_ok(adb(['shell', 'atrace', '--list_categories'], 'categories'), 'atrace unavailable')
        names = {line.split()[0] for line in categories.splitlines() if line.split()}
        if not {'am', 'dalvik', 'binder_driver'} <= names:
            raise RuntimeError('required tracing category unavailable')
        require_ok(adb(['shell', 'test', '!', '-e', state['remote']], 'admit-output'), 'unique remote output already exists')
        state['remote_admitted'] = True
        save(STATE, state)
        (OUT / 'config.pbtxt').write_text(config)
        # Compilation dependencies finish before the exact task doFirst starts capture.
        r = command(['timeout', '--signal=INT', '--kill-after=30s', '25m', *selected_command], 'instrumentation', 1535)
        test_code = r['code'] if r['code'] is not None else 124
        if test_code == 0:
            required = OUT / 'required-cases.txt'
            required.write_text(required_case + '\n')
            checked = command(['python3', 'scripts/check_instrumented_required_cases.py',
                               'app/build/outputs/androidTest-results/connected', '--required', str(required)], 'required-case', 10)
            test_code = checked['code'] if checked['code'] is not None else 124
    except Exception as error:
        state['setup_error'] = str(error)
        state['setup_error_class'] = type(error).__name__
    finally:
        state['test_exit_code'] = test_code
        original_error = sys.exc_info()[1]
        if original_error is not None:
            state['run_exception'] = {'error_class': type(original_error).__name__, 'error': str(original_error)}
        try:
            # Keep runner discovery diagnostics inside this hosted synthetic lane.
            adb(['logcat', '-d', '-v', 'threadtime', 'AndroidJUnitRunner:I',
                 'TestRunner:I', 'AndroidRuntime:E', '*:S'], 'runner-logcat', 10)
        except Exception as error:
            # Diagnostic retention must never prevent trace cleanup.
            state['runner_diagnostic_error'] = type(error).__name__
        try:
            issues = finish(state)
        except Exception as error:
            # A receipt write failure must not mask an existing test failure or cancellation.
            issues = ['capture receipt could not be saved: ' + type(error).__name__]
            print(issues[0], file=sys.stderr)
        finally:
            lock.close()
    # Preserve a failing test. A trace problem cannot turn it green.
    return test_code if test_code else (2 if issues or test_code is None else 0)


if __name__ == '__main__':
    if sys.argv[1:] == ['start']:
        start()
    elif sys.argv[1:] == ['run']:
        sys.exit(run())
    elif len(sys.argv) == 3 and sys.argv[1] == 'run-av' and sys.argv[2] in ('30', '36'):
        sys.exit(run('av', sys.argv[2]))
    else:
        raise SystemExit('Expected run or start; no arbitrary command')
