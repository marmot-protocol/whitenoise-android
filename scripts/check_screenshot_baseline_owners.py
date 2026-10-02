#!/usr/bin/env python3
"""Keep every committed Roborazzi golden owned by the curated screenshot CI selection.

The registry in config/screenshot-baseline-owners.txt lists the Gradle `--tests`
filters the "Curated screenshot verification" job runs. This script has three modes:

* no arguments: static consistency check for the tooling job. Every registry filter
  must resolve to an existing capturing test, and every unit-test class that calls
  captureRoboImage must be selected (or declared `diagnostic:`).
* --gradle-test-args: print the filters as `--tests` arguments, one per line, for
  the screenshot job to pass to Gradle.
* --results SUMMARY: after a verification run, require that Roborazzi compared every
  PNG committed under app/src/test/snapshots/, so a golden whose owner is no longer
  selected fails instead of silently going unverified.
"""

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REGISTRY = Path('config/screenshot-baseline-owners.txt')
SNAPSHOTS = Path('app/src/test/snapshots')
TEST_SOURCE_GLOB = 'app/src/test*/**/*.kt'
SUFFIX_FILTER = re.compile(r'^\*([A-Z]\w*)$')
CLASS_FILTER = re.compile(r'^\*\.([A-Z]\w*)$')
METHOD_FILTER = re.compile(r'^\*\.([A-Z]\w*)\.(\w+)$')
TEST_METHOD = re.compile(r'@Test\b(?:\s*(?://[^\n]*|@\w+(?:\([^)]*\))?))*\s*(?:\w+\s+)*fun\s+(\w+)\s*\(')
COMPARED_TYPES = frozenset({'unchanged', 'changed'})


def parse_registry(text):
    """Return (owner filters, diagnostic class names) and reject malformed or duplicate lines."""
    filters, diagnostics, problems = [], [], []
    for number, raw in enumerate(text.splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith('#'):
            continue
        if line.startswith('diagnostic:'):
            diagnostics.append(line.split(':', 1)[1].strip())
            continue
        if not (SUFFIX_FILTER.match(line) or CLASS_FILTER.match(line) or METHOD_FILTER.match(line)):
            problems.append(f'line {number}: unsupported filter {line!r}')
        elif line in filters:
            problems.append(f'line {number}: duplicate filter {line!r}')
        filters.append(line)
    return filters, diagnostics, problems


def test_sources(root):
    """Map each unit-test file's class name to its path, across every unit-test source set."""
    sources = {}
    for path in sorted(root.glob(TEST_SOURCE_GLOB)):
        sources.setdefault(path.stem, []).append(path)
    return sources


def test_methods(text):
    """Return the names of the JUnit @Test methods declared in one Kotlin source."""
    return set(TEST_METHOD.findall(text))


def selected_classes(filters, class_names):
    """Return the class names that at least one registry filter selects."""
    selected = set()
    for owner in filters:
        suffix = SUFFIX_FILTER.match(owner)
        if suffix:
            selected.update(name for name in class_names if name.endswith(suffix.group(1)))
            continue
        exact = CLASS_FILTER.match(owner) or METHOD_FILTER.match(owner)
        if exact:
            selected.add(exact.group(1))
    return selected


def resolve_filter(owner, sources):
    """Return a problem description when a filter selects no capturing test, else None."""
    suffix = SUFFIX_FILTER.match(owner)
    if suffix:
        matches = [name for name in sources if name.endswith(suffix.group(1))]
        return None if matches else f'{owner} selects no unit-test class'
    exact = CLASS_FILTER.match(owner) or METHOD_FILTER.match(owner)
    if exact is None:
        return None
    paths = sources.get(exact.group(1), [])
    if len(paths) != 1:
        return f'{owner} must name exactly one unit-test class, found {len(paths)}'
    text = paths[0].read_text()
    if 'captureRoboImage' not in text:
        return f'{owner} names a class that captures no Roborazzi image'
    if exact.re is METHOD_FILTER and exact.group(2) not in test_methods(text):
        return f'{owner} names no @Test method in {paths[0].name}'
    return None


def static_problems(root, registry_text):
    """Check the registry against the unit-test sources without running Gradle."""
    filters, diagnostics, problems = parse_registry(registry_text)
    sources = test_sources(root)
    for owner in filters:
        problem = resolve_filter(owner, sources)
        if problem:
            problems.append(problem)
    capturing = {
        name for name, paths in sources.items()
        if any('captureRoboImage(' in path.read_text() for path in paths)
    }
    selected = selected_classes(filters, sources)
    for name in sorted(set(diagnostics) - capturing):
        problems.append(f'diagnostic:{name} does not capture a Roborazzi image')
    for name in sorted(set(diagnostics) & selected):
        problems.append(f'{name} is both a golden owner and diagnostic-only')
    for name in sorted(capturing - selected - set(diagnostics)):
        problems.append(
            f'{name} calls captureRoboImage but is not selected; add its golden-producing tests to '
            f'{REGISTRY} (or mark it diagnostic: when it owns no committed golden)'
        )
    return problems


def gradle_test_args(registry_text):
    """Return the Gradle arguments that select exactly the registered owners."""
    filters, _, problems = parse_registry(registry_text)
    if problems or not filters:
        raise ValueError('; '.join(problems) or 'registry selects no owners')
    args = []
    for owner in filters:
        args.extend(('--tests', owner))
    return args


def compared_goldens(summary, snapshots_dir):
    """Return committed-golden file names that a Roborazzi results summary compared."""
    compared = set()
    for result in summary.get('results', []):
        golden = Path(result.get('golden_file_path', ''))
        if result.get('type') in COMPARED_TYPES and golden.parent.parts[-4:] == snapshots_dir.parts[-4:]:
            compared.add(golden.name)
    return compared


def results_problems(root, summary):
    """Report committed PNG goldens that a verification run never compared."""
    snapshots = root / SNAPSHOTS
    committed = {path.name for path in snapshots.glob('*.png')}
    if not committed:
        return [f'no committed goldens found under {SNAPSHOTS}']
    missing = sorted(committed - compared_goldens(summary, SNAPSHOTS))
    return [
        f'{name} was not compared; select the test that captures it in {REGISTRY}'
        for name in missing
    ]


def main(argv=None, root=ROOT, out=sys.stdout, err=sys.stderr):
    """Run the selected mode and return a process exit status."""
    parser = argparse.ArgumentParser(description=__doc__.split('\n', 1)[0])
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--gradle-test-args', action='store_true', help='print --tests arguments, one per line')
    mode.add_argument('--results', type=Path, help='Roborazzi results-summary.json to check for coverage')
    args = parser.parse_args(argv)
    registry_text = (root / REGISTRY).read_text()
    if args.gradle_test_args:
        try:
            print('\n'.join(gradle_test_args(registry_text)), file=out)
        except ValueError as error:
            print(f'screenshot owner registry: {error}', file=err)
            return 1
        return 0
    if args.results:
        if not args.results.is_file():
            print(f'screenshot owner coverage: missing results summary {args.results}', file=err)
            return 1
        problems = results_problems(root, json.loads(args.results.read_text()))
    else:
        problems = static_problems(root, registry_text)
    for problem in problems:
        print(f'screenshot owner: {problem}', file=err)
    if problems:
        return 1
    print('screenshot owners: every committed golden has a selected owner', file=out)
    return 0


if __name__ == '__main__':
    sys.exit(main())
