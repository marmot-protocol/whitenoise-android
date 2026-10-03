"""Check the viewport owner's narrow floor in the existing full-suite Kover report."""

import argparse
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


OWNER = 'dev/ipf/whitenoise/android/ui/conversation/ConversationViewportRestorationOwner'
# Measured in full-suite CI at 1e8a14bb897dfbd9dd3be82e0b11224f2f005c4f:
# 82/82 lines, 52/56 branches (92.857%). Keep every line and a 90% branch floor,
# including the saved fallback and remembered factory; exclude no owner classes.
LINE_FLOOR = 100
BRANCH_FLOOR = 90


def owner_class(name):
    """Include coroutine/Compose-generated classes, never unrelated UI classes."""
    return (name == OWNER or name.startswith(OWNER + '$')
            or name == OWNER + 'Kt' or name.startswith(OWNER + 'Kt$'))


def check_coverage(report):
    root = ET.parse(report).getroot()
    totals = {kind: [0, 0] for kind in ('LINE', 'BRANCH')}
    found_owner = False
    for klass in root.findall('./package/class'):
        name = klass.get('name', '')
        if not owner_class(name):
            continue
        found_owner |= name == OWNER
        for kind, counts in totals.items():
            counters = [counter for counter in klass.findall('counter') if counter.get('type') == kind]
            if len(counters) > 1 or (kind == 'LINE' and not counters):
                raise ValueError(f'{name}: invalid {kind} counter count')
            if not counters:
                continue  # Classes with no eligible branches omit that counter.
            values = [counters[0].get(key, '') for key in ('covered', 'missed')]
            if not all(value.isascii() and value.isdecimal() for value in values):
                raise ValueError(f'{name}: invalid {kind} counts')
            covered, missed = map(int, values)
            counts[0] += covered
            counts[1] += covered + missed
    if not found_owner:
        raise ValueError('missing viewport restoration owner')
    for kind, floor in (('LINE', LINE_FLOOR), ('BRANCH', BRANCH_FLOOR)):
        covered, total = totals[kind]
        if not total or covered * 100 < floor * total:
            raise ValueError(f'viewport owner {kind}: {covered}/{total}; minimum {floor}%')
    return totals


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('report', type=Path)
    args = parser.parse_args()
    try:
        totals = check_coverage(args.report)
    except (OSError, ET.ParseError, ValueError) as error:
        print(f'Coverage gate failed: {error}', file=sys.stderr)
        return 1
    print('Viewport owner coverage: ' + ', '.join(
        f'{kind} {covered}/{total}' for kind, (covered, total) in totals.items()))
    return 0


if __name__ == '__main__':
    sys.exit(main())
