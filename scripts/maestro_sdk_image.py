"""Verify the emulator image before launch; retry only a diagnosed SDK archive failure."""

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET


def image_package(api):
    if api not in {'33', '34', '36', '37.0'}:
        raise ValueError('Unsupported Maestro Android API')
    target = 'google_apis_ps16k' if api == '37.0' else 'google_apis'
    return f'system-images;android-{api};{target};x86_64'


def installed(root, package):
    folder = root.joinpath(*package.split(';'))
    metadata, image = folder / 'package.xml', folder / 'system.img'
    if any(p.is_symlink() for p in (folder, metadata, image)):
        return False
    if not metadata.is_file() or not image.is_file() or image.stat().st_size == 0:
        return False
    try:
        document = ET.parse(metadata)
    except (ET.ParseError, OSError):
        return False
    return any(node.tag.rsplit('}', 1)[-1] == 'localPackage' and node.get('path') == package
               for node in document.iter())


def prepare(api, root, reports, manager, run=subprocess.run):
    package = image_package(api)
    reports.mkdir(parents=True, exist_ok=True)
    if installed(root, package):
        return 0
    for attempt in (1, 2):
        log = reports / f'install-{attempt}.log'
        with log.open('w') as output:
            result = run([manager, '--sdk_root=' + str(root), '--install', package, '--channel=0'],
                         input='y\n' * 64, text=True, stdout=output, stderr=subprocess.STDOUT, timeout=180)
        if result.returncode == 0 and installed(root, package):
            return attempt
        # No UI/instrumentation rerun, generalized SDK retry or longer boot deadline.
        tail = log.read_bytes()[-65536:].decode('utf-8', errors='replace')
        if attempt == 2 or 'Error on ZipFile unknown archive' not in tail:
            raise RuntimeError(f'SDK image installation did not qualify; inspect {log}')
    raise AssertionError('Unreachable SDK retry state')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--api', required=True)
    parser.add_argument('--reports', type=Path, required=True)
    args = parser.parse_args()
    root = os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME')
    manager = shutil.which('sdkmanager')
    if root and not manager:
        candidate = Path(root) / 'cmdline-tools/latest/bin/sdkmanager'
        if candidate.is_file() and os.access(candidate, os.X_OK):
            manager = str(candidate)
    if not root or not manager:
        parser.error('Configured Android SDK and sdkmanager are required')
    try:
        attempts = prepare(args.api, Path(root), args.reports, manager)
    except (ValueError, RuntimeError, OSError, subprocess.TimeoutExpired) as error:
        parser.exit(1, str(error) + '\n')
    print(f'Android image verified before emulator launch; installation attempts: {attempts}')


if __name__ == '__main__':
    main()
