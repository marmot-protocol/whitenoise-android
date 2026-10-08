"""Run bounded Maestro slices against a generated, loopback-only native fixture."""

import argparse
import json
import math
import os
from pathlib import Path
import re
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'dev.ipf.whitenoise.android.maestrolab'
HOST = 'dev.ipf.whitenoise.android.maestro.MaestroRuntimeHostTest'
RUNNER = 'dev.ipf.whitenoise.android.maestro.MaestroFixtureRunner'
SUITES = ('navigation', 'settings', 'conversation', 'preferences', 'advanced', 'connectors', 'groups', 'creation', 'actions', 'polls', 'folders', 'nested', 'reader', 'composer', 'developer', 'support', 'ballots', 'profiles', 'chats', 'chatstate', 'consent', 'keys', 'search', 'permissions', 'reports', 'acquisition', 'speech', 'speech-validation', 'dictation', 'reactions', 'alert-dialogs', 'smart-folders', 'account-guards', 'settings-lifecycle', 'speech-persistence', 'profile-text', 'folder-rules', 'relay-validation', 'inbound-share')
MAX_CASES_PER_SHARD = 4
CASE_RESERVE_SECONDS = 660
UI_TIMEOUTS = {'polls-question-boundary': 240}
CAMPAIGN_SECONDS = MAX_CASES_PER_SHARD * CASE_RESERVE_SECONDS
CASES = json.loads((ROOT / 'config/maestro-runtime-cases.json').read_text())['cases']


def receipt(text, generation, flag):
    """A stale, partial or foreign fixture receipt cannot certify this case."""
    result = json.loads(text)
    if result.get('generation') != generation or result.get(flag) is not True:
        raise ValueError(f'Missing matching fixture {flag} receipt')
    return result


def ui_result(path, name):
    """Require one named successful UI assertion and a finite nonnegative duration."""
    cases = list(ET.parse(path).getroot().iter('testcase'))
    if len(cases) != 1 or cases[0].get('name') != name:
        raise ValueError('Missing, duplicate or unexpected Maestro case')
    case = cases[0]
    if case.get('status') != 'SUCCESS' or any(child.tag in ('failure', 'error', 'skipped') for child in case):
        raise ValueError('Maestro case did not pass')
    seconds = float(case.get('time', '0'))
    if not math.isfinite(seconds) or seconds < 0:
        raise ValueError('Invalid Maestro duration')
    return seconds


def command(arguments, **kwargs):
    """Run a bounded device command and propagate transport or nonzero-exit failures."""
    return subprocess.run(arguments, check=True, timeout=30, capture_output=True, text=True, **kwargs).stdout


def run_case(name, reports):
    """One generation owns setup, visible UI, optional peer proof, and mandatory cleanup."""
    generation = uuid.uuid4().hex
    directory = reports / name
    directory.mkdir()
    relative = f'files/maestro-{generation}'
    adb = ['adb', '-s', 'emulator-5554']
    def read(flag):
        """Read only the current generated fixture receipt from the isolated package."""
        return command(adb + ['shell', 'run-as', PACKAGE, 'cat', f'{relative}/{flag}.json'])
    if command(adb + ['shell', 'pm', 'clear', PACKAGE]).strip() != 'Success':
        raise ValueError('Isolated fixture data reset failed before instrumentation')
    # Restore Android's fresh-install permission state after a preceding grant or denial.
    permission = 'android.permission.POST_NOTIFICATIONS'
    command(adb + ['shell', 'pm', 'revoke', PACKAGE, permission])
    command(adb + ['shell', 'pm', 'clear-permission-flags', PACKAGE, permission, 'user-set', 'user-fixed'])
    if CASES[name]['postcondition'] == 'camera-denied':
        camera = 'android.permission.CAMERA'
        command(adb + ['shell', 'pm', 'revoke', PACKAGE, camera])
        command(adb + ['shell', 'pm', 'clear-permission-flags', PACKAGE, camera, 'user-set', 'user-fixed'])
    fixture = CASES[name].get('fixture', 'basic')
    record = {'case': name, 'generation': generation, 'passed': False, 'cleanup_safe': False, **CASES[name]}
    failure = None
    with (directory / 'instrumentation.txt').open('w') as log:
        process = subprocess.Popen(adb + ['shell', 'am', 'instrument', '-w', '-r',
                                         '-e', 'class', HOST, '-e', 'fixtureGeneration', generation,
                                         '-e', 'postcondition', CASES[name]['postcondition'],
                                         '-e', 'fixtureScenario', fixture,
                                         f'{PACKAGE}.test/{RUNNER}'], stdout=log, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 120
            while True:
                if process.poll() is not None:
                    raise ValueError('Fixture instrumentation ended before readiness')
                try:
                    ready = receipt(read('ready'), generation, 'ready')
                    if ready.get('accounts') != 3 or ready.get('fixture') != fixture or ready.get('uiObserver') != 'maestro':
                        raise ValueError('Fixture account inventory mismatch')
                    break
                except (subprocess.CalledProcessError, json.JSONDecodeError):
                    if time.monotonic() >= deadline:
                        raise TimeoutError('Fixture setup deadline exceeded')
                    time.sleep(1)
            (directory / 'ready.json').write_text(json.dumps(ready, indent=2) + '\n')
            result = run_ui(name, directory)
            if result.returncode != 0:
                raise ValueError(f'Maestro exit {result.returncode}')
            record['seconds'] = ui_result(directory / 'junit.xml', name)
        except Exception as error:
            failure = error
            record['failure'] = f'{type(error).__name__}: {error}'
        finally:
            # Preserve readiness diagnostics even when Maestro never started.
            diagnostics = [
                ('setup.json', adb + ['shell', 'run-as', PACKAGE, 'cat', f'{relative}/setup.json']),
                ('emulator-errors.txt', adb + ['logcat', '-d', '-v', 'brief', 'AndroidRuntime:E', 'TestRunner:V',
                                              'UiAutomation:V', 'UiAutomationConnection:V', 'AccessibilityManagerService:V',
                                              'ActivityManager:I', 'Maestro:V', '*:S']),
            ]
            for filename, arguments in diagnostics:
                try:
                    diagnostic = command(arguments)
                    (directory / filename).write_text(diagnostic[-256000:])
                except (subprocess.CalledProcessError, subprocess.TimeoutExpired, OSError) as error:
                    record.setdefault('diagnostic_failures', []).append(f'{filename}: {type(error).__name__}')
            # Stop only this generated host; do not reset an installed user package.
            try:
                if process.poll() is None:
                    command(adb + ['shell', 'run-as', PACKAGE, 'touch', f'{relative}/finish'])
                    process.wait(timeout=60)
                closed = receipt(read('closed'), generation, 'closed')
                (directory / 'closed.json').write_text(json.dumps(closed, indent=2) + '\n')
                record['cleanup_safe'] = True
                verified = receipt(read('verified'), generation, 'verified')
                (directory / 'verified.json').write_text(json.dumps(verified, indent=2) + '\n')
                if (CASES[name]['postcondition'].startswith('speech-rate-')
                        and verified.get('speechRateVerified') is not True):
                    raise ValueError('Persisted speech rate was not verified')
                if (CASES[name]['postcondition'].startswith('public-profile-')
                        and verified.get('publicProfileVerified') is not True):
                    raise ValueError('Authoritative public profiles were not verified')
                if (CASES[name]['postcondition'].startswith('smart-rule-')
                        and verified.get('smartFolderRuleVerified') is not True):
                    raise ValueError('Persisted smart-folder rules were not verified')
                if (CASES[name]['postcondition'] == 'relay-lists-unchanged'
                        and verified.get('relayListsVerified') is not True):
                    raise ValueError('Unchanged native relay lists were not verified')
                if (CASES[name]['postcondition'].startswith('share-request-')
                        and verified.get('shareImportVerified') is not True):
                    raise ValueError('Actual inbound share recovery and no-send proof was not verified')
                if process.returncode != 0 or 'OK (1 test)' not in (directory / 'instrumentation.txt').read_text():
                    raise ValueError('Fixture instrumentation or cleanup failed')
                if failure is None:
                    record['passed'] = True
                command(adb + ['shell', 'run-as', PACKAGE, 'rm', '-r', relative])
            except Exception as error:
                record['cleanup_failure'] = f'{type(error).__name__}: {error}'
                record['passed'] = False
            finally:
                if process.poll() is None:
                    record['passed'] = False
                    record['cleanup_safe'] = False
                    try:
                        command(adb + ['shell', 'am', 'force-stop', PACKAGE])
                    except (subprocess.CalledProcessError, subprocess.TimeoutExpired, OSError) as error:
                        record['stop_failure'] = f'{type(error).__name__}: {error}'
                    finally:
                        process.terminate()
                        try:
                            process.wait(timeout=10)
                        except subprocess.TimeoutExpired:
                            process.kill()
                            process.wait(timeout=10)
                (directory / 'result.json').write_text(json.dumps(record, indent=2) + '\n')
    return record


def run_ui(name, directory):
    """Retain bounded CLI stdout and stderr, including parse failures that produce no JUnit."""
    navigation = os.environ.get('MAESTRO_NAVIGATION_MODE', 'button')
    if navigation not in ('button', 'gesture'):
        raise ValueError('Qualified navigation mode required')
    with (directory / 'maestro-output.txt').open('w') as log:
        return subprocess.run(['maestro', '--device', 'emulator-5554', 'test', '--format', 'JUNIT',
                               '-e', f'MAESTRO_NAVIGATION_MODE={navigation}',
                               '--output', str(directory / 'junit.xml'), '--debug-output', str(directory / 'debug'),
                               '--test-output-dir', str(directory / 'screenshots'),
                               str(ROOT / '.maestro/runtime' / f'{name}.yaml')],
                              timeout=UI_TIMEOUTS.get(name, 120), check=False,
                              stdout=log, stderr=subprocess.STDOUT)


def case_selection(suite, partition):
    """Partition a logical suite into disjoint slices with a maximum of four cases."""
    selected = [name for name, case in CASES.items() if case['suite'] == suite]
    partitions = (len(selected) + MAX_CASES_PER_SHARD - 1) // MAX_CASES_PER_SHARD
    if suite not in SUITES or not 1 <= partition <= partitions:
        raise ValueError('Unknown runtime suite partition')
    start = (partition - 1) * MAX_CASES_PER_SHARD
    return selected[start:start + MAX_CASES_PER_SHARD]


def main():
    """Run an admitted emulator slice, persist every outcome and fail incomplete campaigns."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--suite', choices=SUITES, required=True)
    parser.add_argument('--reports', type=Path, required=True)
    parser.add_argument('--partition', type=int, default=1)
    args = parser.parse_args()
    if os.environ.get('GITHUB_ACTIONS') != 'true':
        parser.error('Disposable GitHub Actions emulator required')
    if command(['adb', '-s', 'emulator-5554', 'shell', 'getprop', 'ro.kernel.qemu']).strip() != '1':
        parser.error('Disposable emulator required')
    try:
        selected = case_selection(args.suite, args.partition)
    except ValueError as error:
        parser.error(str(error))
    if not 1 <= len(selected) <= MAX_CASES_PER_SHARD or any(not re.fullmatch(r'[a-z]+(?:-[a-z]+)+', name) for name in selected):
        parser.error('Suite case budget or allowlist invalid')
    args.reports.mkdir(parents=True, exist_ok=False)
    results = []
    deadline = time.monotonic() + CAMPAIGN_SECONDS
    stop_reason = 'Prior fixture teardown did not certify safe continuation'
    try:
        for name in selected:
            if time.monotonic() + CASE_RESERVE_SECONDS > deadline:
                stop_reason = 'Campaign budget cannot admit another complete setup, UI and teardown'
                break
            result = run_case(name, args.reports)
            results.append(result)
            if not result['cleanup_safe']:
                break
    finally:
        completed = {result['case'] for result in results}
        results.extend({'case': name, 'passed': False, 'not_run': True,
                        'failure': stop_reason}
                       for name in selected if name not in completed)
        summary = {'suite': args.suite, 'partition': args.partition, 'expected': selected, 'results': results,
                   'evidence_complete': len(results) == len(selected) and all(case['passed'] for case in results)}
        (args.reports / 'results.json').write_text(json.dumps(summary, indent=2) + '\n')
    if not summary['evidence_complete']:
        parser.exit(1, 'Runtime campaign contains failed or unexecuted cases\n')


if __name__ == '__main__':
    main()
