#!/usr/bin/env python3
"""Check official UI metadata for the trusted master preview workflow during adoption.

The legacy filename remains because master dispatches check out the candidate's
scripts before calling it. Remove this compatibility entry point after master has
adopted the workflow that no longer calls it. It never patches Compose or accepts
the former backport. Version metadata is a packaging check, not a bytecode hash.
"""
import sys
import zipfile

VERSION_RESOURCE = 'META-INF/androidx.compose.ui_ui.version'
EXPECTED_VERSION = b'1.13.0-beta01\n'
BACKPORT_MARKER = 'META-INF/whitenoise-compose-rectlist-backport.properties'


def verify_apk(path):
    """Reject missing, duplicate or mismatched UI metadata and custom backport APKs."""
    with zipfile.ZipFile(path) as apk:
        names = apk.namelist()
        if BACKPORT_MARKER in names:
            raise ValueError(f'{path}: temporary Compose backport is still packaged')
        if names.count(VERSION_RESOURCE) != 1 or apk.read(VERSION_RESOURCE) != EXPECTED_VERSION:
            raise ValueError(f'{path}: expected official Compose UI 1.13.0-beta01 version metadata')


def main():
    """Validate every candidate passed by the trusted pre-adoption preview workflow."""
    if len(sys.argv) < 2:
        raise SystemExit('Usage: verify_compose_backport_apk.py APK [APK ...]')
    for path in sys.argv[1:]:
        try:
            verify_apk(path)
        except (OSError, ValueError, zipfile.BadZipFile) as error:
            raise SystemExit(str(error)) from error
        print(f'{path}: official Compose UI 1.13.0-beta01 metadata present; no custom backport marker')


if __name__ == '__main__':
    main()
