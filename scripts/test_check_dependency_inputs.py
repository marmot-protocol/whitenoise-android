"""Reject stale provenance, incomplete classpath locks and checksum bypasses."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from scripts import check_dependency_inputs as inputs

XML = '''<verification-metadata><configuration><verify-metadata>true</verify-metadata>
<verify-signatures>false</verify-signatures></configuration><components>
<component group="example" name="library" version="1.0"><artifact name="library-1.0.jar">
<sha256 value="DIGEST"/></artifact></component></components></verification-metadata>'''.replace('DIGEST', 'a' * 64)


class DependencyInputsTest(unittest.TestCase):
    """Exercise the checked-in trust boundary with independently constructed evidence."""

    def setUp(self):
        """Create the smallest complete app/plugin lock set with one supplier artifact."""
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / 'gradle').mkdir()
        self.metadata = self.root / 'gradle/verification-metadata.xml'
        self.metadata.write_text(XML)
        self.manifest = {'schema': 1, 'reviewed_from': {
            'repository': 'marmot-protocol/whitenoise-android', 'source_sha': 'b' * 40,
            'evidence': 'https://github.com/marmot-protocol/whitenoise-android/actions/runs/123/artifacts/456'},
            'files': {}, 'coverage': [], 'suppliers': [{
                'coordinate': 'example:library:1.0', 'name': 'library-1.0.jar', 'sha256': 'a' * 64,
                'source': 'https://repo.maven.apache.org/maven2/example/library/1.0/library-1.0.jar.sha256',
                'method': 'published-sha256'}]}
        for module in ['', 'app/', 'benchmark/', 'cryptoBenchmark/', 'fuzz/']:
            (self.root / module).mkdir(exist_ok=True)
            (self.root / module / 'buildscript-gradle.lockfile').write_text('empty=classpath\n')
            self.manifest['coverage'].append({'project': ':' + module.rstrip('/'),
                                             'scope': 'plugin', 'configuration': 'classpath'})
            if module:
                (self.root / module / 'gradle.lockfile').write_text('empty=\n')
        (self.root / 'settings-gradle.lockfile').write_text('empty=incomingCatalogForLibs0\n')
        names = [variant + suffix for variant in [
            'devPlayDebug', 'devZapstoreDebug', 'productionPlayRelease', 'productionZapstoreRelease',
            'stagingPlayRelease', 'stagingZapstoreRelease', 'previewPlayRelease', 'previewZapstoreRelease',
            'previewPlayDebug', 'previewZapstoreDebug'] for suffix in ['CompileClasspath', 'RuntimeClasspath']]
        (self.root / 'app/gradle.lockfile').write_text('empty=' + ','.join(sorted(names)) + '\n')
        self.manifest['coverage'].extend({'project': ':app', 'scope': 'project', 'configuration': name}
                                         for name in names)
        for path in [self.metadata, *self.root.rglob('*gradle.lockfile')]:
            self.manifest['files'][str(path.relative_to(self.root))] = hashlib.sha256(path.read_bytes()).hexdigest()
        folder = self.root / 'gradle/dependency-evidence'
        folder.mkdir()
        asset = folder / 'source-inputs.json'
        asset.write_text(json.dumps({'source_sha': 'b' * 40}))
        receipt = folder / 'capture.json'
        receipt.write_text(json.dumps({'schema': 1, 'source_sha': 'b' * 40,
                           'files': {'gradle/dependency-evidence/source-inputs.json': hashlib.sha256(asset.read_bytes()).hexdigest()}}))
        self.manifest['reviewed_from']['retained'] = {
            'path': 'gradle/dependency-evidence/capture.json', 'sha256': hashlib.sha256(receipt.read_bytes()).hexdigest()}
        self.save()

    def save(self):
        """Persist only the reviewed manifest; never silently refresh its pinned file hashes."""
        (self.root / 'gradle/dependency-inputs.json').write_text(json.dumps(self.manifest))

    def test_complete_reviewed_bundle_passes_without_git_or_network(self):
        """Source archives retain the same committed evidence boundary."""
        self.assertEqual((10, 1), inputs.validate_bundle(self.root))

    def test_retained_capture_survives_without_hosted_artifact_and_rejects_changed_source(self):
        """The original URL is historical context; committed receipt bytes remain verifiable."""
        self.assertEqual((10, 1), inputs.validate_bundle(self.root))
        receipt = self.root / self.manifest['reviewed_from']['retained']['path']
        value = json.loads(receipt.read_text()); value['source_sha'] = 'c' * 40
        receipt.write_text(json.dumps(value))
        self.manifest['reviewed_from']['retained']['sha256'] = hashlib.sha256(receipt.read_bytes()).hexdigest()
        self.save()
        with self.assertRaisesRegex(ValueError, 'Wrong-source retained'):
            inputs.validate_bundle(self.root)

    def test_audited_capture_assembles_a_complete_staged_candidate(self):
        """Exercise the maintainer's actual capture-to-assembly contract with isolated bytes."""
        from scripts.assemble_dependency_inputs import assemble
        from scripts.audit_dependency_candidates import runtime_identities
        import shutil
        capture = self.root / 'capture'; capture.mkdir()
        candidate = capture / 'candidate-inputs'
        for relative in self.manifest['files']:
            destination = candidate / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(self.root / relative, destination)
        (capture / 'source.txt').write_text('b' * 40 + '\n')
        (capture / 'source-inputs.json').write_text(json.dumps({'schema': 1, 'source_sha': 'b' * 40, 'files': {}}))
        (capture / 'resolution.json').write_text(json.dumps({'schema': 1, 'observations': self.manifest['coverage']}))
        runtime = capture / 'artifact-evidence.json'
        runtime.write_text(json.dumps({'schema': 1, 'artifacts': [{
            'coordinate': 'example:library:1.0', 'name': 'library-1.0.jar', 'sha256': 'a' * 64}]}))
        self.assertEqual(runtime_identities(runtime), {('example:library:1.0', 'library-1.0.jar')})
        review = self.root / 'review'; review.mkdir()
        (review / 'provenance.json').write_text(json.dumps(self.manifest['suppliers']))
        destination = self.root / 'staged-candidate'
        result = assemble(capture, review, destination, self.manifest['reviewed_from']['evidence'])
        self.assertEqual(result['reviewed_from']['source_sha'], 'b' * 40)
        self.assertEqual((10, 1), inputs.validate_bundle(destination))
        self.assertEqual(self.metadata.read_text(), XML)
        runtime.write_text('[]')
        with self.assertRaisesRegex(ValueError, 'Expected captured'):
            runtime_identities(runtime)

    def test_changed_bytes_and_missing_module_lock_fail(self):
        """A validly formatted changed lock is still stale evidence."""
        path = self.root / 'fuzz/gradle.lockfile'
        path.write_text('example:new:2.0=runtimeClasspath\nempty=\n')
        with self.assertRaisesRegex(ValueError, 'Stale or changed'):
            inputs.validate_bundle(self.root)
        path.unlink()
        del self.manifest['files']['fuzz/gradle.lockfile']
        self.save()
        with self.assertRaisesRegex(ValueError, 'Missing or unexpected'):
            inputs.validate_bundle(self.root)

    def test_wrong_source_and_missing_supplier_fail(self):
        """A hash copied from an unreviewed source cannot stand in for provenance."""
        self.manifest['reviewed_from']['repository'] = 'other/repository'
        self.save()
        with self.assertRaisesRegex(ValueError, 'wrong-source'):
            inputs.validate_bundle(self.root)
        self.manifest['reviewed_from']['repository'] = 'marmot-protocol/whitenoise-android'
        self.manifest['suppliers'] = []
        self.save()
        with self.assertRaisesRegex(ValueError, 'Missing independent'):
            inputs.validate_bundle(self.root)

    def test_uncovered_variant_and_wrong_supplier_digest_fail(self):
        """All required variants and every exact artifact must retain evidence."""
        row = self.manifest['coverage'].pop()
        self.save()
        with self.assertRaisesRegex(ValueError, 'Incomplete application'):
            inputs.validate_bundle(self.root)
        self.manifest['coverage'].append(row)
        self.manifest['suppliers'][0]['sha256'] = 'c' * 64
        self.save()
        with self.assertRaisesRegex(ValueError, 'supplier evidence'):
            inputs.validate_bundle(self.root)

    def test_cross_publication_requires_authenticated_module_mapping(self):
        """Capability aliases need pinned module metadata; arbitrary alternate paths fail."""
        artifact = self.manifest['suppliers'][0]
        artifact['method'] = 'independent-payload-sha256'
        artifact['source'] = 'https://repo.maven.apache.org/maven2/example/library/2.0/library-2.0.jar'
        self.save()
        with self.assertRaisesRegex(ValueError, 'without authenticated mapping'):
            inputs.validate_bundle(self.root)
        metadata_name = 'library-1.0.module'
        publication = json.dumps({'variants': [{'files': [{
            'name': 'library-1.0.jar', 'url': '../2.0/library-2.0.jar', 'sha256': 'a' * 64}]}]}).encode()
        metadata_sha = hashlib.sha256(publication).hexdigest()
        folder = self.root / 'gradle/publication-metadata'
        folder.mkdir()
        (folder / (metadata_sha + '.module')).write_bytes(publication)
        self.metadata.write_text(XML.replace('</component>', '<artifact name="' + metadata_name +
                                            '"><sha256 value="' + metadata_sha + '"/></artifact></component>'))
        self.manifest['files']['gradle/verification-metadata.xml'] = hashlib.sha256(self.metadata.read_bytes()).hexdigest()
        artifact['publication_metadata'] = {'coordinate': 'example:library:1.0', 'name': metadata_name,
                                            'sha256': metadata_sha,
                                            'path': 'publication-metadata/' + metadata_sha + '.module'}
        self.manifest['suppliers'].append({
            'coordinate': 'example:library:1.0', 'name': metadata_name, 'sha256': metadata_sha,
            'source': 'https://repo.maven.apache.org/maven2/example/library/1.0/' + metadata_name,
            'method': 'independent-payload-sha256'})
        self.save()
        self.assertEqual((10, 2), inputs.validate_bundle(self.root))
        artifact['source'] = 'https://repo.maven.apache.org/maven2/unrelated/artifact/3.0/other.jar'
        self.save()
        with self.assertRaisesRegex(ValueError, 'URL is not authenticated'):
            inputs.validate_bundle(self.root)
        artifact['source'] = 'https://repo.maven.apache.org/maven2/example/library/2.0/library-2.0.jar'
        artifact['publication_metadata']['sha256'] = 'c' * 64
        self.save()
        with self.assertRaisesRegex(ValueError, 'without authenticated mapping'):
            inputs.validate_bundle(self.root)

    def test_metadata_bypasses_and_malformed_records_fail(self):
        """Verification cannot be disabled, broadly trusted, entity-expanded or ambiguous."""
        for text in [XML.replace('true', 'false'),
                     XML.replace('</configuration>', '<trusted-artifacts><trust group=".*"/></trusted-artifacts></configuration>'),
                     '<!DOCTYPE verification-metadata>' + XML,
                     XML.replace('a' * 64, 'bad'),
                     XML.replace('</artifact>', '<sha256 value="' + 'b' * 64 + '"/></artifact>')]:
            with self.subTest(text=text[:80]):
                self.metadata.write_text(text)
                with self.assertRaises(ValueError):
                    inputs.validate_verification(self.metadata)

    def test_dynamic_and_contradictory_locks_fail(self):
        """Neither mutable selections nor conflicting empty records are valid inputs."""
        path = self.root / 'gradle.lockfile'
        for text in ['example:library:1.+=runtimeClasspath\nempty=\n',
                     'example:library:1.0=runtimeClasspath\nempty=runtimeClasspath\n',
                     'example:library:1.0=runtimeClasspath\n']:
            path.write_text(text)
            with self.subTest(text=text), self.assertRaises(ValueError):
                inputs.validate_lock(path)

    def test_all_xml_sections_and_options_are_validated(self):
        """A second section or scalar cannot hide a Gradle trust bypass from review."""
        variants = [
            XML.replace('</configuration>', '</configuration><configuration><trusted-artifacts>'
                        '<trust group="^.*$" regex="true"/></trusted-artifacts></configuration>'),
            XML.replace('</verify-metadata>', '</verify-metadata><verify-metadata>false</verify-metadata>'),
            XML.replace('</components>', '</components><components><component group="example" '
                        'name="library" version="1.0"><artifact name="library-1.0.jar">'
                        '<sha256 value="' + 'b' * 64 + '"><also-trust value="' + 'c' * 64 + '"/>'
                        '</sha256></artifact></component></components>'),
            XML.replace('<configuration>', '<configuration trust="all">'),
            XML.replace('<verify-metadata>true', '<verify-metadata/><verify-metadata>true'),
            XML.replace('<verify-signatures>false</verify-signatures>', '<unknown-option>true</unknown-option>'),
            XML.replace('</configuration>', '<trusted-artifacts/></configuration>'),
            XML.replace('name="library-1.0.jar"', 'name="library-1.0.jar" trust="true"'),
            XML.replace('</verification-metadata>', '<unknown-section/></verification-metadata>'),
        ]
        for text in variants:
            with self.subTest(text=text), self.assertRaises(ValueError):
                self.metadata.write_text(text)
                inputs.validate_verification(self.metadata)


if __name__ == '__main__':
    unittest.main()
