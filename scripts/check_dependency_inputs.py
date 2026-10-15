#!/usr/bin/env python3
"""Reject incomplete checksum trust roots and malformed committed Gradle locks."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import urllib.parse
import xml.etree.ElementTree as ET


def validate_verification(path):
    """Require SHA-256 for every exact artifact and metadata, with no trust bypasses."""
    data = path.read_bytes()
    if len(data) > 8 * 1024 * 1024 or re.search(br'<!\s*(DOCTYPE|ENTITY)\b', data, re.IGNORECASE):
        raise ValueError('Unsafe dependency verification XML')
    root = ET.fromstring(data)
    for node in root.iter():
        node.tag = node.tag.rsplit('}', 1)[-1]
    if root.tag != 'verification-metadata':
        raise ValueError('Unsupported dependency verification root')
    config = root.find('configuration')
    if config is None or config.findtext('verify-metadata', '').strip() != 'true':
        raise ValueError('Dependency metadata must be verified')
    for forbidden in ['trusted-artifacts', 'ignored-keys', 'trusted-keys']:
        section = config.find(forbidden)
        if section is not None and len(section):
            raise ValueError('Unreviewed verification bypass: ' + forbidden)
    components = root.find('components')
    if components is None or not len(components):
        raise ValueError('Missing dependency checksums')
    observed = set()
    records = {}
    for component in components:
        identity = tuple(component.get(k, '') for k in ['group', 'name', 'version'])
        if (component.tag != 'component' or identity in observed or
                not all(re.fullmatch(r'[A-Za-z0-9_.+-]+', part) for part in identity)):
            raise ValueError('Invalid or duplicate verification component')
        observed.add(identity)
        names = set()
        for artifact in component:
            name = artifact.get('name', '')
            checksums = list(artifact)
            if (artifact.tag != 'artifact' or name in names or
                    not re.fullmatch(r'[A-Za-z0-9_.+-]+', name) or
                    len(checksums) != 1 or checksums[0].tag != 'sha256' or len(checksums[0]) or
                    not re.fullmatch(r'[a-f0-9]{64}', checksums[0].get('value', ''))):
                raise ValueError('Missing or ambiguous SHA-256: ' + str(identity) + ':' + name)
            names.add(name)
            records[(':'.join(identity), name)] = checksums[0].get('value')
        if not names:
            raise ValueError('Empty verification component')
    return records


def validate_lock(path):
    """Validate exact module selections and unambiguous per-configuration lock records."""
    if path.stat().st_size > 8 * 1024 * 1024:
        raise ValueError('Oversized dependency lock')
    observed = set()
    empty_seen = False
    configurations_seen = set()
    empty_configurations = set()
    for line in path.read_text(encoding='utf-8').splitlines():
        if not line or line.startswith('#'):
            continue
        if line.startswith('empty='):
            if empty_seen:
                raise ValueError('Duplicate empty-configuration lock record')
            empty_seen = True
            values = line[len('empty='):].split(',') if line[len('empty='):] else []
            if len(values) != len(set(values)) or any(not re.fullmatch(r'[A-Za-z0-9_.:-]+', c) for c in values):
                raise ValueError('Invalid empty-configuration lock record')
            empty_configurations.update(values)
            continue
        coordinate, separator, configuration = line.partition('=')
        segments = coordinate.split(':')
        if (not separator or len(segments) != 3 or coordinate in observed or not configuration or
                not all(re.fullmatch(r'[A-Za-z0-9_.-]+', part) for part in segments) or
                segments[2].endswith('-SNAPSHOT') or segments[2].startswith('latest.')):
            raise ValueError('Invalid or mutable dependency lock: ' + coordinate)
        configurations = configuration.split(',')
        if len(set(configurations)) != len(configurations) or any(not c for c in configurations):
            raise ValueError('Ambiguous locked configurations: ' + coordinate)
        if any(not re.fullmatch(r'[A-Za-z0-9_.:-]+', c) for c in configurations):
            raise ValueError('Invalid locked configuration name')
        configurations_seen.update(configurations)
        observed.add(coordinate)
    if not empty_seen:
        raise ValueError('Incomplete Gradle lock format')
    if empty_configurations & configurations_seen:
        raise ValueError('Configuration is both empty and nonempty')
    return configurations_seen | empty_configurations


REPOSITORY = 'marmot-protocol/whitenoise-android'
REQUIRED_LOCKS = {'buildscript-gradle.lockfile', 'settings-gradle.lockfile'} | {
    module + '/' + name for module in ['app', 'benchmark', 'cryptoBenchmark', 'fuzz']
    for name in ['gradle.lockfile', 'buildscript-gradle.lockfile']}
SUPPLIER_HOSTS = {'dl.google.com', 'maven.google.com', 'repo.maven.apache.org', 'repo1.maven.org',
                  'plugins.gradle.org', 'plugins-artifacts.gradle.org'}


def unique_object(pairs):
    """Reject duplicate manifest identities before comparing reviewed evidence."""
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError('Duplicate dependency evidence key')
        value[key] = item
    return value


def validate_bundle(root):
    """Bind complete lock/checksum coverage to independently checked supplier records."""
    manifest_path = root / 'gradle/dependency-inputs.json'
    if manifest_path.stat().st_size > 8 * 1024 * 1024:
        raise ValueError('Oversized dependency evidence')
    manifest = json.loads(manifest_path.read_text(), object_pairs_hook=unique_object)
    source = manifest.get('reviewed_from', {})
    if (type(manifest.get('schema')) is not int or manifest['schema'] != 1 or
            source.get('repository') != REPOSITORY or
            not re.fullmatch(r'[a-f0-9]{40}', source.get('source_sha', '')) or
            not re.fullmatch(r'https://github.com/' + REPOSITORY +
                             r'/actions/runs/[0-9]+/artifacts/[0-9]+', source.get('evidence', ''))):
        raise ValueError('Missing or wrong-source dependency evidence')
    files = manifest.get('files', {})
    allowed_locks = REQUIRED_LOCKS | {'gradle.lockfile', 'settings-gradle.lockfile', 'settings-buildscript-gradle.lockfile'}
    if (not REQUIRED_LOCKS <= set(files) or 'gradle/verification-metadata.xml' not in files or
            set(files) - allowed_locks - {'gradle/verification-metadata.xml'}):
        raise ValueError('Missing or unexpected reviewed lock/checksum files')
    for relative, expected in files.items():
        path = root / relative
        if (not re.fullmatch(r'[a-f0-9]{64}', expected) or not path.is_file() or path.is_symlink() or
                hashlib.sha256(path.read_bytes()).hexdigest() != expected):
            raise ValueError('Stale or changed dependency evidence: ' + relative)
    actual_locks = {str(p.relative_to(root)) for p in root.glob('*gradle.lockfile')}
    for module in ['app', 'benchmark', 'cryptoBenchmark', 'fuzz']:
        actual_locks.update(str(p.relative_to(root)) for p in (root / module).glob('*gradle.lockfile'))
    if actual_locks != set(files) - {'gradle/verification-metadata.xml'}:
        raise ValueError('Unreviewed or missing committed locks')
    locks = {name: validate_lock(root / name) for name in actual_locks}
    if not locks['settings-gradle.lockfile']:
        raise ValueError('Missing settings dependency-management lock state')
    coverage = manifest.get('coverage', [])
    observed = set()
    for row in coverage:
        module, scope, configuration = (row.get(k, '') for k in ['project', 'scope', 'configuration'])
        identity = (module, scope, configuration)
        if identity in observed or module not in [':', ':app', ':benchmark', ':cryptoBenchmark', ':fuzz']:
            raise ValueError('Duplicate or unknown configuration coverage')
        observed.add(identity)
        if scope not in ['project', 'plugin'] or not configuration:
            raise ValueError('Invalid configuration coverage')
        relative = (module[1:] + '/' if module != ':' else '')
        relative += 'buildscript-gradle.lockfile' if scope == 'plugin' else 'gradle.lockfile'
        if configuration not in locks.get(relative, set()):
            raise ValueError('Missing locked configuration: ' + str(identity))
    required = {(':app', 'project', variant + suffix)
                for variant in ['devPlayDebug', 'devZapstoreDebug', 'productionPlayRelease',
                                'productionZapstoreRelease', 'stagingPlayRelease', 'stagingZapstoreRelease',
                                'previewPlayRelease', 'previewZapstoreRelease', 'previewPlayDebug',
                                'previewZapstoreDebug']
                for suffix in ['CompileClasspath', 'RuntimeClasspath']}
    required.update((module, 'plugin', 'classpath') for module in [':', ':app', ':benchmark', ':cryptoBenchmark', ':fuzz'])
    if not required <= observed:
        raise ValueError('Incomplete application/plugin configuration coverage')
    records = validate_verification(root / 'gradle/verification-metadata.xml')
    suppliers = {}
    for item in manifest.get('suppliers', []):
        key = (item.get('coordinate'), item.get('name'))
        url = urllib.parse.urlsplit(item.get('source', ''))
        if (key in suppliers or key not in records or item.get('sha256') != records[key] or
                url.scheme != 'https' or url.hostname not in SUPPLIER_HOSTS or url.username or url.password or
                url.port not in [None, 443] or url.query or url.fragment or
                item.get('method') not in ['published-sha256', 'independent-payload-sha256']):
            raise ValueError('Unreviewed or ambiguous artifact supplier evidence')
        group, module, version = item['coordinate'].split(':')
        publication = '/' + '/'.join([*group.split('.'), module, version]) + '/'
        if publication not in urllib.parse.unquote(url.path):
            # Authenticated Gradle module metadata can deliberately publish a
            # selected capability using another version's archive (notably Guava).
            mapping = item.get('publication_metadata', {})
            metadata_key = (item['coordinate'], module + '-' + version + '.module')
            if (mapping.get('coordinate') != metadata_key[0] or mapping.get('name') != metadata_key[1] or
                    metadata_key not in records or mapping.get('sha256') != records[metadata_key]):
                raise ValueError('Artifact supplier refers to another publication without authenticated mapping')
        suppliers[key] = item
    if set(suppliers) != set(records):
        raise ValueError('Missing independent artifact supplier checks')
    return len(locks), len(records)


def main():
    """Check committed evidence without resolving dependencies or accessing the network."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parent.parent)
    arguments = parser.parse_args()
    root = arguments.root
    locks, records = validate_bundle(root)
    print(f'Validated {locks} lock files and {records} independently checked SHA-256 artifact records')


if __name__ == '__main__':
    main()
