"""Enforce the measured teardown-owner floor using the existing Kover report."""

import argparse
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


OWNER = 'dev/ipf/whitenoise/android/state/AppRuntimeListenerTeardownOwner'
LINE_FLOOR = 94


def check_coverage(report):
    """Count class-level lines once, including the owner's generated classes."""
    root = ET.parse(report).getroot()
    covered = missed = 0
    found_owner = False
    for klass in root.findall('./package/class'):
        name = klass.get('name', '')
        if name != OWNER and not name.startswith(OWNER + '$'):
            continue
        found_owner |= name == OWNER
        counters = [counter for counter in klass.findall('counter')
                    if counter.get('type') == 'LINE']
        if len(counters) != 1:
            raise ValueError(f'{name}: expected exactly one class LINE counter')
        counts = [counters[0].get(key, '') for key in ('covered', 'missed')]
        if not all(value.isascii() and value.isdecimal() for value in counts):
            raise ValueError(f'{name}: invalid LINE counts')
        class_covered, class_missed = map(int, counts)
        covered += class_covered
        missed += class_missed
    total = covered + missed
    if not found_owner or total == 0:
        raise ValueError('missing teardown owner or eligible lines')
    if covered * 100 < LINE_FLOOR * total:
        raise ValueError(f'teardown owner: {covered}/{total} lines; minimum {LINE_FLOOR}%')
    return covered, total


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('report', type=Path)
    args = parser.parse_args()
    try:
        covered, total = check_coverage(args.report)
    except (OSError, ET.ParseError, ValueError) as error:
        print(f'Coverage gate failed: {error}', file=sys.stderr)
        return 1
    print(f'Teardown owner coverage: {covered}/{total} lines (minimum {LINE_FLOOR}%)')
    return 0


if __name__ == '__main__':
    sys.exit(main())
