"""Acquire one checksum-pinned external injector on GitHub; never replace or rebuild MarmotKit."""

import hashlib
import lzma
import os
from pathlib import Path
import urllib.request

VERSION = '17.5.1'
SERVER_SHA256 = '19a89ad75c341abffb089b9f68e2cc0c340b22d20503f5a0f9c1737f580344df'


def main():
    """Retain the published server bytes and reject non-hosted use or a changed upstream asset."""
    if os.environ.get('GITHUB_ACTIONS') != 'true':
        raise RuntimeError('Hosted disposable qualification only')
    url = f'https://github.com/frida/frida/releases/download/{VERSION}/frida-server-{VERSION}-android-x86_64.xz'
    with urllib.request.urlopen(url, timeout=120) as response:
        data = response.read(40 * 1024 * 1024)
    if hashlib.sha256(data).hexdigest() != SERVER_SHA256:
        raise RuntimeError('Frida server release checksum mismatch')
    output = Path(os.environ['RUNNER_TEMP']) / 'mdk-frida-server'
    output.write_bytes(lzma.decompress(data))
    output.chmod(0o700)


if __name__ == '__main__':
    main()
