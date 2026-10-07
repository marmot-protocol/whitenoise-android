"""Validate manual runtime selection before requesting a build or emulator."""

import json
import sys
from scripts.maestro_runtime import SUITES


def selection(value):
    """Accept only maintained runtime slices before admitting a fixture build."""
    if value == 'runtime-all':
        return list(SUITES)
    if value.startswith('runtime-') and value[8:] in SUITES:
        return [value[8:]]
    raise ValueError('Select an allowlisted runtime slice')


if __name__ == '__main__':
    try:
        print('slices=' + json.dumps(selection(sys.argv[1])))
    except (IndexError, ValueError) as error:
        sys.exit(str(error))
