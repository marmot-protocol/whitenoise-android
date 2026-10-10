#!/usr/bin/env python3
"""Check real packaged notices in an existing optimized APK; never compile it."""
import argparse
from pathlib import Path
import re
import subprocess
import zipfile


def packaged_notices(apk, aapt2):
    dump = subprocess.check_output([str(aapt2), 'dump', 'resources', str(apk)],
                                   text=True, timeout=60)
    resources = {}
    name = None
    for line in dump.splitlines():
        header = re.search(r'\bresource 0x[0-9a-f]+ raw/(\w+)\s*$', line)
        if 'resource 0x' in line:
            name = header[1] if header else None
        file = re.search(r'\(file\) (\S+)', line)
        if name is not None and file:
            if name in resources and resources[name] != file[1]:
                raise ValueError('notice resource has ambiguous configurations')
            resources[name] = file[1]
    required = ('third_party_license_metadata', 'third_party_licenses', 'material_icons_notice')
    if any(name not in resources for name in required):
        raise ValueError('optimized APK dropped required open source notice resources')
    with zipfile.ZipFile(apk) as archive:
        data = {}
        for name in required:
            member = archive.getinfo(resources[name])
            if not 0 < member.file_size <= 16_777_216:
                raise ValueError('notice resource missing, empty or oversized')
            data[name] = archive.read(member)
    entries = data['third_party_license_metadata'].decode('utf-8').splitlines()
    if len(data['third_party_license_metadata']) > 1_048_576:
        raise ValueError('packaged notice metadata exceeds the reader bound')
    text = data['third_party_licenses']
    count = 0
    for line in entries:
        if not line.strip():
            continue
        extent, name = line.split(' ', 1)
        offset, length = map(int, extent.split(':'))
        if not name.strip() or offset < 0 or length <= 0 or offset + length > len(text):
            raise ValueError('packaged notice index is malformed or truncated')
        if not text[offset:offset + length].decode('utf-8').strip():
            raise ValueError('packaged notice text is empty')
        count += 1
    if not 0 < count <= 4096 or not data['material_icons_notice'].decode('utf-8').strip():
        raise ValueError('packaged dependency or local attribution is empty')
    print(f'Packaged notice resources verified: {count} generated notices and local icon attribution')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', required=True, type=Path)
    parser.add_argument('--aapt2', type=Path)
    args = parser.parse_args()
    executable = args.aapt2
    if executable is None:
        import os
        sdk = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or '')
        candidates = sorted((sdk / 'build-tools').glob('*/aapt2'),
                            key=lambda path: tuple(map(int, re.findall(r'\d+', path.parent.name))))
        if not candidates:
            raise SystemExit('aapt2 unavailable for packaged-resource verification')
        executable = candidates[-1]
    packaged_notices(args.apk, executable)
