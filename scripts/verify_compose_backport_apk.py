#!/usr/bin/env python3
"""Require the reviewed backport provenance resource in each packaged release APK."""
import sys
import zipfile

MARKER = 'META-INF/whitenoise-compose-rectlist-backport.properties'
EXPECTED = b'upstream=fd550bed793b66378c83091532e29c18fdef44cc\nbase=1.12.1\n'


def main():
    """Reject missing, duplicate or unexpected markers before a release artifact is handed off."""
    if len(sys.argv) < 2:
        raise SystemExit('Usage: verify_compose_backport_apk.py APK [APK ...]')
    for path in sys.argv[1:]:
        with zipfile.ZipFile(path) as apk:
            if apk.namelist().count(MARKER) != 1 or apk.read(MARKER) != EXPECTED:
                raise SystemExit(f'{path}: missing or mismatched Compose source-backport marker')
        print(f'{path}: reviewed Compose UI 1.12.1 backport marker present')


if __name__ == '__main__':
    main()
