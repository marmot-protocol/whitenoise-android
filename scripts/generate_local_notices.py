#!/usr/bin/env python3
"""Generate the existing Android raw-resource contract from reviewed local inputs."""
import argparse
import hashlib
import json
from pathlib import Path
import re

if __package__:
    from .verify_notice_resources import require_baseline
else:
    from verify_notice_resources import require_baseline


def unique_object(pairs):
    """Reject duplicate JSON keys rather than trusting an overwritten declaration."""
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('Duplicate JSON key: ' + key)
        result[key] = value
    return result


def read_json(path):
    """Read bounded UTF-8 metadata, including duplicate-key validation."""
    if path.stat().st_size > 8 * 1024 * 1024:
        raise ValueError('Oversized notice metadata')
    return json.loads(path.read_text(encoding='utf-8'), object_pairs_hook=unique_object)


def artifact_key(item):
    """Validate a selected external artifact's stable identity and byte fingerprint."""
    coordinate = item.get('coordinate', '')
    name = item.get('name', '')
    sha = item.get('sha256', '')
    if (not isinstance(coordinate, str) or
            not re.fullmatch(r'[A-Za-z0-9_.+-]+:[A-Za-z0-9_.+-]+:[A-Za-z0-9_.+-]+', coordinate) or
            not isinstance(name, str) or not re.fullmatch(r'[A-Za-z0-9_.+-]+', name) or
            name in ['.', '..'] or not isinstance(sha, str) or not re.fullmatch(r'[a-f0-9]{64}', sha)):
        raise ValueError('Malformed notice artifact identity')
    return coordinate, name


def read_text(catalog_path, item, name):
    """Read one pinned, reviewed text under texts/, with its source recorded."""
    relative = Path(item.get('text', ''))
    if relative.is_absolute() or '..' in relative.parts or len(relative.parts) != 2 or relative.parts[0] != 'texts':
        raise ValueError('Unsafe notice text path')
    text_path = catalog_path.parent / relative
    if text_path.is_symlink() or not text_path.is_file():
        raise ValueError('Missing notice text: ' + name)
    try:
        text_path.resolve().relative_to(catalog_path.parent.resolve())
    except ValueError as error:
        raise ValueError('Notice text escapes reviewed directory') from error
    if not 0 < text_path.stat().st_size <= 16 * 1024 * 1024:
        raise ValueError('Invalid notice text size: ' + name)
    data = text_path.read_bytes()
    if hashlib.sha256(data).hexdigest() != item.get('sha256'):
        raise ValueError('Changed or truncated notice text: ' + name)
    if not data.decode('utf-8', errors='strict').strip():
        raise ValueError('Empty notice text: ' + name)
    if not isinstance(item.get('source'), str) or not item['source'].strip():
        raise ValueError('Missing notice source: ' + name)
    return data


def read_catalog(path):
    """Verify every pinned notice before selecting entries for a build variant."""
    catalog = read_json(path)
    if type(catalog.get('schema')) is not int or catalog['schema'] != 1 or not isinstance(catalog.get('artifacts'), list):
        raise ValueError('Unsupported notice catalog')
    provenance = catalog.get('reviewed_from', {})
    if (not isinstance(provenance, dict) or
            not re.fullmatch(r'[a-f0-9]{40}', provenance.get('source_sha', '')) or
            not isinstance(provenance.get('evidence'), str) or not provenance['evidence'].strip()):
        raise ValueError('Missing reviewed notice provenance')
    notices = {}
    for item in catalog.get('notices', []):
        identifier = item.get('id')
        name = item.get('name')
        if (not isinstance(identifier, str) or not re.fullmatch(r'[A-Za-z0-9_.:-]+', identifier) or
                identifier in notices or not isinstance(name, str) or not name.strip() or
                name != name.strip() or '\n' in name or '\r' in name):
            raise ValueError('Invalid or duplicate notice name/id')
        data = read_text(path, item, name)
        if 'full_text' in item:
            # A licence named only by URL keeps that original line and gains its reviewed full text.
            if not isinstance(item['full_text'], dict):
                raise ValueError('Malformed full licence text: ' + name)
            data += b'\n\n' + read_text(path, item['full_text'], name)
        notices[identifier] = (name, data)
    if not notices or len(notices) > 4096:
        raise ValueError('Missing or oversized notice set')
    artifacts = {}
    for item in catalog['artifacts']:
        key = artifact_key(item)
        identifiers = item.get('notice_ids')
        if (key in artifacts or not isinstance(identifiers, list) or not identifiers or
                any(not isinstance(i, str) or i not in notices for i in identifiers) or
                len(set(identifiers)) != len(identifiers)):
            raise ValueError('Missing or ambiguous artifact attribution: ' + str(key))
        artifacts[key] = item
    bundled = catalog.get('bundled_notice_ids', [])
    if (not isinstance(bundled, list) or
            any(not isinstance(i, str) or i not in notices for i in bundled) or
            len(set(bundled)) != len(bundled)):
        raise ValueError('Invalid bundled native attribution')
    return artifacts, notices, bundled


def generate(catalog_path, selected, output, baseline_path=None, icons_path=None):
    """Require complete artifact attribution and emit deterministic UTF-8 byte offsets."""
    artifacts, notices, bundled = read_catalog(catalog_path)
    if not isinstance(selected, list) or not selected:
        raise ValueError('Empty selected runtime artifacts')
    identifiers = set(bundled)
    observed = set()
    unreviewed = []
    for item in selected:
        key = artifact_key(item)
        expected = artifacts.get(key)
        if key in observed:
            raise ValueError('Duplicate selected runtime artifact: ' + str(key))
        observed.add(key)
        if expected is None or expected['sha256'] != item['sha256']:
            # Report every gap at once so one dependency bump needs one catalogue review.
            change = 'new artifact' if expected is None else 'changed bytes, reviewed ' + expected['sha256']
            unreviewed.append(f"{key[0]} {key[1]} sha256={item['sha256']} ({change})")
            continue
        identifiers.update(expected['notice_ids'])
    if unreviewed:
        raise ValueError('Unreviewed runtime artifacts; update config/licenses/catalog.json as described in '
                         'docs/dependency-reduction/README.md:\n  ' + '\n  '.join(sorted(unreviewed)))
    entries = sorted({notices[i] for i in identifiers}, key=lambda entry: (entry[0].encode('utf-8'), entry[1]))
    content = bytearray()
    metadata = []
    offsets = {}
    for name, data in entries:
        if data not in offsets:
            offsets[data] = len(content)
            content.extend(data)
            content.extend(b'\n')
        metadata.append(f'{offsets[data]}:{len(data)} {name}\n')
    index = ''.join(metadata).encode('utf-8')
    if len(index) > 1_048_576 or len(content) > 16_777_216 or len(entries) > 4096:
        raise ValueError('Generated notices exceed Android reader bounds')
    baseline_path = baseline_path or catalog_path.parent / 'baseline.json'
    icons_path = icons_path or catalog_path.parents[2] / 'app/src/main/res/raw/material_icons_notice.txt'
    baseline = read_json(baseline_path)
    pin = read_json(catalog_path).get('baseline', {})
    if (pin.get('sha256') != hashlib.sha256(baseline_path.read_bytes()).hexdigest() or
            pin.get('source_sha') != baseline.get('source_sha')):
        raise ValueError('Wrong-source or changed notice baseline')
    if not icons_path.is_file() or icons_path.is_symlink() or not 0 < icons_path.stat().st_size <= 16_777_216:
        raise ValueError('Missing or unsafe icon attribution')
    require_baseline(index, bytes(content), icons_path.read_bytes(), baseline_path)
    output.mkdir(parents=True, exist_ok=True)
    (output / 'third_party_license_metadata').write_bytes(index)
    (output / 'third_party_licenses').write_bytes(content)
    return len(entries)


def main():
    """Expose a local-only build command; all network acquisition belongs to review."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--catalog', required=True, type=Path)
    parser.add_argument('--selected', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--baseline', type=Path)
    parser.add_argument('--icons-notice', type=Path)
    arguments = parser.parse_args()
    count = generate(arguments.catalog, read_json(arguments.selected), arguments.output,
                     arguments.baseline, arguments.icons_notice)
    print(f'Generated {count} reviewed notices')


if __name__ == '__main__':
    main()
