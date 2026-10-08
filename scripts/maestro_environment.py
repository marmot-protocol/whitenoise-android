"""Qualify the selected OS and navigation mode on the disposable CI emulator."""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time

APIS = {'33': 33, '34': 34, '36': 36, '37.0': 37}
MODES = {'button': ('threebutton', '0'), 'gesture': ('gestural', '2')}


def selection(api, mode):
    """Reject unsupported platform requests before a build or emulator is admitted."""
    if api not in APIS or mode not in MODES:
        raise ValueError('Select an allowlisted Android API and navigation mode')
    return APIS[api], MODES[mode]


def adb(*arguments):
    """Bound every device command to the disposable emulator serial."""
    return subprocess.check_output(['adb', '-s', 'emulator-5554', 'shell', *arguments],
                                   text=True, timeout=10).strip()


def configure(api, mode, environment=None):
    """Verify CI/QEMU/SDK, select navigation, and retain actual platform identity.

    Never write navigation settings on another device. An absent overlay, wrong
    SDK or unsettled SystemUI mode fails setup rather than qualifying a fallback.
    """
    sdk, (suffix, expected_mode) = selection(api, mode)
    env = os.environ if environment is None else environment
    source, run, attempt = (env.get(key, '') for key in ('GITHUB_SHA', 'GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT'))
    if (env.get('GITHUB_ACTIONS') != 'true' or not re.fullmatch('[0-9a-f]{40}', source)
            or not re.fullmatch('[1-9][0-9]*', run) or not re.fullmatch('[1-9][0-9]{0,8}', attempt)):
        raise ValueError('Exact GitHub workflow identity required')
    if adb('getprop', 'ro.kernel.qemu') != '1':
        raise ValueError('Disposable emulator required')
    if adb('getprop', 'ro.build.version.sdk') != str(sdk):
        raise ValueError('Emulator SDK differs from selected API')
    overlay = 'com.android.internal.systemui.navbar.' + suffix
    adb('cmd', 'overlay', 'enable-exclusive', '--category', '--user', '0', overlay)
    for _ in range(20):
        actual_mode = adb('settings', 'get', 'secure', 'navigation_mode')
        overlays = adb('cmd', 'overlay', 'list', '--user', '0')
        if actual_mode == expected_mode and f'[x] {overlay}' in overlays:
            fingerprint = adb('getprop', 'ro.build.fingerprint')
            if not fingerprint:
                raise ValueError('Missing Android image fingerprint')
            return {'schema': 1, 'source_sha': source, 'run_id': run, 'run_attempt': attempt,
                    'api': api, 'sdk': sdk, 'navigation': mode, 'navigation_mode': actual_mode,
                    'navigation_overlay': overlay, 'image_fingerprint': fingerprint, 'qemu': True}
        time.sleep(0.25)
    raise ValueError('Selected Android navigation mode did not settle')


def qualify(record, source, run, attempt, api, mode):
    """Reject foreign platform reports and return the immutable cross-shard OS identity."""
    sdk, (suffix, actual_mode) = selection(api, mode)
    expected = {'schema': 1, 'source_sha': source, 'run_id': str(run), 'run_attempt': str(attempt),
                'api': api, 'sdk': sdk, 'navigation': mode, 'navigation_mode': actual_mode,
                'navigation_overlay': 'com.android.internal.systemui.navbar.' + suffix, 'qemu': True}
    if (not isinstance(record, dict) or type(record.get('schema')) is not int
            or type(record.get('sdk')) is not int or record.get('qemu') is not True
            or any(record.get(key) != value for key, value in expected.items())
            or not isinstance(record.get('image_fingerprint'), str) or not record['image_fingerprint'].strip()):
        raise ValueError('Missing or mismatched observed Android platform')
    return {key: record[key] for key in ('api', 'sdk', 'navigation', 'navigation_mode',
                                        'navigation_overlay', 'image_fingerprint')}


def main():
    """Validate a request or write its observed, source-bound emulator report after successful setup."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['validate', 'configure'])
    parser.add_argument('--api', required=True)
    parser.add_argument('--navigation', required=True)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    selection(args.api, args.navigation)
    if args.action == 'configure':
        if args.output is None:
            parser.error('Observed environment output required')
        record = configure(args.api, args.navigation)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(record, indent=2) + '\n')


if __name__ == '__main__':
    main()
