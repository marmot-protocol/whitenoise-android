"""Prepare bounded offline journeys and reconcile actual Maestro JUnit evidence."""

import argparse
from collections import Counter
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
CASES = {
    'onboarding': ('onboarding.yaml', ['ONB-001', 'ONB-004']),
    'signin-invalid': ('signin-invalid.yaml', ['ONB-005']),
    'signin-public': ('signin-public.yaml', ['ONB-005']),
    'signin-back': ('signin-back.yaml', ['ONB-004', 'ONB-010']),
    'signup-cancel': ('signup-cancel.yaml', ['ONB-002']),
    'signup-offline-retry': ('signup-offline-retry.yaml', ['ONB-002']),
}
OFFLINE_BASELINE = tuple(CASES)
CASES.update({
    'signin-clear': ('signin-clear.yaml', ['ONB-004', 'ONB-005']),
    'signin-edit-error': ('signin-edit-error.yaml', ['ONB-005']),
    'signin-whitespace': ('signin-whitespace.yaml', ['ONB-005']),
    'signin-qr-denied': ('signin-qr-denied.yaml', ['ONB-004', 'ONB-006']),
    'signin-warm-resume': ('signin-warm-resume.yaml', ['ONB-004', 'ONB-005']),
    'signup-system-back': ('signup-system-back.yaml', ['ONB-002']),
    'signup-warm-resume': ('signup-warm-resume.yaml', ['ONB-002']),
    'signup-edit-retry': ('signup-edit-retry.yaml', ['ONB-002']),
    'signup-rotation': ('signup-rotation.yaml', ['ONB-001', 'ONB-002']),
    'signup-empty-offline': ('signup-empty-offline.yaml', ['ONB-002']),
})
SUITES = {
    'onboarding': ('onboarding',),
    'offline': OFFLINE_BASELINE,
    'offline-signin': ('onboarding', 'signin-invalid', 'signin-public', 'signin-back', 'signin-clear',
                       'signin-edit-error', 'signin-whitespace', 'signin-qr-denied', 'signin-warm-resume'),
    'offline-signup': ('signup-cancel', 'signup-offline-retry', 'signup-system-back', 'signup-warm-resume',
                       'signup-edit-retry', 'signup-rotation', 'signup-empty-offline'),
    'offline-edge': ('signup-suggest-name', 'signup-photo-cancel', 'signup-picker-cancel',
                     'signin-rotation', 'signin-ime-back', 'signin-long-invalid'),
}
CASES.update({
    'signup-suggest-name': ('signup-suggest-name.yaml', ['ONB-025']),
    'signup-photo-cancel': ('signup-photo-cancel.yaml', ['ONB-002']),
    'signup-picker-cancel': ('signup-picker-cancel.yaml', ['ONB-002']),
    'signin-rotation': ('signin-rotation.yaml', ['ONB-004', 'NAV-009']),
    'signin-ime-back': ('signin-ime-back.yaml', ['ONB-004']),
    'signin-long-invalid': ('signin-long-invalid.yaml', ['ONB-005']),
})
NEGATIVE_ASSERTION = 'MAESTRO_NEGATIVE_CONTROL_IMPOSSIBLE_3141'


def selection(suite, repetitions, negative):
    """Fail before emulator setup for unknown suites or an excessive case budget."""
    if suite not in SUITES or not re.fullmatch(r'([1-9]|1[0-9]|20)', repetitions):
        raise ValueError('Select an allowlisted suite and 1 through 20 repetitions')
    if negative not in ('true', 'false'):
        raise ValueError('Negative control must be true or false')
    if suite in ('offline-signin', 'offline-signup', 'offline-edge') and repetitions != '1':
        raise ValueError('Focused expanded suites permit one repetition within the ten-minute CLI budget')
    count = len(SUITES[suite]) * int(repetitions)
    if count > 20:
        raise ValueError('At most 20 positive journeys; offline permits 1 through 3 repetitions')
    return SUITES[suite], int(repetitions), negative == 'true'


def prepare(suite, repetitions, negative, destination, root=ROOT):
    """Copy only selected allowlisted flows, with unique case and screenshot names."""
    selected, repeats, control = selection(suite, repetitions, negative)
    reports = Path(destination)
    flows = reports / 'suite'
    if flows.exists():
        raise ValueError('Suite directory already exists; use a fresh result directory')
    # Read every selected flow before writing; missing source cannot produce a partial suite.
    sources = {key: (root / '.maestro' / CASES[key][0]).read_text() for key in selected}
    flows.mkdir(parents=True)
    cases = []
    for iteration in range(1, repeats + 1):
        for key in selected:
            name = f'{key}-{iteration:02}'
            text = re.sub(r'^name: .+$', f'name: {name}', sources[key], count=1, flags=re.M)
            text = re.sub(r'^- takeScreenshot: (.+)$', rf'- takeScreenshot: \1-{iteration:02}', text, flags=re.M)
            (flows / f'{name}.yaml').write_text(text)
            cases.append({'name': name, 'case': key, 'manual_ids': CASES[key][1], 'negative': False})
    if control:
        # A fresh, real navigation journey must complete before this deliberate failure.
        text = (root / '.maestro/onboarding.yaml').read_text()
        text = re.sub(r'^name: .+$', 'name: negative-control', text, count=1, flags=re.M)
        text = text.replace('welcome-returned', 'negative-control-welcome')
        (flows / 'zz-negative-control.yaml').write_text(text + f'- assertVisible: "{NEGATIVE_ASSERTION}"\n')
        cases.append({'name': 'negative-control', 'case': 'onboarding', 'manual_ids': [], 'negative': True})
    manifest = {'schema': 1, 'suite': suite, 'repetitions': repeats, 'positive_count': len(cases) - int(control),
                'negative_control': control, 'cases': cases, 'coverage': 'partial UI assertions only'}
    (reports / 'suite-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    return manifest


def report(destination, maestro_exit):
    """Missing, skipped, duplicated or unexpected cases cannot be reported as passing."""
    reports = Path(destination)
    expected = json.loads((reports / 'suite-manifest.json').read_text())
    results = list(ET.parse(reports / 'junit.xml').getroot().iter('testcase'))
    names = [case.get('name') for case in results]
    if Counter(names) != Counter(case['name'] for case in expected['cases']):
        raise ValueError('JUnit case discovery differs from the selected suite')
    controls = {case['name'] for case in expected['cases'] if case['negative']}
    cases = []
    for case in results:
        problems = [item for item in case if item.tag in ('failure', 'error', 'skipped')]
        control = case.get('name') in controls
        deliberate = control and len(problems) == 1 and problems[0].tag == 'failure' and NEGATIVE_ASSERTION in ''.join(problems[0].itertext())
        cases.append({'name': case.get('name'), 'seconds': float(case.get('time', '0')),
                      'passed': not problems and case.get('status') == 'SUCCESS', 'deliberate_failure': deliberate})
    positives_pass = all(case['passed'] for case in cases if case['name'] not in controls)
    controls_match = all(case['deliberate_failure'] for case in cases if case['name'] in controls)
    # GNU timeout uses 124/137; these must never certify the control.
    exit_matches = maestro_exit == 1 if controls else maestro_exit == 0
    summary = {'suite': expected['suite'], 'positive_count': expected['positive_count'],
               'positive_passed': sum(case['passed'] for case in cases if case['name'] not in controls),
               'negative_control_verified': bool(controls) and controls_match,
               'evidence_complete': positives_pass and controls_match and exit_matches,
               'maestro_exit': maestro_exit, 'cases': cases}
    (reports / 'suite-results.json').write_text(json.dumps(summary, indent=2) + '\n')
    if not summary['evidence_complete']:
        raise ValueError('Selected journeys or deliberate assertion proof failed; inspect JUnit/debug output')
    return summary


def main():
    """Dispatch validation, flow preparation or report reconciliation; return errors without false proof."""
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    for name in ('validate', 'prepare'):
        sub = commands.add_parser(name)
        sub.add_argument('--suite', required=True)
        sub.add_argument('--repetitions', required=True)
        sub.add_argument('--negative', default='false')
        if name == 'prepare':
            sub.add_argument('--destination', type=Path, required=True)
    sub = commands.add_parser('report')
    sub.add_argument('--destination', type=Path, required=True)
    sub.add_argument('--maestro-exit', type=int, required=True)
    args = parser.parse_args()
    try:
        if args.command == 'validate':
            selection(args.suite, args.repetitions, args.negative)
        elif args.command == 'prepare':
            prepare(args.suite, args.repetitions, args.negative, args.destination)
        else:
            report(args.destination, args.maestro_exit)
    except (ValueError, OSError, ET.ParseError) as error:
        parser.exit(2, f'{error}\n')


if __name__ == '__main__':
    main()
