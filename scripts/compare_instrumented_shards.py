#!/usr/bin/env python3
"""Fail when connected test shards overlap, or when together they differ from an unsharded run.

Usage: compare_instrumented_shards.py SHARD_DIR... [--baseline UNSHARDED_DIR]

Each SHARD_DIR holds one shard's connected JUnit XML. A test reported by more than
one shard fails the check. With --baseline, the union of the shards must also name
exactly the tests reported by an unsharded run of the same commit, so a test that
no shard executed is reported even when it is not on the required-case list.
"""

import argparse
import sys
from pathlib import Path

from check_instrumented_required_cases import case_outcomes


def compare(shard_cases, baseline=None):
    """Return problems for tests seen in several shards or differing from the baseline."""
    owners = {}
    for index, cases in enumerate(shard_cases):
        for case in cases:
            owners.setdefault(case, []).append(index)
    problems = [f'{case} ran in shards {owners[case]}' for case in sorted(owners) if len(owners[case]) > 1]
    if baseline is not None:
        problems += [f'{case} ran unsharded but in no shard' for case in sorted(baseline - owners.keys())]
        problems += [f'{case} ran in a shard but not unsharded' for case in sorted(owners.keys() - baseline)]
    return problems, len(owners)


def main(argv=None, out=sys.stdout, err=sys.stderr):
    """Compare the shard reports and return an exit status."""
    parser = argparse.ArgumentParser(description=__doc__.split('\n', 1)[0])
    parser.add_argument('shards', type=Path, nargs='+')
    parser.add_argument('--baseline', type=Path)
    args = parser.parse_args(argv)
    dirs = args.shards + ([args.baseline] if args.baseline else [])
    missing = [str(path) for path in dirs if not path.is_dir()]
    if missing:
        print(f'instrumented shards: missing results directory {", ".join(missing)}', file=err)
        return 1
    try:
        shard_cases = [set(case_outcomes(path)) for path in args.shards]
        baseline = set(case_outcomes(args.baseline)) if args.baseline else None
    except ValueError as error:
        print(f'instrumented shards: {error}', file=err)
        return 1
    problems, total = compare(shard_cases, baseline)
    if not total:
        problems.append('no testcases in any shard')
    for problem in problems:
        print(f'instrumented shards: {problem}', file=err)
    if problems:
        return 1
    sizes = ' + '.join(str(len(cases)) for cases in shard_cases)
    match = f', matching {len(baseline)} unsharded' if baseline is not None else ''
    print(f'instrumented shards: {total} tests ran exactly once ({sizes}){match}', file=out)
    return 0


if __name__ == '__main__':
    sys.exit(main())
