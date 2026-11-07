#!/usr/bin/env python3
"""Hash Gradle-resolved SDK inputs, excluding unrelated installed SDK packages."""
import hashlib
import json
from pathlib import Path
import sys


def digest(path):
    """Hash complete toolchain bytes without loading large compiler binaries into memory."""
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()


def record(value, apksigner):
    """Require resolved package identities and retain every selected package file and link."""
    if set(value) != {'schema', 'sdk', 'bootClasspath', 'buildToolsVersion', 'ndk', 'cmakeVersion'} or value['schema'] != 1:
        raise ValueError('Complete Gradle-resolved SDK report required')
    sdk = Path(value['sdk']).resolve(strict=True)
    if not sdk.is_dir() or not isinstance(value['bootClasspath'], list) or not value['bootClasspath']:
        raise ValueError('Resolved SDK and nonempty boot classpath required')
    packages = set()

    def package(path, category):
        """Accept an existing SDK package of the expected category, never a foreign toolchain."""
        path = Path(path).resolve(strict=True)
        parts = path.relative_to(sdk).parts
        if len(parts) != 2 or parts[0] != category or not path.is_dir() or not (path / 'source.properties').is_file():
            raise ValueError('Exact resolved SDK package required: ' + category)
        packages.add(path)
        return path

    boot = []
    for name in value['bootClasspath']:
        path = Path(name).resolve(strict=True)
        parts = path.relative_to(sdk).parts
        if len(parts) < 3 or parts[0] not in ('platforms', 'build-tools') or not path.is_file():
            raise ValueError('Resolved boot classpath must belong to selected SDK packages')
        package(sdk.joinpath(*parts[:2]), parts[0])
        boot.append({'path': path.relative_to(sdk).as_posix(), 'sha256': digest(path)})
    if not any(row['path'].startswith('platforms/') and row['path'].endswith('/android.jar') for row in boot):
        raise ValueError('Resolved Android compile platform required')
    package(sdk / 'build-tools' / value['buildToolsVersion'], 'build-tools')
    package(value['ndk'], 'ndk')
    signer = Path(apksigner).resolve(strict=True)
    if signer.name != 'apksigner' or not signer.is_file() or not signer.stat().st_mode & 0o111:
        raise ValueError('Actual executable APK verifier required')
    package(signer.parent, 'build-tools')
    if value['cmakeVersion'] is not None:
        package(sdk / 'cmake' / value['cmakeVersion'], 'cmake')
    files = []
    for root in sorted(packages):
        for path in sorted(root.rglob('*')):
            if path.is_symlink():
                target = path.resolve(strict=True)
                target.relative_to(root)
                files.append({'path': path.relative_to(sdk).as_posix(), 'link': str(path.readlink())})
            elif path.is_file():
                files.append({'path': path.relative_to(sdk).as_posix(), 'sha256': digest(path),
                              'executable': bool(path.stat().st_mode & 0o111)})
            elif not path.is_dir():
                raise ValueError('Special SDK package input refused')
    return {'schema': 1, 'bootClasspath': boot, 'apksigner': signer.relative_to(sdk).as_posix(),
            'cmakeConfigured': value['cmakeVersion'] is not None,
            'packages': [path.relative_to(sdk).as_posix() for path in sorted(packages)], 'files': files}


if __name__ == '__main__':
    if len(sys.argv) != 3:
        raise SystemExit('Usage: repro_sdk_inputs.py GRADLE_SDK_REPORT ACTUAL_APKSIGNER')
    print(json.dumps(record(json.loads(Path(sys.argv[1]).read_text()), sys.argv[2]), sort_keys=True, indent=2))
