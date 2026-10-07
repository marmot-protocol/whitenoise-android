"""Validate manual runtime selection before requesting a build or emulator."""

import json
import sys
from scripts.maestro_runtime import SUITES, CASES, MAX_CASES_PER_SHARD


def selection(value):
    """Accept only maintained runtime slices before admitting a fixture build."""
    if value == 'runtime-all':
        return list(SUITES)
    if value.startswith('runtime-') and value[8:] in SUITES:
        return [value[8:]]
    raise ValueError('Select an allowlisted runtime slice')


def matrix_selection(value):
    """Expand selected logical suites into bounded partitions that reuse one APK pair."""
    result = []
    for suite in selection(value):
        count = sum(case['suite'] == suite for case in CASES.values())
        partitions = (count + MAX_CASES_PER_SHARD - 1) // MAX_CASES_PER_SHARD
        result.extend({'slice': suite, 'partition': part, 'partitions': partitions}
                      for part in range(1, partitions + 1))
    return result


if __name__ == '__main__':
    try:
        print('slices=' + json.dumps(matrix_selection(sys.argv[1])))
    except (IndexError, ValueError) as error:
        sys.exit(str(error))
