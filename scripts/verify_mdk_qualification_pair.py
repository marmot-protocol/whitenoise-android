"""Bind hosted upgrade evidence to APK identity, signer and the verified published native bytes."""

import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

if __package__:
    from .verify_apk_signature import verify_certificates
else:
    from verify_apk_signature import verify_certificates

APP = 'dev.ipf.whitenoise.android.mdkqualification'


def lock(path):
    """Read existing complete lock fields without inventing or changing artifact metadata."""
    return dict(line.split('=', 1) for line in path.read_text().splitlines() if line and not line.startswith('#'))


def package_identity(badging, kind):
    """Require the exact package; only app APKs need a version for the no-downgrade comparison."""
    package = re.search(r"^package: name='([^']+)'", badging, re.MULTILINE)
    version = re.search(r"^package: .*?versionCode='([^']*)'", badging, re.MULTILINE)
    expected = APP + ('.test' if kind == 'test' else '')
    if package is None or package.group(1) != expected:
        raise ValueError('Unexpected fixture package')
    value = version.group(1) if version else ''
    if (value and not value.isdecimal()) or (kind == 'app' and not value):
        raise ValueError('App version must be numeric; test APKs may omit it')
    return expected, value or None


def fixture_certificate(output):
    """Use the release verifier for numbered, SDK-range and scheme-specific signer output."""
    candidates = re.findall(r'certificate SHA-256 digest: ([0-9a-fA-F]{64})$', output, re.MULTILINE)
    if not candidates:
        raise ValueError('No fixture signing certificate was reported')
    return verify_certificates(output, candidates[0].lower())


def main():
    """Reject any package, signer, version or native-library mismatch before installing either generation."""
    reports = Path('build/mdk-qualification')
    sdk = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', '')))
    build_tools = sorted((sdk / 'build-tools').iterdir())[-1]
    rows = []
    for role in ('baseline', 'candidate'):
        pin = lock(reports / f'{role}-MARMOT_VERSION')
        artifact = Path(os.environ['WHITENOISE_MARMOTKIT_CACHE_DIR']) / pin['artifact-sha256'] / pin['archive-root']
        elf = json.loads((artifact / 'android-elf.json').read_text())
        expected_native = elf['libraries']['jniLibs/x86_64/libmarmot_uniffi.so']['sha256']
        for kind in ('app', 'test'):
            apk = Path('build/mdk-pair') / role / f'{kind}.apk'
            certificate = subprocess.check_output([str(build_tools / 'apksigner'), 'verify', '--print-certs', str(apk)], text=True)
            digest = fixture_certificate(certificate)
            badging = subprocess.check_output([str(build_tools / 'aapt2'), 'dump', 'badging', str(apk)], text=True)
            package, version = package_identity(badging, kind)
            row = {'role': role, 'kind': kind, 'package': package, 'versionCode': version,
                   'apk_sha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'signer_sha256': digest}
            if kind == 'app':
                with zipfile.ZipFile(apk) as archive:
                    native = hashlib.sha256(archive.read('lib/x86_64/libmarmot_uniffi.so')).hexdigest()
                if native != expected_native:
                    raise ValueError('Packaged native bytes differ from the verified published artifact')
                row.update(mdk_source=pin['mdk-sha'], native_sha256=native, abi='x86_64')
            rows.append(row)
    if len({row['signer_sha256'] for row in rows}) != 1:
        raise ValueError('All app and instrumentation generations must share the disposable signer')
    apps = [row for row in rows if row['kind'] == 'app']
    if int(apps[1]['versionCode']) < int(apps[0]['versionCode']):
        raise ValueError('Candidate must not require a downgrade')
    (reports / 'apk-provenance.json').write_text(json.dumps(rows, indent=2) + '\n')


if __name__ == '__main__':
    main()
