#!/usr/bin/env python3
"""Audit exact POM/module counterparts, inherited parents and imported BOM metadata."""
import argparse
import concurrent.futures
import hashlib
import json
from pathlib import Path
import re
import tempfile
import urllib.parse
import xml.etree.ElementTree as ET

if __package__:
    from . import audit_dependency_candidates as audit
    from .check_dependency_inputs import validate_verification
else:
    import audit_dependency_candidates as audit
    from check_dependency_inputs import validate_verification


def expand(value, properties):
    """Expand only bounded literal Maven properties; never guess a version selector."""
    for _ in range(8):
        updated = re.sub(r'\$\{([^}]+)\}', lambda match: properties.get(match[1], match[0]), value or '')
        if updated == value:
            return value
        value = updated
    return value


def ancestors(poms):
    """Resolve parent/BOM references using the independently checked inherited property chain."""
    def properties(coordinate, seen=()):
        """Follow a bounded, cycle-checked chain without interpreting Maven build code."""
        if coordinate in seen or len(seen) >= 12:
            raise ValueError('Cyclic or oversized Maven parent ancestry')
        root = poms[coordinate]
        group, module, version = coordinate.split(':')
        local = {node.tag: node.text or '' for node in root.findall('./properties/*')}
        result = {}
        parent = root.find('parent')
        if parent is not None:
            target = ':'.join(expand(parent.findtext(key, ''), local) for key in ['groupId', 'artifactId', 'version'])
            if target in poms:
                result.update(properties(target, (*seen, coordinate)))
            result.update({'project.parent.groupId': parent.findtext('groupId', ''),
                           'project.parent.version': parent.findtext('version', '')})
        result.update(local)
        for prefix in ['project', 'pom']:
            result.update({prefix + '.groupId': group, prefix + '.artifactId': module, prefix + '.version': version})
        return result
    references = set()
    unresolved = set()
    for coordinate, root in poms.items():
        values = properties(coordinate)
        nodes = [root.find('parent')] + [node for node in root.findall('./dependencyManagement/dependencies/dependency')
                 if node.findtext('scope') == 'import' and node.findtext('type') == 'pom']
        for node in nodes:
            if node is None:
                continue
            parts = [expand(node.findtext(key, ''), values) for key in ['groupId', 'artifactId', 'version']]
            if not all(re.fullmatch(r'[A-Za-z0-9_.+-]+', part or '') for part in parts):
                unresolved.add((coordinate, ':'.join(parts)))
            else:
                references.add(':'.join(parts))
    return references, unresolved


def audit_metadata(metadata, review):
    """Cross-check a bounded metadata closure, retaining exact bytes and supplemental provenance."""
    records = validate_verification(metadata)
    checked = json.loads((review / 'provenance.json').read_text())
    known = {(item['coordinate'], item['name']): item for item in checked}
    cache = review / 'downloads'
    cache.mkdir(exist_ok=True)
    additions = {}
    poms = {}
    attempted = set()
    seeds = {coordinate for coordinate, _ in records}
    pending = set(seeds)
    checkpoint = review / 'metadata-closure.json'
    def fetch(coordinate, extension):
        """Recheck exact reviewed digests; newly found metadata needs an independent supplier read."""
        group, module, version = coordinate.split(':')
        name = module + '-' + version + '.' + extension
        expected = records.get((coordinate, name))
        prior = known.get((coordinate, name))
        if prior and expected and prior['sha256'] != expected:
            raise ValueError('Supplier audit differs from candidate metadata')
        if prior:
            path = cache / prior['sha256']
            if path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest() == prior['sha256']:
                return prior, path.read_bytes()
        suffix = '/'.join(urllib.parse.quote(part, safe='-._') for part in [*group.split('.'), module, version, name])
        for repository in audit.REPOSITORIES:
            url = repository + suffix
            with tempfile.NamedTemporaryFile(dir=cache, delete=False) as stream:
                temporary = Path(stream.name)
            try:
                response = audit.download(url, temporary)
                if response is None:
                    continue
                if response[2] > 2 * 1024 * 1024 or (expected and response[0] != expected):
                    raise ValueError('Changed or oversized supplier metadata: ' + coordinate)
                checksum = audit.download(url + '.sha256')
                if checksum and checksum[1].decode('ascii').strip().split()[0].lower() != response[0]:
                    raise ValueError('Official metadata checksum differs')
                data = temporary.read_bytes()
                temporary.replace(cache / response[0])
                return {'coordinate': coordinate, 'name': name, 'sha256': response[0], 'source': url,
                        'method': 'independent-payload-sha256'}, data
            finally:
                temporary.unlink(missing_ok=True)
        if expected:
            raise ValueError('Reviewed metadata disappeared: ' + coordinate + '/' + name)
        return None, None
    for _ in range(12):
        if len(seeds) > 1200:
            raise ValueError('Metadata ancestry exceeds review bounds')
        jobs = [(coordinate, extension) for coordinate in sorted(pending) for extension in ['pom', 'module']
                if (coordinate, extension) not in attempted]
        if not jobs:
            break
        attempted.update(jobs)
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as executor:
            futures = {executor.submit(fetch, *job): job for job in jobs}
            for future in concurrent.futures.as_completed(futures):
                coordinate, extension = futures[future]
                item, data = future.result()
                if item is None:
                    continue
                if (coordinate, item['name']) not in records:
                    additions[(coordinate, item['name'])] = item
                if extension == 'pom':
                    if re.search(br'<!\s*(DOCTYPE|ENTITY)\b', data, re.I):
                        raise ValueError('Unsafe supplier POM')
                    root = ET.fromstring(data)
                    for node in root.iter():
                        node.tag = node.tag.rsplit('}', 1)[-1]
                    poms[coordinate] = root
        references, unresolved = ancestors(poms)
        missing = {coordinate for coordinate in references
                   if (coordinate, 'pom') in attempted and coordinate not in poms}
        if missing:
            raise ValueError('Missing referenced parent/BOM POM: ' + ', '.join(sorted(missing)))
        pending = references - seeds
        seeds.update(pending)
        checkpoint.write_text(json.dumps(sorted(additions.values(), key=lambda item: (item['coordinate'], item['name'])),
                                         indent=2, sort_keys=True) + '\n')
    else:
        raise ValueError('Metadata ancestry did not converge')
    (review / 'metadata-closure-unresolved.json').write_text(json.dumps(sorted(unresolved), indent=2) + '\n')
    if unresolved:
        raise ValueError('Unresolved metadata property references require manual supplier review')
    return additions


def main():
    """Audit metadata after the candidate artifact audit, before candidate assembly."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--metadata', type=Path, required=True)
    parser.add_argument('--review', type=Path, required=True)
    args = parser.parse_args()
    print('Additional exact metadata records: ' + str(len(audit_metadata(args.metadata, args.review))))


if __name__ == '__main__':
    main()
