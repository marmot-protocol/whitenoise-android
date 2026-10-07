"""Bind an optional test APK pair to its exact workflow revision and checksums."""

import hashlib
import json
import os
from pathlib import Path
import re
import sys


def digest(path):
    """Hash a regular APK file while rejecting symlinks and missing files."""
    if path.is_symlink() or not path.is_file():
        raise ValueError('Regular APK required')
    return hashlib.sha256(path.read_bytes()).hexdigest()


def pair(directory, mode, environ=os.environ):
    """Stage or verify app/test identity against the exact hosted source, run and attempt."""
    source = environ.get('GITHUB_SHA', '')
    if environ.get('GITHUB_ACTIONS') != 'true' or not re.fullmatch('[0-9a-f]{40}', source):
        raise ValueError('Exact GitHub workflow source required')
    expected = {'schema': 1, 'source_sha': source, 'distribution': 'Zapstore',
                'package': 'dev.ipf.whitenoise.android.maestrolab',
                'run_id': environ['GITHUB_RUN_ID'], 'run_attempt': environ['GITHUB_RUN_ATTEMPT'],
                'apk_sha256': digest(directory / 'app.apk'), 'test_apk_sha256': digest(directory / 'test.apk')}
    if mode == 'stage':
        if (directory / 'pair.json').exists():
            raise ValueError('Pair receipt already exists')
        (directory / 'pair.json').write_text(json.dumps(expected, indent=2) + '\n')
    elif mode != 'verify' or json.loads((directory / 'pair.json').read_text()) != expected:
        raise ValueError('Mismatched source, run or APK pair')
    return expected


if __name__ == '__main__':
    try:
        pair(Path(sys.argv[2]), sys.argv[1])
    except (IndexError, KeyError, ValueError, OSError) as error:
        sys.exit(str(error))
