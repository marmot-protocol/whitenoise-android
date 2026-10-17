#!/usr/bin/env python3
"""Retain hosted bootstrap candidates for review, never approve them automatically."""
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import zipfile


def digest(path):
    """Fingerprint an input incrementally, including large tool archives."""
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()


def capture(root):
    """Copy lock candidates after Gradle exits and retain exact notice source bytes."""
    output = root / 'build/dependency-inputs-bootstrap'
    for path in [*root.rglob('*gradle.lockfile'),
                 root / 'gradle/verification-metadata.xml']:
        relative = path.relative_to(root)
        if path.is_file() and 'build' not in relative.parts and '.gradle' not in relative.parts:
            destination = output / 'candidate-inputs' / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, destination)
    source = (output / 'source.txt').read_text().strip()
    actual = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
    if source != actual:
        raise ValueError('Captured resolution source differs from checkout')
    names = subprocess.check_output(['git', 'ls-files'], cwd=root, text=True).splitlines()
    inputs = {}
    for name in names:
        if (name.endswith(('.gradle', '.gradle.kts', '.versions.toml')) or
                name in ['scripts/capture_dependency_candidates.py', '.github/workflows/android-ci.yml',
                         'gradle.properties', 'gradle/wrapper/gradle-wrapper.properties']):
            data = subprocess.check_output(['git', 'show', 'HEAD:' + name], cwd=root)
            if len(data) > 2 * 1024 * 1024:
                raise ValueError('Oversized captured build input')
            inputs[name] = data.decode('utf-8')
    (output / 'source-inputs.json').write_text(json.dumps(
        {'schema': 1, 'source_sha': source, 'files': inputs}, indent=2, sort_keys=True) + '\n')
    records = []
    paths = json.loads((output / 'artifact-paths.json').read_text())
    for item in paths:
        coordinate = item['coordinate']
        segments = coordinate.split(':')
        if len(segments) != 3 or not all(re.fullmatch(r'[A-Za-z0-9_.-]+', p) for p in segments):
            raise ValueError('Unsafe artifact coordinate: ' + coordinate)
        artifact = Path(item['path'])
        if not artifact.is_file():
            # A transformed directory has no archive bytes to review or attribute.
            raise ValueError('Capture needs a regular artifact file: ' + coordinate + ' ' + artifact.name)
        directory = output / 'notice-inputs' / Path(*segments)
        directory.mkdir(parents=True, exist_ok=True)
        sources = []

        def retain(name, data):
            """Keep bounded inputs under their content digest, without archive path traversal."""
            if len(data) > 16 * 1024 * 1024:
                raise ValueError('Invalid notice input size: ' + name)
            sha = hashlib.sha256(data).hexdigest()
            destination = directory / sha
            destination.write_bytes(data)
            sources.append({'name': name, 'path': str(destination.relative_to(output)),
                            'sha256': sha, 'bytes': len(data)})

        pom_name = segments[1] + '-' + segments[2] + '.pom'
        parents = [artifact.parent, *sorted(artifact.parent.parent.iterdir())]
        for parent in parents:
            pom = parent / pom_name
            if pom.is_file():
                if pom.stat().st_size > 16 * 1024 * 1024:
                    raise ValueError('Oversized POM source: ' + pom_name)
                retain(pom_name, pom.read_bytes())
        if zipfile.is_zipfile(artifact):
            with zipfile.ZipFile(artifact) as archive:
                for entry in sorted(archive.infolist(), key=lambda e: e.filename):
                    basename = entry.filename.rsplit('/', 1)[-1]
                    if (basename in ['third_party_licenses.json', 'third_party_licenses.txt'] or
                            re.fullmatch(r'(?i)(licen[cs]e|notice|copying)([._-].*)?', basename)):
                        if entry.file_size > 16 * 1024 * 1024:
                            raise ValueError('Oversized notice input: ' + entry.filename)
                        retain(entry.filename, archive.read(entry))
        records.append({'coordinate': coordinate, 'name': artifact.name,
                        'sha256': digest(artifact), 'bytes': artifact.stat().st_size,
                        'notice_inputs': sorted(sources, key=lambda s: (s['name'], s['sha256']))})
    (output / 'artifact-evidence.json').write_text(json.dumps(
        {'schema': 1, 'artifacts': records}, indent=2, sort_keys=True) + '\n')
    # Absolute runner/cache paths are transient task inputs, not publication evidence.
    (output / 'artifact-paths.json').unlink()


if __name__ == '__main__':
    capture(Path(__file__).resolve().parent.parent)
