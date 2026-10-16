#!/usr/bin/env python3
"""Reject incomplete checksum trust roots and malformed committed Gradle locks."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import urllib.parse
import xml.etree.ElementTree as ET

if __package__:
    from .retain_dependency_evidence import validate_retained
else:
    from retain_dependency_evidence import validate_retained


def validate_verification(path):
    """Require SHA-256 for every exact artifact and metadata, with no trust bypasses."""
    data = path.read_bytes()
    if len(data) > 8 * 1024 * 1024 or re.search(br'<!\s*(DOCTYPE|ENTITY)\b', data, re.IGNORECASE):
        raise ValueError('Unsafe dependency verification XML')
    # Gradle parses each raw text chunk of these scalars, so whitespace, comments or
    # character references that ElementTree would normalize can disable verification.
    if (data.count(b'<configuration') != 1 or not re.search(
            rb'<configuration>\s*<verify-metadata>true</verify-metadata>\s*'
            rb'<verify-signatures>false</verify-signatures>\s*</configuration>', data)):
        raise ValueError('Dependency metadata must be verified with the reviewed checksum policy')
    # Gradle matches unprefixed element names only, so a prefixed record is invisible to it.
    if re.search(rb'</?[A-Za-z_][\w.-]*:', data):
        raise ValueError('Unexpected verification XML namespace')
    root = ET.fromstring(data)
    namespace = '{https://schema.gradle.org/dependency-verification}' if root.tag.startswith('{') else ''
    if root.tag != namespace + 'verification-metadata':
        raise ValueError('Unsupported dependency verification root')
    for node in root.iter():
        if namespace and not node.tag.startswith(namespace):
            raise ValueError('Unexpected verification XML namespace')
        node.tag = node.tag[len(namespace):]
    allowed_root_attributes = {'{http://www.w3.org/2001/XMLSchema-instance}schemaLocation'}
    if (set(root.attrib) - allowed_root_attributes or
            [node.tag for node in root] != ['configuration', 'components'] or (root.text or '').strip() or
            any((node.tail or '').strip() for node in root)):
        raise ValueError('Unexpected or duplicate verification section')
    config, components = list(root)
    if (config.attrib or [node.tag for node in config] != ['verify-metadata', 'verify-signatures'] or
            any(node.attrib or len(node) for node in config) or (config.text or '').strip() or
            any((node.tail or '').strip() for node in config)):
        raise ValueError('Unreviewed verification bypass or duplicate configuration entry')
    if config[0].text != 'true' or config[1].text != 'false':
        raise ValueError('Dependency metadata must be verified with the reviewed checksum policy')
    if components.attrib or not len(components) or (components.text or '').strip():
        raise ValueError('Missing dependency checksums')
    observed = set()
    records = {}
    for component in components:
        identity = tuple(component.get(k, '') for k in ['group', 'name', 'version'])
        if (component.tag != 'component' or set(component.attrib) != {'group', 'name', 'version'} or
                (component.text or '').strip() or (component.tail or '').strip() or identity in observed or
                not all(re.fullmatch(r'[A-Za-z0-9_.+-]+', part) for part in identity)):
            raise ValueError('Invalid or duplicate verification component')
        observed.add(identity)
        names = set()
        for artifact in component:
            name = artifact.get('name', '')
            checksums = list(artifact)
            if (artifact.tag != 'artifact' or set(artifact.attrib) != {'name'} or name in names or
                    (artifact.text or '').strip() or (artifact.tail or '').strip() or
                    not re.fullmatch(r'[A-Za-z0-9_.+-]+', name) or
                    len(checksums) != 1 or checksums[0].tag != 'sha256' or len(checksums[0]) or
                    set(checksums[0].attrib) - {'value', 'origin'} or
                    (checksums[0].text or '').strip() or (checksums[0].tail or '').strip() or
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
# Each supplier's Maven repository root. A host without a fixed root serves only URLs that authenticated
# publication metadata names.
SUPPLIER_ROOTS = {'dl.google.com': '/dl/android/maven2', 'maven.google.com': '', 'repo.maven.apache.org': '/maven2',
                  'repo1.maven.org': '/maven2', 'plugins.gradle.org': '/m2', 'plugins-artifacts.gradle.org': None}
SUPPLIER_HOSTS = set(SUPPLIER_ROOTS)


def unique_object(pairs):
    """Reject duplicate manifest identities before comparing reviewed evidence."""
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError('Duplicate dependency evidence key')
        value[key] = item
    return value



def payload_url(source):
    """Resolve the artifact URL that a published .sha256 file or a payload read covers."""
    return source[:-len('.sha256')] if source.endswith('.sha256') else source


def is_standard_supplier_path(coordinate, name, source):
    """Whether the URL is the registry's exact coordinate path to this artifact's canonical file.

    Gradle shows some module-metadata artifacts under a display name, such as
    benchmark-traceprocessor.aar or datastore-release.aar for the multiplatform modules
    benchmark-traceprocessor-android and datastore-android. That file is still the
    coordinate's canonical one, so it needs no retained module metadata.
    """
    group, module, version = coordinate.split(':')
    url = urllib.parse.urlsplit(payload_url(source))
    registry = SUPPLIER_ROOTS.get(url.hostname)
    directory, _, filename = urllib.parse.unquote(url.path).rpartition('/')
    if registry is None or directory != '/'.join([registry, *group.split('.'), module, version]):
        return False
    stem, dot, extension = name.rpartition('.')
    base = stem.removesuffix('-release')
    display_name = bool(dot) and (base == module or module.startswith(base + '-'))
    return filename == name or display_name and filename == f'{module}-{version}.{extension}'


def validate_publication_mapping(coordinate, name, sha, source, records, suppliers, root):
    """Rederive an artifact URL outside its coordinate path from retained authenticated module bytes."""
    group, module, version = coordinate.split(':')
    module_name = module + '-' + version + '.module'
    module_sha = records.get((coordinate, module_name))
    module_source = suppliers.get(coordinate + '/' + module_name)
    if module_sha is None or module_source is None:
        raise ValueError('Artifact supplier refers to another publication without authenticated mapping')
    path = root / 'gradle/publication-metadata' / (module_sha + '.module')
    if (not path.is_file() or path.is_symlink() or path.stat().st_size > 2 * 1024 * 1024 or
            not path.resolve().is_relative_to((root / 'gradle').resolve())):
        raise ValueError('Artifact supplier refers to another publication without authenticated mapping')
    data = path.read_bytes()
    if hashlib.sha256(data).hexdigest() != module_sha:
        raise ValueError('Changed or truncated publication metadata source')
    metadata = json.loads(data.decode('utf-8'), object_pairs_hook=unique_object)
    locations = set()
    for variant in metadata.get('variants', []):
        for file in variant.get('files', []):
            if file.get('name') == name:
                if file.get('sha256') not in [None, sha]:
                    raise ValueError('Publication metadata artifact digest differs')
                locations.add(urllib.parse.urljoin(payload_url(module_source), file['url']))
    if locations != {payload_url(source)}:
        raise ValueError('Artifact supplier URL is not authenticated by publication metadata')


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
    validate_retained(root, source.get('retained', {}), source['source_sha'])
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
    observed = set()
    # Each row is "<project> <project|plugin> <configuration>", for example ":app plugin classpath".
    for row in manifest.get('coverage', []):
        identity = tuple(row.split(' ')) if isinstance(row, str) else ()
        if len(identity) != 3 or identity in observed or identity[0] not in [':', ':app', ':benchmark',
                                                                          ':cryptoBenchmark', ':fuzz']:
            raise ValueError('Duplicate or unknown configuration coverage')
        observed.add(identity)
        module, scope, configuration = identity
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
    # Each supplier maps "<coordinate>/<artifact name>" to the official URL that confirmed its
    # SHA-256: a published .sha256 file, or the payload itself downloaded and hashed.
    suppliers = manifest.get('suppliers', {})
    if not isinstance(suppliers, dict):
        raise ValueError('Unreviewed or ambiguous artifact supplier evidence')
    for key, source in suppliers.items():
        coordinate, _, name = key.partition('/')
        url = urllib.parse.urlsplit(source if isinstance(source, str) else '')
        if ((coordinate, name) not in records or url.scheme != 'https' or url.hostname not in SUPPLIER_HOSTS or
                url.username or url.password or url.port not in [None, 443] or url.query or url.fragment):
            raise ValueError('Unreviewed or ambiguous artifact supplier evidence')
        # Any other path, including another directory or a different filename, must be named by the
        # coordinate's own authenticated publication metadata.
        if not is_standard_supplier_path(coordinate, name, source):
            validate_publication_mapping(coordinate, name, records[(coordinate, name)], source, records, suppliers, root)
    if {tuple(key.split('/', 1)) for key in suppliers} != set(records):
        raise ValueError('Missing independent artifact supplier checks')
    validate_exceptions(manifest, records, root, actual_locks)
    return len(locks), len(records)


def validate_exceptions(manifest, records, root, lock_names):
    """Tie each reviewed post-lock exception to committed locks, checksums and evidence."""
    exceptions = manifest.get('post_lock_selection', [])
    if not isinstance(exceptions, list) or not isinstance(manifest.get('supplemental_tools', {}), dict):
        raise ValueError('Malformed reviewed dependency exceptions')
    locked = {line.split('=', 1)[0] for name in lock_names
              for line in (root / name).read_text(encoding='utf-8').splitlines()
              if '=' in line and not line.startswith(('#', 'empty='))}
    for item in exceptions:
        if (not isinstance(item, dict) or item.get('scope') not in ['project', 'plugin', 'metadata'] or
                not isinstance(item.get('reason'), str) or not item['reason'].strip() or
                not str(item.get('evidence', '')).startswith('https://github.com/' + REPOSITORY + '/actions/runs/')):
            raise ValueError('Reviewed dependency exception lacks scope, reason or evidence')
        if any(coordinate not in locked for coordinate in item.get('additional_coordinates', [])):
            raise ValueError('Dependency exception names a coordinate absent from committed locks')
        if any(tuple(entry.split('/', 1)) not in records for entry in item.get('additional_metadata', [])):
            raise ValueError('Dependency exception names metadata without a checksum record')
        if 'retained' in item:
            # Hosted evidence expires; a retained copy keeps the exception auditable.
            relative = item['retained'].get('path', '')
            path = root / relative
            if (not relative.startswith('gradle/dependency-evidence/') or '..' in Path(relative).parts or
                    not path.is_file() or path.is_symlink() or
                    hashlib.sha256(path.read_bytes()).hexdigest() != item['retained'].get('sha256')):
                raise ValueError('Missing or changed retained dependency exception evidence')


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
