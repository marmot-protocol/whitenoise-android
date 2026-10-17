"""Run offline flows once in bounded fresh driver sessions, preserving actual JUnit."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

try:
    from scripts.maestro_suite import report
except ModuleNotFoundError:
    from maestro_suite import report


MAX_BATCH = 1
UI_SECONDS = 570


def prepare(reports):
    """Keep manifest order and exact flow bytes; reset before each fresh driver session."""
    manifest = json.loads((reports / 'suite-manifest.json').read_text())
    root = reports / 'batches'
    if root.exists():
        raise ValueError('Batch destination must be fresh')
    for case in manifest['cases']:
        if not re.fullmatch(r'[a-z][a-z0-9-]{0,99}', case['name']):
            raise ValueError('Unsafe case identity')
    groups = [[case] for case in manifest['cases']]
    batches = []
    for index, cases in enumerate(groups, 1):
        folder = root / f'{index:03}'
        folder.mkdir(parents=True)
        hashes = {}
        for case in cases:
            filename = 'zz-negative-control.yaml' if case['negative'] else case['name'] + '.yaml'
            source = reports / 'suite' / filename
            content = source.read_bytes()
            header = content.decode().splitlines()[:2]
            if header != ['appId: dev.ipf.whitenoise.android.dev', 'name: ' + case['name']]:
                raise ValueError('Prepared flow identity differs from selection')
            shutil.copyfile(source, folder / filename)
            hashes[case['name']] = hashlib.sha256(content).hexdigest()
        config = {'flows': ['*.yaml'], 'executionOrder': {
            'continueOnFailure': False, 'flowsOrder': [case['name'] for case in cases]}}
        order = ''.join('    - ' + json.dumps(name) + '\n' for name in config['executionOrder']['flowsOrder'])
        (folder / 'config.yaml').write_text(
            'flows:\n  - "*.yaml"\nexecutionOrder:\n  continueOnFailure: false\n  flowsOrder:\n' + order)
        batches.append({'number': index, 'names': config['executionOrder']['flowsOrder'], 'flow_sha256': hashes})
    ledger = {'source_sha': os.environ.get('GITHUB_SHA'), 'maximum_batch_size': MAX_BATCH,
              'ui_budget_seconds': UI_SECONDS, 'batches': batches, 'evidence_complete': False}
    (reports / 'batch-manifest.json').write_text(json.dumps(ledger, indent=2) + '\n')
    return batches


def aggregate(reports, batches):
    """Copy only real test records; absent reports remain absent from the aggregate."""
    document = ET.Element('testsuites')
    for batch in batches:
        path = reports / 'batches' / f"{batch['number']:03}" / 'junit.xml'
        if not path.exists():
            continue
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        suite = ET.SubElement(document, 'testsuite', name=f"batch-{batch['number']:03}")
        for case in root.iter('testcase'):
            suite.append(case)
    ET.ElementTree(document).write(reports / 'junit.xml', encoding='utf-8', xml_declaration=True)


def complete_batch(path, names):
    """A successful CLI exit still requires every selected real JUnit record exactly once."""
    try:
        records = list(ET.parse(path).getroot().iter('testcase'))
        if sorted(case.get('name') or '' for case in records) != sorted(names):
            return False
        return all(case.get('status') == 'SUCCESS' and not any(
            child.tag in ('error', 'failure', 'skipped') for child in case) for case in records)
    except (OSError, ET.ParseError):
        return False


def execute(reports):
    """One global deadline, serial CLI processes and checked driver teardown; never replay a flow."""
    if os.environ.get('GITHUB_ACTIONS') != 'true':
        raise ValueError('Offline batches require the disposable GitHub Actions emulator')
    started = time.monotonic()
    batches = prepare(reports)
    # A hard kill may bypass finalization. Preserve an honest incomplete ledger before any UI process.
    try:
        report(reports, None)
    except ValueError:
        pass
    receipts, result = [], 0
    try:
        for batch in batches:
            folder = reports / 'batches' / f"{batch['number']:03}"
            remaining = UI_SECONDS - (time.monotonic() - started)
            if remaining <= 0:
                result = 124
                break
            row = {'number': batch['number'], 'names': batch['names'],
                   'reset_verified': False, 'cleanup_verified': False}
            receipts.append(row)
            try:
                reset = subprocess.run(['adb', '-s', 'emulator-5554', 'shell', 'pm', 'clear',
                                        'dev.ipf.whitenoise.android.dev'],
                                       timeout=min(30, remaining), capture_output=True, text=True, check=False)
                row['reset_verified'] = reset.returncode == 0 and reset.stdout.strip() == 'Success'
            except subprocess.TimeoutExpired:
                result = 124
                break
            if not row['reset_verified']:
                result = 2
                break
            remaining = UI_SECONDS - (time.monotonic() - started)
            if remaining <= 0:
                result = 124
                break
            arguments = ['maestro', '--device', 'emulator-5554', '--driver-host-port', '7001',
                         'test', '--config', str(folder / 'config.yaml'), '--format', 'JUNIT',
                         '--output', str(folder / 'junit.xml'), '--test-suite-name', 'White Noise onboarding pilot',
                         '--debug-output', str(reports / 'debug' / f"batch-{batch['number']:03}"),
                         '--test-output-dir', str(reports / 'screenshots' / f"batch-{batch['number']:03}"),
                         str(folder)]
            try:
                result = subprocess.run(arguments, timeout=remaining, check=False).returncode
            except subprocess.TimeoutExpired:
                result = 124
            row['maestro_exit'] = result
            # Unexpected failure stops the campaign; a selected deliberate control is already last.
            if result != 0:
                break
            if not complete_batch(folder / 'junit.xml', batch['names']):
                result = 2
                break
            remaining = UI_SECONDS - (time.monotonic() - started)
            if remaining <= 0:
                result = 124
                break
            try:
                check = subprocess.run(['adb', '-s', 'emulator-5554', 'shell', 'pm', 'list',
                                        'packages', '--user', '0', 'dev.mobile.maestro'],
                                       timeout=min(10, remaining), capture_output=True, text=True, check=False)
                row['cleanup_verified'] = check.returncode == 0 and not check.stdout.strip()
            except subprocess.TimeoutExpired:
                row['cleanup_verified'] = False
            if not row['cleanup_verified']:
                result = 2
                break
    except SystemExit:
        # GNU timeout signals the whole group. Finalize genuine completed batches before its kill grace.
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        result = 124
        if receipts:
            receipts[-1]['maestro_exit'] = result
    finally:
        aggregate(reports, batches)
        (reports / 'batch-results.json').write_text(json.dumps(
            {'source_sha': os.environ.get('GITHUB_SHA'), 'maestro_exit': result, 'batches': receipts}, indent=2) + '\n')
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reports', required=True, type=Path)
    args = parser.parse_args()
    signal.signal(signal.SIGTERM, lambda _signal, _frame: sys.exit(124))
    try:
        raise SystemExit(execute(args.reports))
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        print(str(error))
        raise SystemExit(2) from error
