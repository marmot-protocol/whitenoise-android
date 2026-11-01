#!/usr/bin/env python3
"""Assemble an inspectable dependency trust-root candidate from independently audited inputs."""
import argparse
import json
from pathlib import Path
import re
import shutil
import xml.etree.ElementTree as ET

if __package__:
    from .check_dependency_inputs import (validate_bundle, validate_verification, is_standard_supplier_path,
                                         REQUIRED_LOCKS, unique_object)
    from .retain_dependency_evidence import retain_capture, sha256
else:
    from check_dependency_inputs import (validate_bundle, validate_verification, is_standard_supplier_path,
                                         REQUIRED_LOCKS, unique_object)
    from retain_dependency_evidence import retain_capture, sha256


RETAINED_PREFIX = 'gradle/dependency-evidence/'


def retained_copy(relative, source_root, output):
    """Return the checked source and destination for one carried-forward retained file.

    The path must be a plain relative path under the retained-evidence folder, with no traversal or symlinked
    component, and both ends must resolve inside their roots. This runs before the candidate is created, so a
    rejected entry never writes anywhere.
    """
    if (not isinstance(relative, str) or not relative.startswith(RETAINED_PREFIX) or '\\' in relative or
            Path(relative).is_absolute() or any(part in ['', '.', '..'] for part in relative.split('/'))):
        raise ValueError('Unsafe retained dependency exception path')
    source = source_root / relative
    for parent in [source, *source.parents]:
        if parent == source_root:
            break
        if parent.is_symlink():
            raise ValueError('Unsafe retained dependency exception path')
    destination = output / relative
    if (not source.is_file() or not source.resolve().is_relative_to((source_root / RETAINED_PREFIX).resolve()) or
            not destination.resolve().is_relative_to((output / RETAINED_PREFIX).resolve())):
        raise ValueError('Unsafe retained dependency exception path')
    return source, destination


def capture_passes(capture):
    """Validate the producer's optional normal pass and union actual consistent observations."""
    captures = [capture]
    normal = capture / 'default-capture'
    if normal.is_symlink() or (normal.exists() and not normal.is_dir()):
        raise ValueError('Invalid nested normal capture')
    if normal.exists():
        if (normal / 'default-capture').exists() or (normal / 'default-capture').is_symlink():
            raise ValueError('Unsupported nested normal capture')
        captures.append(normal)
    first_source, first_inputs = None, None
    observed = {}
    for current in captures:
        source = (current / 'source.txt').read_text().strip()
        snapshot = json.loads((current / 'source-inputs.json').read_text(), object_pairs_hook=unique_object)
        if (not re.fullmatch(r'[a-f0-9]{40}', source) or not isinstance(snapshot, dict) or
                snapshot.get('schema') != 1 or snapshot.get('source_sha') != source):
            raise ValueError('Wrong-source captured build inputs')
        if len(captures) > 1:
            files = snapshot.get('files')
            if (not isinstance(files, dict) or any(not isinstance(name, str) or not name or
                    not isinstance(text, str) for name, text in files.items())):
                raise ValueError('Malformed captured build inputs')
        if first_source is None:
            first_source, first_inputs = source, snapshot
        elif (source, snapshot) != (first_source, first_inputs):
            raise ValueError('Capture passes differ in source or build inputs')
        resolution = json.loads((current / 'resolution.json').read_text(), object_pairs_hook=unique_object)
        if not isinstance(resolution, dict) or resolution.get('schema') != 1 or not isinstance(resolution.get('observations'), list):
            raise ValueError('Malformed captured resolution coverage')
        seen = set()
        for row in resolution['observations']:
            if (not isinstance(row, dict) or any(not isinstance(row.get(key), str) or not row[key]
                    for key in ('project', 'scope', 'configuration')) or
                    not isinstance(row.get('components', []), list) or not isinstance(row.get('unresolved', []), list)):
                raise ValueError('Malformed captured dependency configuration')
            if row.get('unresolved'):
                raise ValueError('Unresolved captured dependency configuration')
            identity = tuple(row[key] for key in ('project', 'scope', 'configuration'))
            if identity in seen:
                raise ValueError('Duplicate captured configuration')
            seen.add(identity)
            normalized = dict(row, components=sorted(row.get('components', []),
                                                    key=lambda value: json.dumps(value, sort_keys=True)),
                              unresolved=row.get('unresolved', []))
            if identity in observed and observed[identity] != normalized:
                raise ValueError('Conflicting captured configuration')
            observed[identity] = normalized
    return captures, sorted(' '.join(identity) for identity in observed)


def retain_passes(captures, output, evidence_url):
    """Bind both verbatim passes through the existing retained-evidence validator."""
    destination = output / 'gradle/dependency-evidence'
    retained = retain_capture(captures[0], destination, output, evidence_url)
    if len(captures) == 1:
        return retained
    primary = output / retained['path']
    receipt = json.loads(primary.read_text(), object_pairs_hook=unique_object)
    for capture in captures[1:]:
        reference = retain_capture(capture, destination / capture.name, output, evidence_url)
        additional = json.loads((output / reference['path']).read_text(), object_pairs_hook=unique_object)
        receipt['files'].update(additional['files'])
        receipt['files'][reference['path']] = reference['sha256']
    primary.write_text(json.dumps(receipt, indent=2, sort_keys=True) + '\n')
    return {'path': retained['path'], 'sha256': sha256(primary)}


def validate_capture_artifacts(captures, records, known):
    """Require independently checked verification bytes for runtime artifacts in every pass."""
    for capture in captures:
        runtime = json.loads((capture / 'artifact-evidence.json').read_text(), object_pairs_hook=unique_object)
        if not isinstance(runtime, dict) or runtime.get('schema') != 1 or not isinstance(runtime.get('artifacts'), list):
            raise ValueError('Malformed captured runtime evidence')
        seen = set()
        for item in runtime['artifacts']:
            if not isinstance(item, dict):
                raise ValueError('Malformed captured runtime artifact')
            key = (item.get('coordinate'), item.get('name'))
            digest = item.get('sha256')
            if (not all(isinstance(value, str) and value for value in key) or key in seen or
                    not isinstance(digest, str) or not re.fullmatch(r'[a-f0-9]{64}', digest)):
                raise ValueError('Malformed or duplicate captured runtime artifact')
            seen.add(key)
            if records.get(key) != digest or known.get(key, {}).get('sha256') != digest:
                raise ValueError('Captured artifact lacks reviewed verification: ' + '/'.join(key))


def assemble(capture, review, output, evidence_url, supplements=(), inputs=None, carry_forward=None):
    """Stage files and provenance for review; never alter the current checkout or approve a supplier."""
    if output.exists():
        raise ValueError('Candidate output must not already exist')
    captures, coverage = capture_passes(capture)
    reviewed = None
    retained_copies = []
    if carry_forward is not None:
        # Every carried path is checked before any filesystem effect.
        reviewed = json.loads(carry_forward.read_text(), object_pairs_hook=unique_object)
        source_root = carry_forward.resolve().parents[1]
        for item in reviewed.get('post_lock_selection', []):
            if isinstance(item, dict) and 'retained' in item:
                retained_copies.append(retained_copy(item['retained'].get('path'), source_root, output))
    inputs = inputs or capture / 'candidate-inputs'
    verification = inputs / 'gradle/verification-metadata.xml'
    original = validate_verification(verification)
    records = dict(original)
    audited = json.loads((review / 'provenance.json').read_text(), object_pairs_hook=unique_object)
    supplemental = [item for path in supplements for item in json.loads(path.read_text(), object_pairs_hook=unique_object)]
    known = {}
    for item in [*audited, *supplemental]:
        key = (item['coordinate'], item['name'])
        if key in known and known[key]['sha256'] != item['sha256']:
            raise ValueError('Conflicting independent supplier checks')
        known[key] = item
    for item in supplemental:
        key = (item['coordinate'], item['name'])
        if key in records and records[key] != item['sha256']:
            raise ValueError('Supplemental supplier conflicts with candidate bytes')
        records[key] = item['sha256']
    if any(key not in known or known[key]['sha256'] != sha for key, sha in records.items()):
        raise ValueError('Candidate has unaudited artifact bytes')
    validate_capture_artifacts(captures, records, known)
    output.mkdir(parents=True)
    (output / 'gradle').mkdir()
    namespace = 'https://schema.gradle.org/dependency-verification'
    ET.register_namespace('', namespace)
    tree = ET.parse(verification)
    root = tree.getroot()
    components = root.find('{' + namespace + '}components') if root.tag.startswith('{') else root.find('components')
    ns = '{' + namespace + '}' if root.tag.startswith('{') else ''
    for component in list(components):
        components.remove(component)
    grouped = {}
    for (coordinate, name), sha in sorted(records.items()):
        if coordinate not in grouped:
            group, module, version = coordinate.split(':')
            grouped[coordinate] = ET.SubElement(components, ns + 'component', group=group, name=module, version=version)
        artifact = ET.SubElement(grouped[coordinate], ns + 'artifact', name=name)
        ET.SubElement(artifact, ns + 'sha256', value=sha, origin='Independent official supplier audit')
    ET.indent(tree, space='   ')
    metadata = output / 'gradle/verification-metadata.xml'
    tree.write(metadata, encoding='utf-8', xml_declaration=True)
    files = {'gradle/verification-metadata.xml': sha256(metadata)}
    locks = set(REQUIRED_LOCKS)
    locks.update(p.name for p in inputs.glob('*gradle.lockfile'))
    for relative in sorted(locks):
        destination = output / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(inputs / relative, destination)
        files[relative] = sha256(destination)
    suppliers = {'/'.join(key): known[key]['source'] for key in sorted(records)}
    for key in sorted(records):
        item = known[key]
        mapping = item.get('publication_metadata')
        # Only an artifact outside its coordinate's canonical path needs its module metadata retained.
        if not is_standard_supplier_path(*key, item['source']):
            if not mapping:
                raise ValueError('Missing retained publication mapping path; re-audit this artifact')
            expected = 'publication-metadata/' + mapping['sha256'] + '.module'
            if mapping.get('path') != expected:
                raise ValueError('Missing retained publication mapping path; re-audit this artifact')
            payload = review / 'downloads' / mapping['sha256']
            if sha256(payload) != mapping['sha256']:
                raise ValueError('Changed retained publication mapping')
            destination = output / 'gradle' / expected
            destination.parent.mkdir(exist_ok=True)
            shutil.copyfile(payload, destination)
    retained = retain_passes(captures, output, evidence_url)
    manifest = {'schema': 1, 'reviewed_from': {'repository': 'marmot-protocol/whitenoise-android',
                'source_sha': (capture / 'source.txt').read_text().strip(), 'evidence': evidence_url,
                'retained': retained}, 'files': files, 'coverage': coverage, 'suppliers': suppliers}
    if reviewed is not None:
        # Reviewed exceptions outlive one capture; reviewers prune entries the new graph no longer needs.
        for key in ['post_lock_selection', 'supplemental_tools']:
            if key in reviewed:
                manifest[key] = reviewed[key]
        for source, destination in retained_copies:
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, destination)
    (output / 'gradle/dependency-inputs.json').write_text(json.dumps(manifest, indent=2, sort_keys=True) + '\n')
    validate_bundle(output)
    return manifest


def main():
    """Generate a staged review candidate, including exact metadata ancestry when explicitly supplied."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--capture', type=Path, required=True)
    parser.add_argument('--review', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--evidence-url', required=True)
    parser.add_argument('--supplemental', type=Path, action='append', default=[])
    parser.add_argument('--inputs', type=Path, help='Explicit normal-build-qualified lock/XML directory, if different from capture')
    parser.add_argument('--carry-forward', type=Path,
                        help='Committed gradle/dependency-inputs.json whose reviewed exceptions remain required')
    args = parser.parse_args()
    assemble(args.capture, args.review, args.output, args.evidence_url, args.supplemental, args.inputs,
             args.carry_forward)
    print('Staged dependency input candidate: ' + str(args.output))


if __name__ == '__main__':
    main()
