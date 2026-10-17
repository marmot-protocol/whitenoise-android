#!/usr/bin/env python3
"""Retain compact, source-bound capture bytes that survive hosted artifact expiry."""
import gzip
import hashlib
import json
from pathlib import Path
import re


def sha256(path):
    """Hash retained evidence independently of its original hosted URL lifetime."""
    return hashlib.sha256(path.read_bytes()).hexdigest()


def retain_capture(capture, destination, repository_root, evidence_url):
    """Copy original reports as deterministic gzip, preserving their original source identity."""
    source = (capture / 'source.txt').read_text().strip()
    if not re.fullmatch(r'[a-f0-9]{40}', source):
        raise ValueError('Invalid captured source identity')
    snapshot = json.loads((capture / 'source-inputs.json').read_text())
    if snapshot.get('schema') != 1 or snapshot.get('source_sha') != source:
        raise ValueError('Wrong-source captured build inputs')
    destination.mkdir(parents=True, exist_ok=True)
    files = {}
    for name in ['resolution.json', 'artifact-evidence.json', 'source-inputs.json']:
        data = (capture / name).read_bytes()
        if not 0 < len(data) <= 32 * 1024 * 1024:
            raise ValueError('Oversized or empty capture report')
        path = destination / (name + '.gz')
        path.write_bytes(gzip.compress(data, mtime=0))
        files[str(path.relative_to(repository_root))] = sha256(path)
    for source_path in sorted((capture / 'candidate-inputs').rglob('*')):
        if not source_path.is_file():
            continue
        relative = source_path.relative_to(capture / 'candidate-inputs')
        path = destination / 'captured-inputs' / str(relative)
        path = path.with_name(path.name + '.gz')
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(gzip.compress(source_path.read_bytes(), mtime=0))
        files[str(path.relative_to(repository_root))] = sha256(path)
    receipt = destination / 'capture.json'
    receipt.write_text(json.dumps({'schema': 1, 'source_sha': source, 'original_evidence': evidence_url,
                                   'files': files}, indent=2, sort_keys=True) + '\n')
    return {'path': str(receipt.relative_to(repository_root)), 'sha256': sha256(receipt)}


def validate_retained(root, reference, source):
    """Check retained bytes and their captured source without Git or network access."""
    relative = reference.get('path', '')
    path = root / relative
    allowed = ('gradle/dependency-evidence/', 'config/licenses/evidence/')
    if (not relative.startswith(allowed) or '..' in Path(relative).parts or not path.is_file() or
            path.is_symlink() or not path.resolve().is_relative_to(root.resolve()) or
            path.stat().st_size > 1024 * 1024 or sha256(path) != reference.get('sha256')):
        raise ValueError('Missing or changed retained dependency evidence')
    receipt = json.loads(path.read_text())
    if receipt.get('schema') != 1 or receipt.get('source_sha') != source or not receipt.get('files'):
        raise ValueError('Wrong-source retained dependency evidence')
    for name, expected in receipt['files'].items():
        asset = root / name
        if (not name.startswith(allowed) or '..' in Path(name).parts or not asset.is_file() or asset.is_symlink() or
                not asset.resolve().is_relative_to(root.resolve()) or asset.stat().st_size > 32 * 1024 * 1024 or
                not re.fullmatch(r'[a-f0-9]{64}', expected) or sha256(asset) != expected):
            raise ValueError('Missing or changed retained dependency evidence asset')
        if name.endswith('source-inputs.json.gz'):
            with gzip.open(asset, 'rb') as stream:
                payload = stream.read(8 * 1024 * 1024 + 1)
            if len(payload) > 8 * 1024 * 1024:
                raise ValueError('Oversized retained source snapshot')
            snapshot = json.loads(payload)
            if snapshot.get('schema') != 1 or snapshot.get('source_sha') != source:
                raise ValueError('Wrong-source retained build snapshot')
    return receipt
