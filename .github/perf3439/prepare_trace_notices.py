"""Add exact supplier-reviewed notices only to the isolated diagnostic checkout."""
import copy
import hashlib
import json
import pathlib
import shutil
import sys
import xml.etree.ElementTree as ET

mode, checkout, evidence_dir = sys.argv[1:4]
root, evidence = pathlib.Path(checkout), pathlib.Path(evidence_dir)
catalog_path = root / 'config/licenses/catalog.json'
def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()
if mode == 'check':
    receipt = json.loads((evidence / 'trace-notices-receipt.json').read_text())
    assert digest(catalog_path) == receipt['catalog_sha256'], 'Diagnostic notice catalog changed during build'
    sys.exit(0)
assert mode == 'prepare'
proof_path = pathlib.Path(__file__).with_name('trace-supplier-proof.json')
proof = json.loads(proof_path.read_text())
expected = {
    ('androidx.compose.runtime', 'runtime-tracing', '1.13.0-beta01'),
    ('androidx.tracing', 'tracing-wire-android', '2.0.4'),
    ('com.squareup.wire', 'wire-runtime-jvm', '6.4.7'),
}
catalog = json.loads(catalog_path.read_text())
original = copy.deepcopy(catalog)
# Reuse the already reviewed URL notice and full Apache2 text, preserving their hashes.
anchor = next(n for n in catalog['notices'] if n['id'] == 'd3ab8bbda5ccfffd8c19752965433c69e7d4581e5a1a0bfc77ef8fb7ffa62ae3')
assert anchor['sha256'] == '0dec6aeec4006c044352a33f0825bf879bcd810e5e38e2b3230e4475916cdd2f'
assert anchor['full_text']['sha256'] == 'cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30'
for text in [anchor, anchor['full_text']]:
    assert digest(catalog_path.parent / text['text']) == text['sha256']
receipts = []
ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
for coordinate in sorted(expected):
    rows = [p for p in proof if (p['group'], p['name'], p['version']) == coordinate]
    pom = next(p for p in rows if p['file'].endswith('.pom'))
    artifact = next(p for p in rows if p['file'].endswith(('.aar', '.jar')))
    path = pathlib.Path(__file__).parent / 'supplier-poms' / pom['file']
    assert digest(path) == pom['sha256']
    xml = ET.parse(path).getroot()
    assert tuple(xml.findtext('m:' + k, namespaces=ns) for k in ['groupId', 'artifactId', 'version']) == coordinate
    licenses = xml.findall('m:licenses/m:license', ns)
    assert len(licenses) == 1
    url = licenses[0].findtext('m:url', namespaces=ns)
    assert url in {'http://www.apache.org/licenses/LICENSE-2.0.txt', 'https://www.apache.org/licenses/LICENSE-2.0'}
    identifier = 'perf3439-diagnostic-' + coordinate[1]
    assert not any(n['id'] == identifier for n in catalog['notices'])
    notice = {k: copy.deepcopy(anchor[k]) for k in ['text', 'sha256', 'full_text']}
    notice.update(id=identifier, name=xml.findtext('m:name', namespaces=ns),
                  source='Verified supplier POM: ' + pom['url'] + ' sha256:' + pom['sha256'])
    catalog['notices'].append(notice)
    name = artifact.get('verification_artifact_name', artifact['file'])
    key = ':'.join(coordinate)
    assert not any(a['coordinate'] == key and a['name'] == name for a in catalog['artifacts'])
    catalog['artifacts'].append({'coordinate': key, 'name': name,
                                'sha256': artifact['sha256'], 'notice_ids': [identifier]})
    receipts.append({'coordinate': key, 'name': name, 'sha256': artifact['sha256'],
                     'pom_sha256': pom['sha256'], 'license_url': url, 'notice_id': identifier})
    shutil.copyfile(path, evidence / path.name)
assert len(catalog['artifacts']) == len(original['artifacts']) + 3
assert len(catalog['notices']) == len(original['notices']) + 3
assert catalog['artifacts'][:-3] == original['artifacts'] and catalog['notices'][:-3] == original['notices']
assert {k: v for k, v in catalog.items() if k not in ('artifacts', 'notices')} == {k: v for k, v in original.items() if k not in ('artifacts', 'notices')}
shutil.copyfile(catalog_path, evidence / 'trace-notices-original-catalog.json')
catalog_path.write_text(json.dumps(catalog, indent=2, ensure_ascii=False) + '\n')
shutil.copyfile(catalog_path, evidence / 'trace-notices-reviewed-catalog.json')
(evidence / 'trace-notices-receipt.json').write_text(json.dumps({'diagnostic_only': True,
    'catalog_sha256': digest(catalog_path), 'added_artifacts': receipts}, indent=2) + '\n')
print('Three exact diagnostic artifacts attributed; original notice baseline retained')
