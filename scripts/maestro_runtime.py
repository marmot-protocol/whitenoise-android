"""Run bounded Maestro slices against a generated, loopback-only native fixture."""

import argparse
import json
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
SUITES = ('navigation', 'settings', 'conversation', 'preferences')
CASES = json.loads((ROOT / 'config/maestro-runtime-cases.json').read_text())['cases']


def receipt(text, generation, flag):
    """A stale, partial or foreign fixture receipt cannot certify this case."""
    result = json.loads(text)
    if result.get('generation') != generation or result.get(flag) is not True:
        raise ValueError(f'Missing matching fixture {flag} receipt')
    return result


def ui_result(path, name):
    cases = list(ET.parse(path).getroot().iter('testcase'))
    if len(cases) != 1 or cases[0].get('name') != name:
        raise ValueError('Missing, duplicate or unexpected Maestro case')
    case = cases[0]
    if case.get('status') != 'SUCCESS' or any(child.tag in ('failure', 'error', 'skipped') for child in case):
        raise ValueError('Maestro case did not pass')
    return float(case.get('time', '0'))


def command(arguments, **kwargs):
    return subprocess.run(arguments, check=True, timeout=30, capture_output=True, text=True, **kwargs).stdout


def run_case(name, reports):
    """One generation owns setup, visible UI, optional peer proof, and mandatory cleanup."""
    generation = uuid.uuid4().hex
    directory = reports / name
    directory.mkdir()
    relative = f'files/maestro-{generation}'
    adb = ['adb', '-s', 'emulator-5554']
    def read(flag):
        return command(adb + ['shell', 'run-as', PACKAGE, 'cat', f'{relative}/{flag}.json'])
    record = {'case': name, 'generation': generation, 'passed': False, **CASES[name]}
    with (directory / 'instrumentation.txt').open('w') as log:
        process = subprocess.Popen(adb + ['shell', 'am', 'instrument', '-w', '-r',
                                         '-e', 'class', HOST, '-e', 'fixtureGeneration', generation,
                                         '-e', 'postcondition', CASES[name]['postcondition'],
                                         f'{PACKAGE}.test/{RUNNER}'], stdout=log, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 120
            while True:
                if process.poll() is not None:
                    raise ValueError('Fixture instrumentation ended before readiness')
                try:
                    ready = receipt(read('ready'), generation, 'ready')
                    if ready.get('accounts') != 3:
                        raise ValueError('Fixture account inventory mismatch')
                    break
                except (subprocess.CalledProcessError, json.JSONDecodeError):
                    if time.monotonic() >= deadline:
                        raise TimeoutError('Fixture setup deadline exceeded')
                    time.sleep(1)
            (directory / 'ready.json').write_text(json.dumps(ready, indent=2) + '\n')
            result = subprocess.run(['maestro', '--device', 'emulator-5554', 'test', '--format', 'JUNIT',
                                     '--output', str(directory / 'junit.xml'), '--debug-output', str(directory / 'debug'),
                                     '--test-output-dir', str(directory / 'screenshots'),
                                     str(ROOT / '.maestro/runtime' / f'{name}.yaml')], timeout=120, check=False)
            if result.returncode != 0:
                raise ValueError(f'Maestro exit {result.returncode}')
            record['seconds'] = ui_result(directory / 'junit.xml', name)
        finally:
            # Stop only this generated host; do not reset an installed user package.
            try:
                command(adb + ['shell', 'run-as', PACKAGE, 'touch', f'{relative}/finish'])
                process.wait(timeout=60)
            finally:
                if process.poll() is None:
                    command(adb + ['shell', 'am', 'force-stop', PACKAGE])
                    process.terminate()
                    process.wait(timeout=10)
        # am instrument may exit zero despite a test failure. Both receipts are mandatory.
        verified = receipt(read('verified'), generation, 'verified')
        closed = receipt(read('closed'), generation, 'closed')
        if process.returncode != 0 or 'OK (1 test)' not in (directory / 'instrumentation.txt').read_text():
            raise ValueError('Fixture instrumentation or cleanup failed')
        (directory / 'verified.json').write_text(json.dumps(verified, indent=2) + '\n')
        (directory / 'closed.json').write_text(json.dumps(closed, indent=2) + '\n')
        command(adb + ['shell', 'run-as', PACKAGE, 'rm', '-r', relative])
        record['passed'] = True
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--suite', choices=SUITES, required=True)
    parser.add_argument('--reports', type=Path, required=True)
    args = parser.parse_args()
    if os.environ.get('GITHUB_ACTIONS') != 'true':
        parser.error('Disposable GitHub Actions emulator required')
    if command(['adb', '-s', 'emulator-5554', 'shell', 'getprop', 'ro.kernel.qemu']).strip() != '1':
        parser.error('Disposable emulator required')
    selected = [name for name, case in CASES.items() if case['suite'] == args.suite]
    if not 1 <= len(selected) <= 8 or any(not re.fullmatch(r'[a-z]+(?:-[a-z]+)+', name) for name in selected):
        parser.error('Suite case budget or allowlist invalid')
    args.reports.mkdir(parents=True, exist_ok=False)
    results = []
    try:
        for name in selected:
            results.append(run_case(name, args.reports))
    finally:
        summary = {'suite': args.suite, 'expected': selected, 'results': results,
                   'evidence_complete': len(results) == len(selected) and all(case['passed'] for case in results)}
        (args.reports / 'results.json').write_text(json.dumps(summary, indent=2) + '\n')


if __name__ == '__main__':
    main()
