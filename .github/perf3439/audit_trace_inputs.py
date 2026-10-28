"""Fail closed around the isolated Compose profiling dependency overlay."""
import hashlib
import json
import pathlib
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

MODE, ROOT, EVIDENCE = sys.argv[1:4]
root, evidence = pathlib.Path(ROOT), pathlib.Path(EVIDENCE)
ns = {'v': 'https://schema.gradle.org/dependency-verification'}
verification = 'gradle/verification-metadata.xml'
configs = {'previewPlayReleaseCompileClasspath', 'previewPlayReleaseRuntimeClasspath', 'previewPlayReleaseLintChecksClasspath'}
allowed = {
    'androidx.compose.runtime:runtime-tracing': '1.13.0-beta01',
    'androidx.tracing:tracing-wire': '2.0.4',
    'androidx.tracing:tracing-wire-android': '2.0.4',
    'com.squareup.wire:wire-runtime': '6.4.7',
    'com.squareup.wire:wire-runtime-jvm': '6.4.7',
}

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def tracked_inputs():
    files = subprocess.check_output(['git', '-C', str(root), 'ls-files'], text=True).splitlines()
    return sorted(p for p in files if p.endswith('gradle.lockfile') or p == verification)

def xml_inputs(path):
    tree = ET.parse(path).getroot()
    assert [e.tag.rsplit('}', 1)[-1] for e in tree] == ['configuration', 'components']
    configuration = ET.tostring(tree.find('v:configuration', ns))
    entries = {}
    for component in tree.find('v:components', ns):
        assert component.tag.endswith('}component')
        coord = tuple(component.attrib[k] for k in ['group', 'name', 'version'])
        assert set(component.attrib) == {'group', 'name', 'version'}
        for artifact in component:
            assert artifact.tag.endswith('}artifact') and set(artifact.attrib) == {'name'}
            key = (*coord, artifact.attrib['name'])
            assert key not in entries, key
            assert len(artifact) == 1 and artifact[0].tag.endswith('}sha256'), key
            assert set(artifact[0].attrib) <= {'value', 'origin'}, key
            entries[key] = artifact[0].attrib['value']
    return configuration, entries

def lock_inputs(path):
    result = {}
    for line in path.read_text().splitlines():
        if not line or line.startswith('#'):
            continue
        coordinate, names = line.split('=', 1)
        for config in names.split(','):
            if not config:
                continue
            selected = result.setdefault(config, {})
            if coordinate != 'empty':
                group, module, version = coordinate.split(':')
                key = group + ':' + module
                assert key not in selected, (config, key)
                selected[key] = version
    return result

if MODE == 'snapshot':
    assert not evidence.exists(), 'Never overwrite an earlier input audit'
    files = tracked_inputs()
    for name in files:
        target = evidence / 'baseline' / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(root / name, target)
    (evidence / 'files.json').write_text(json.dumps(files))
    sys.exit(0)

files = json.loads((evidence / 'files.json').read_text())
assert tracked_inputs() == files
if MODE == 'check':
    audited = json.loads((evidence / 'audited-hashes.json').read_text())
    assert {p: digest(root / p) for p in files} == audited, 'Build mutated audited inputs'
    sys.exit(0)
assert MODE == 'audit'
for name in files:
    target = evidence / 'generated' / name
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(root / name, target)
shutil.copyfile(root / 'trace-resolved-graph.json', evidence / 'generated-resolved-graph.json')
proof = json.loads(pathlib.Path(sys.argv[4]).read_text())
trusted = {(p['group'], p['name'], p['version'], p.get('verification_artifact_name', p['file'])): p['sha256'] for p in proof}
a_config, a_entries = xml_inputs(evidence / 'baseline' / verification)
b_config, b_entries = xml_inputs(root / verification)
assert a_config == b_config, 'Verification configuration changed'
assert all(b_entries.get(k) == v for k, v in a_entries.items()), 'Existing trust changed'
new_entries = {k: v for k, v in b_entries.items() if k not in a_entries}
assert new_entries and all(trusted.get(k) == v for k, v in new_entries.items()), new_entries
discarded_removals = {}
for name in files:
    if name not in ('app/gradle.lockfile', verification):
        prior = lock_inputs(evidence / 'baseline' / name)
        generated = lock_inputs(root / name)
        assert set(prior) == set(generated), ('Unrelated lock configurations changed', name)
        for config, selected in generated.items():
            assert all(prior[config].get(k) == v for k, v in selected.items()), ('Unrelated dependency addition/version change', name, config)
            removed = {k: v for k, v in prior[config].items() if k not in selected}
            if removed:
                discarded_removals.setdefault(name, {})[config] = removed
        # Candidate generation may discard locks it did not need. Never export those removals:
        # the actual assembly consumes original reviewed inputs with normal STRICT validation.
        shutil.copyfile(evidence / 'baseline' / name, root / name)
before = lock_inputs(evidence / 'baseline' / 'app/gradle.lockfile')
after = lock_inputs(root / 'app/gradle.lockfile')
assert set(before) == set(after), 'Configurations changed'
changes = {}
for config in before:
    if config not in configs:
        assert before[config] == after[config], config
        continue
    prior, current = before[config], after[config]
    if config == 'previewPlayReleaseCompileClasspath':
        assert prior == current, 'Diagnostic runtime overlay changed compilation dependencies'
        continue
    assert not set(prior) - set(current), (config, 'Removed dependency')
    for key in set(prior) | set(current):
        if prior.get(key) == current.get(key):
            continue
        assert (key not in prior and allowed.get(key) == current.get(key)), (config, key, prior.get(key), current.get(key))
        changes.setdefault(config, {})[key] = [prior.get(key), current.get(key)]
    assert all(current.get(k) == v for k, v in allowed.items()), config
resolved = json.loads((root / 'trace-resolved-graph.json').read_text())
assert set(resolved) == configs
assert all(resolved[c] == after[c] for c in configs), 'Resolved graph differs from audited locks'
for name in files:
    target = evidence / 'audited' / name
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(root / name, target)
shutil.copyfile(root / 'trace-resolved-graph.json', evidence / 'resolved-graph.json')
(evidence / 'changes.json').write_text(json.dumps({'locks': changes, 'discarded_generated_removals': discarded_removals, 'new_hashes': [list(k) + [v] for k, v in new_entries.items()]}, indent=2))
(evidence / 'audited-hashes.json').write_text(json.dumps({p: digest(root / p) for p in files}, indent=2))
print(json.dumps({'audited_new_artifacts': len(new_entries), 'configurations': sorted(configs), 'dependency_changes': changes}, indent=2))
