#!/usr/bin/env python3
"""Publish one validated CI APK; immutable release markers guard uncertain PUTs."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import urllib.error
import urllib.request

SERVER = 'https://nostr.download'
TAG = 'android-pr-previews'
MAX_SIZE = 200 * 1024 * 1024


def readback(url, digest, size):
    try:
        with urllib.request.urlopen(url, timeout=120) as response:
            if response.geturl() != url:
                raise RuntimeError('Blossom readback redirected')
            total, actual = 0, hashlib.sha256()
            while chunk := response.read(1024 * 1024):
                total += len(chunk)
                if total > size:
                    raise RuntimeError('Blossom readback exceeds APK size')
                actual.update(chunk)
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return False
        raise
    if total != size or actual.hexdigest() != digest:
        raise RuntimeError('Blossom readback differs from validated APK')
    return True


def gh(*args):
    return subprocess.run(['gh', *args], check=True, capture_output=True,
                          text=True, timeout=120).stdout


def marker_exists(repository, marker):
    release = json.loads(gh('api', f'repos/{repository}/releases/tags/{TAG}'))
    for page in range(1, 21):
        assets = json.loads(gh('api', f'repos/{repository}/releases/{release["id"]}/assets?per_page=100&page={page}'))
        if any(asset['name'] == marker for asset in assets):
            return True
        if len(assets) < 100:
            return False
    raise RuntimeError('Release marker inventory incomplete')


def publish(apk, repository):
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository):
        raise ValueError('Invalid repository')
    size = apk.stat().st_size
    if not 0 < size <= MAX_SIZE:
        raise ValueError('APK size outside bound')
    with apk.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    url = f'{SERVER}/{digest}.apk'
    # A GET failure or integrity mismatch is never permission to upload.
    if readback(url, digest, size):
        return url
    secret = os.environ.get('NOSTR_SECRET_KEY')
    if not secret:
        raise RuntimeError('Existing Blossom signing identity required')
    marker = f'blossom-intent-{digest}.json'
    if marker_exists(repository, marker):
        raise RuntimeError('Prior Blossom upload unresolved or copy removed; reconcile before retrying')
    with tempfile.TemporaryDirectory() as directory:
        intent = Path(directory) / marker
        intent.write_text(json.dumps({'schema': 1, 'action': 'one_primary_upload',
                                      'sha256': digest, 'size': size, 'url': url,
                                      'run_id': os.environ.get('GITHUB_RUN_ID')}))
        # No --clobber: creation arbitrates concurrent attempts for these bytes.
        # A failed/unknown marker write stops before any blob upload.
        gh('release', 'upload', TAG, str(intent), '--repo', repository)
        environment = {'PATH': os.environ['PATH'], 'HOME': directory,
                       'NOSTR_SECRET_KEY': secret}
        try:
            subprocess.run(['nak', 'blossom', '--server', SERVER,
                                     'upload', str(apk.resolve())], env=environment,
                                    stdin=subprocess.DEVNULL, capture_output=True,
                                    timeout=240, check=False)
        except subprocess.TimeoutExpired:
            pass
        # Preserve the immutable intent even for a timeout/nonzero exit. A future
        # invocation may only adopt a matching readback, never repeat the PUT.
        if not readback(url, digest, size):
            raise RuntimeError('Blossom outcome unresolved; immutable intent retained')
    return url


if __name__ == '__main__':
    try:
        print(publish(Path(os.environ['APK_PATH']), os.environ['GITHUB_REPOSITORY']))
    except Exception as error:
        # Never echo provider output, auth events or credential-bearing errors.
        raise SystemExit(f'Blossom publication held ({type(error).__name__}); inspect intent and readback') from None
