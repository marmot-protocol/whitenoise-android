#!/usr/bin/env python3
"""Check real packaged notices in an existing optimized APK; never compile it."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile

if __package__:
    from .retain_dependency_evidence import validate_retained
else:
    from retain_dependency_evidence import validate_retained


def notice_rows(metadata, texts):
    """Decode the reader's offset:length index into (name, text SHA-256) rows."""
    rows = []
    for line in metadata.decode('utf-8').splitlines():
        if not line.strip():
            continue
        extent, name = line.split(' ', 1)
        offset, length = map(int, extent.split(':'))
        if not name.strip() or offset < 0 or length <= 0 or offset + length > len(texts):
            raise ValueError('packaged notice index is malformed or truncated')
        text = texts[offset:offset + length]
        if not text.decode('utf-8').strip():
            raise ValueError('packaged notice text is empty')
        rows.append((name, hashlib.sha256(text).hexdigest()))
    return rows


def require_baseline(metadata, texts, icons, baseline):
    """Keep every original notice identity and the exact local copyright attribution."""
    expected = json.loads(baseline.read_text(encoding='utf-8'))
    if (type(expected.get('schema')) is not int or expected['schema'] != 1 or
            not re.fullmatch(r'[a-f0-9]{40}', expected.get('source_sha', '')) or
            not isinstance(expected.get('required_notices'), list) or not expected['required_notices'] or
            not isinstance(expected.get('retained'), dict) or not isinstance(expected.get('resources'), dict)):
        raise ValueError('missing source-bound notice baseline')
    # The baseline must equal the retained original plugin output, not merely its own pin.
    root = baseline.resolve().parents[2]
    receipt = validate_retained(root, expected['retained'], expected['source_sha'])
    original = {}
    for key, name in [('metadata_sha256', 'third_party_license_metadata'), ('text_sha256', 'third_party_licenses')]:
        paths = [path for path in receipt['files'] if path.endswith('/' + name)]
        if len(paths) != 1 or receipt['files'][paths[0]] != expected['resources'].get(key):
            raise ValueError('retained original notice resources do not match the baseline')
        original[name] = (root / paths[0]).read_bytes()
    required = sorted((item['name'], item['text_sha256']) for item in expected['required_notices'])
    if sorted(notice_rows(original['third_party_license_metadata'], original['third_party_licenses'])) != required:
        raise ValueError('notice baseline differs from the retained original plugin output')
    missing = set(required) - set(notice_rows(metadata, texts))
    if missing:
        raise ValueError('original notices were lost or changed: ' + ', '.join(sorted(name for name, _ in missing)))
    if hashlib.sha256(icons).hexdigest() != expected.get('material_icons_sha256'):
        raise ValueError('local icon copyright/provenance attribution changed')


def packaged_notices(apk, aapt2, baseline=None):
    """Read actual retained resources through aapt2, then check bounds and equivalence."""
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
            if archive.namelist().count(resources[name]) != 1:
                raise ValueError('notice resource ZIP entry is missing or duplicated')
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
    if baseline is None:
        baseline = Path(__file__).resolve().parent.parent / 'config/licenses/baseline.json'
    require_baseline(data['third_party_license_metadata'], text, data['material_icons_notice'], baseline)
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
