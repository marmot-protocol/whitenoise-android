#!/usr/bin/env python3
"""Fail when a required instrumented case did not execute and pass in the connected results.

Usage: check_instrumented_required_cases.py RESULTS_DIR [--required FILE]

RESULTS_DIR is searched recursively for the JUnit XML that connected Android test
tasks write. Each case in the required list (config/instrumented-required-cases.txt)
must appear as an executed, non-skipped, passing testcase. A case that is absent,
for example because @SdkSuppress filtered it out on the runner's API level, fails
the check instead of letting a green runner hide that it never ran.
"""

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REQUIRED = ROOT / 'config/instrumented-required-cases.txt'
CASE = re.compile(r'^[a-zA-Z_][\w.]*\.[A-Z]\w*#\w+$')


def parse_required(text):
    """Return the declared `Class#method` cases and any malformed or duplicate lines."""
    cases, problems = [], []
    for number, raw in enumerate(text.splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith('#'):
            continue
        if not CASE.match(line):
            problems.append(f'line {number}: expected fully.qualified.Class#method, got {line!r}')
        elif line in cases:
            problems.append(f'line {number}: duplicate case {line!r}')
        else:
            cases.append(line)
    if not cases and not problems:
        problems.append('no required cases declared')
    return cases, problems


def case_outcomes(results_dir):
    """Map every `Class#method` in the JUnit XML under results_dir to its outcomes.

    Unreadable XML raises ValueError so a truncated report fails closed.
    """
    outcomes = {}
    for path in sorted(results_dir.rglob('*.xml')):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as error:
            raise ValueError(f'unreadable connected result {path.name}: {error}') from error
        for case in root.iter('testcase'):
            key = f"{case.get('classname', '')}#{case.get('name', '')}"
            if case.find('skipped') is not None:
                outcome = 'skipped'
            elif case.find('failure') is not None or case.find('error') is not None:
                outcome = 'failed'
            else:
                outcome = 'passed'
            outcomes.setdefault(key, []).append(outcome)
    return outcomes


def required_problems(cases, outcomes):
    """Describe each required case that is missing, skipped or failed on any device."""
    problems = []
    for case in cases:
        seen = outcomes.get(case)
        if not seen:
            problems.append(f'{case} did not run (missing from connected results)')
        elif 'skipped' in seen:
            problems.append(f'{case} was skipped')
        elif 'failed' in seen:
            problems.append(f'{case} failed')
    return problems


def main(argv=None, out=sys.stdout, err=sys.stderr):
    """Check the connected results against the required-case list and return an exit status."""
    parser = argparse.ArgumentParser(description=__doc__.split('\n', 1)[0])
    parser.add_argument('results_dir', type=Path)
    parser.add_argument('--required', type=Path, default=REQUIRED)
    args = parser.parse_args(argv)
    cases, problems = parse_required(args.required.read_text())
    if not args.results_dir.is_dir():
        problems.append(f'missing connected results directory {args.results_dir}')
    if not problems:
        try:
            problems = required_problems(cases, case_outcomes(args.results_dir))
        except ValueError as error:
            problems = [str(error)]
    for problem in problems:
        print(f'required instrumented case: {problem}', file=err)
    if problems:
        return 1
    print(f'required instrumented cases: {len(cases)} executed and passed', file=out)
    return 0


if __name__ == '__main__':
    sys.exit(main())
