"""Validate manual runtime selection before requesting a build or emulator."""

import json
import sys
try:
    from scripts.maestro_runtime import SUITES, CASES, MAX_CASES_PER_SHARD, case_selection
except ModuleNotFoundError:
    from maestro_runtime import SUITES, CASES, MAX_CASES_PER_SHARD, case_selection


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
        for part in range(1, partitions + 1):
            # The producer and executor must admit the same maintained, path-safe case names.
            case_selection(suite, part)
            result.append({'slice': suite, 'partition': part, 'partitions': partitions})
    # Exercise recovery and decode regressions before spending hours on the full
    # campaign. Sorting only schedules existing partitions; membership, limits,
    # cleanup and final reconciliation remain identical.
    return sorted(result, key=feedback_priority)


def feedback_priority(partition):
    """Prefer controlled recovery/media and modal checks without omitting any case."""
    names = case_selection(partition['slice'], partition['partition'])
    presentations = [CASES[name].get('presentation', '') for name in names]
    if any(name.startswith(('dictation-draft-', 'dictation-status-')) for name in presentations):
        return 0
    if any(name.startswith(('preview-', 'extra-native-viewer-'))
           or name == 'surface-animated-avatar' for name in presentations):
        return 1
    if partition['slice'] == 'modals':
        return 2
    if partition['slice'] == 'presentation':
        return 3
    return 4


def validate_credential_platform(value, api):
    """Reject unsupported PIN campaigns before requesting the APK producer or any emulator."""
    chosen = selection(value)
    if api != '34' and any(case['suite'] in chosen and case['postcondition'].startswith('app-lock-credential-')
                          for case in CASES.values()):
        raise ValueError('Synthetic device-credential cases initially require API34')


if __name__ == '__main__':
    try:
        validate_credential_platform(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else '34')
        print('slices=' + json.dumps(matrix_selection(sys.argv[1])))
    except (IndexError, ValueError) as error:
        sys.exit(str(error))
