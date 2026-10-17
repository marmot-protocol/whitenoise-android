#!/usr/bin/env python3
"""Cross-check Gradle checksum candidates against independent official HTTPS reads."""
import argparse
import concurrent.futures
import hashlib
import json
from pathlib import Path
import re
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
if __package__:
    from .check_dependency_inputs import validate_verification, unique_object
else:
    from check_dependency_inputs import validate_verification, unique_object

REPOSITORIES = ('https://dl.google.com/dl/android/maven2/',
                'https://repo.maven.apache.org/maven2/', 'https://plugins.gradle.org/m2/')
HOSTS = {'dl.google.com', 'maven.google.com', 'repo.maven.apache.org', 'repo1.maven.org',
         'plugins.gradle.org', 'plugins-artifacts.gradle.org'}


class OfficialRedirects(urllib.request.HTTPRedirectHandler):
    """Keep public supplier downloads on HTTPS and the declared registry infrastructure."""

    def redirect_request(self, request, response, code, message, headers, url):
        """Reject redirects outside official registry hosts before making the next request."""
        parsed = urllib.parse.urlsplit(url)
        if (parsed.scheme != 'https' or parsed.hostname not in HOSTS or parsed.username or
                parsed.password or parsed.port not in [None, 443]):
            raise ValueError('Supplier redirected outside the approved HTTPS registries')
        return super().redirect_request(request, response, code, message, headers, url)


def download(url, destination=None):
    """Fetch bounded public bytes without credentials; retain payloads only when requested."""
    opener = urllib.request.build_opener(OfficialRedirects())
    request = urllib.request.Request(url, headers={'User-Agent': 'WhiteNoise-Dependency-Review/1.0'})
    for attempt in range(3):
        try:
            with opener.open(request, timeout=30) as response:
                digest = hashlib.sha256()
                small = bytearray()
                total = 0
                started = time.monotonic()
                stream = destination.open('wb') if destination else None
                try:
                    while True:
                        chunk = response.read(1024 * 1024)
                        if not chunk:
                            break
                        total += len(chunk)
                        if total > 512 * 1024 * 1024 or time.monotonic() - started > 180:
                            raise ValueError('Supplier response exceeds download bounds')
                        digest.update(chunk)
                        if stream:
                            stream.write(chunk)
                        elif total <= 8192:
                            small.extend(chunk)
                    return digest.hexdigest(), bytes(small), total
                finally:
                    if stream:
                        stream.close()
        except urllib.error.HTTPError as error:
            if error.code in [404, 410]:
                return None
            if error.code not in [429, 500, 502, 503, 504] or attempt == 2:
                raise
        except (urllib.error.URLError, TimeoutError):
            if attempt == 2:
                raise
        time.sleep(attempt + 1)


def verify_one(record, output, retain, module_hashes):
    """Accept a publisher checksum or independently downloaded payload, never a mismatch."""
    coordinate, name, expected = record
    group, module, version = coordinate.split(':')
    segments = [*group.split('.'), module, version, name]
    suffix = '/'.join(urllib.parse.quote(part, safe='-._') for part in segments)
    for repository in REPOSITORIES:
        url = repository + suffix
        publication_evidence = {}
        if not name.startswith(module + '-' + version):
            # Gradle module metadata can publish a display name such as
            # animation-core.aar under a different repository filename.
            module_sha = module_hashes.get(coordinate)
            if module_sha is None:
                raise ValueError('Missing authenticated publication mapping: ' + coordinate + '/' + name)
            publication = '/'.join(urllib.parse.quote(part, safe='-._') for part in
                                   [*group.split('.'), module, version, module + '-' + version + '.module'])
            with tempfile.NamedTemporaryFile(dir=output / 'downloads', prefix='publication-', delete=False) as stream:
                temporary = Path(stream.name)
            try:
                response = download(repository + publication, temporary)
                if response is None:
                    continue
                if response[0] != module_sha or response[2] > 2 * 1024 * 1024:
                    raise ValueError('Publication metadata differs: ' + coordinate)
                metadata = json.loads(temporary.read_text())
                (output / 'downloads' / module_sha).write_bytes(temporary.read_bytes())
            finally:
                temporary.unlink(missing_ok=True)
            locations = set()
            for variant in metadata.get('variants', []):
                for file in variant.get('files', []):
                    if file.get('name') == name:
                        if file.get('sha256') not in [None, expected]:
                            raise ValueError('Publication artifact fingerprint differs: ' + coordinate + '/' + name)
                        location = urllib.parse.urljoin(repository + publication, file['url'])
                        if not location.startswith(repository) or urllib.parse.urlsplit(location).query:
                            raise ValueError('Publication escapes its declared repository')
                        locations.add(location)
            if len(locations) != 1:
                raise ValueError('Ambiguous publication filename: ' + coordinate + '/' + name)
            url = locations.pop()
            publication_evidence = {'publication_metadata': {
                'coordinate': coordinate, 'name': module + '-' + version + '.module',
                'sha256': module_sha, 'path': 'publication-metadata/' + module_sha + '.module'}}
        if not retain:
            checksum = download(url + '.sha256')
            if checksum is not None:
                value = checksum[1].decode('ascii').strip().split()[0].lower()
                if not re.fullmatch(r'[a-f0-9]{64}', value) or value != expected:
                    raise ValueError('Published SHA-256 differs: ' + coordinate + '/' + name)
                return {'coordinate': coordinate, 'name': name, 'sha256': expected,
                        'source': url + '.sha256', 'method': 'published-sha256', **publication_evidence}
        with tempfile.NamedTemporaryFile(dir=output / 'downloads', prefix='candidate-', delete=False) as stream:
            temporary = Path(stream.name)
        try:
            payload = download(url, temporary)
            if payload is None:
                continue
            if payload[0] != expected:
                raise ValueError('Official payload differs: ' + coordinate + '/' + name)
            if retain:
                temporary.replace(output / 'downloads' / expected)
        finally:
            temporary.unlink(missing_ok=True)
        return {'coordinate': coordinate, 'name': name, 'sha256': expected,
                'source': url, 'method': 'independent-payload-sha256', 'bytes': payload[2],
                'retained': retain, **publication_evidence}
    raise ValueError('No declared official supplier: ' + coordinate + '/' + name)


def runtime_identities(path):
    """Consume the capture's schema-tagged artifact evidence, rejecting ambiguous identities."""
    evidence = json.loads(path.read_text(), object_pairs_hook=unique_object)
    if not isinstance(evidence, dict) or evidence.get('schema') != 1 or not isinstance(evidence.get('artifacts'), list):
        raise ValueError('Expected captured {schema: 1, artifacts: [...]} runtime evidence')
    result = set()
    for item in evidence['artifacts']:
        key = (item.get('coordinate'), item.get('name'))
        if (not all(isinstance(value, str) and value for value in key) or key in result or
                not re.fullmatch(r'[a-f0-9]{64}', item.get('sha256', ''))):
            raise ValueError('Malformed or duplicate captured runtime identity')
        result.add(key)
    return result


def main():
    """Audit every candidate identity, retaining runtime payloads for attribution review."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--metadata', required=True, type=Path)
    parser.add_argument('--runtime', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    arguments = parser.parse_args()
    records = [(coordinate, name, sha) for (coordinate, name), sha
               in validate_verification(arguments.metadata).items()]
    module_hashes = {coordinate: sha for coordinate, name, sha in records
                     if name == coordinate.split(':')[1] + '-' + coordinate.split(':')[2] + '.module'}
    retained = runtime_identities(arguments.runtime)
    arguments.output.mkdir(parents=True, exist_ok=True)
    (arguments.output / 'downloads').mkdir(exist_ok=True)
    manifest = arguments.output / 'provenance.json'
    previous = json.loads(manifest.read_text()) if manifest.is_file() else []
    known = {(r['coordinate'], r['name'], r['sha256']): r for r in previous}
    records = [r for r in records if r not in known or
               ((r[0], r[1]) in retained and (not (arguments.output / 'downloads' / r[2]).is_file() or
                hashlib.sha256((arguments.output / 'downloads' / r[2]).read_bytes()).hexdigest() != r[2]))]
    executor = concurrent.futures.ThreadPoolExecutor(max_workers=8)
    try:
        futures = {executor.submit(verify_one, r, arguments.output, (r[0], r[1]) in retained, module_hashes): r
                   for r in records}
        for future in concurrent.futures.as_completed(futures):
            result = future.result()
            known[(result['coordinate'], result['name'], result['sha256'])] = result
            staging = manifest.with_suffix('.tmp')
            staging.write_text(json.dumps(list(known.values()), indent=2, sort_keys=True) + '\n')
            staging.replace(manifest)
    except Exception as error:
        for future in futures:
            future.cancel()
        print(type(error).__name__ + ': ' + str(error), flush=True)
        executor.shutdown(wait=False, cancel_futures=True)
        raise
    else:
        executor.shutdown(wait=True)
    print(f'Independently checked {len(records)} supplier artifact/checksum candidates')


if __name__ == '__main__':
    main()
