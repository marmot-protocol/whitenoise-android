#!/usr/bin/env python3
"""Assemble an inspectable dependency trust-root candidate from independently audited inputs."""
import argparse
import json
from pathlib import Path
import shutil
import xml.etree.ElementTree as ET

if __package__:
    from .check_dependency_inputs import validate_bundle, validate_verification, REQUIRED_LOCKS, unique_object
    from .retain_dependency_evidence import retain_capture, sha256
else:
    from check_dependency_inputs import validate_bundle, validate_verification, REQUIRED_LOCKS, unique_object
    from retain_dependency_evidence import retain_capture, sha256


def assemble(capture, review, output, evidence_url, supplements=(), inputs=None, carry_forward=None):
    """Stage files and provenance for review; never alter the current checkout or approve a supplier."""
    if output.exists():
        raise ValueError('Candidate output must not already exist')
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
    resolution = json.loads((capture / 'resolution.json').read_text(), object_pairs_hook=unique_object)
    if resolution.get('schema') != 1 or not isinstance(resolution.get('observations'), list):
        raise ValueError('Malformed captured resolution coverage')
    if any(row.get('unresolved') for row in resolution['observations']):
        raise ValueError('Unresolved captured dependency configuration')
    coverage = sorted([{key: row[key] for key in ['project', 'scope', 'configuration']}
                       for row in resolution['observations']],
                      key=lambda row: (row['project'], row['scope'], row['configuration']))
    suppliers = [known[key] for key in sorted(records)]
    for item in suppliers:
        mapping = item.get('publication_metadata')
        if mapping:
            expected = 'publication-metadata/' + mapping['sha256'] + '.module'
            if mapping.get('path') != expected:
                raise ValueError('Missing retained publication mapping path; re-audit this artifact')
            payload = review / 'downloads' / mapping['sha256']
            if sha256(payload) != mapping['sha256']:
                raise ValueError('Changed retained publication mapping')
            destination = output / 'gradle' / expected
            destination.parent.mkdir(exist_ok=True)
            shutil.copyfile(payload, destination)
    retained = retain_capture(capture, output / 'gradle/dependency-evidence', output, evidence_url)
    manifest = {'schema': 1, 'reviewed_from': {'repository': 'marmot-protocol/whitenoise-android',
                'source_sha': (capture / 'source.txt').read_text().strip(), 'evidence': evidence_url,
                'retained': retained}, 'files': files, 'coverage': coverage, 'suppliers': suppliers}
    if carry_forward is not None:
        # Reviewed exceptions outlive one capture; reviewers prune entries the new graph no longer needs.
        reviewed = json.loads(carry_forward.read_text(), object_pairs_hook=unique_object)
        for key in ['post_lock_selection', 'supplemental_tools']:
            if key in reviewed:
                manifest[key] = reviewed[key]
        for item in manifest.get('post_lock_selection', []):
            if 'retained' in item:
                destination = output / item['retained']['path']
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(carry_forward.resolve().parents[1] / item['retained']['path'], destination)
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
