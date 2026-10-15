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
        self.save()

    def save(self):
        """Persist only the reviewed manifest; never silently refresh its pinned file hashes."""
        (self.root / 'gradle/dependency-inputs.json').write_text(json.dumps(self.manifest))

    def test_complete_reviewed_bundle_passes_without_git_or_network(self):
        """Source archives retain the same committed evidence boundary."""
        self.assertEqual((10, 1), inputs.validate_bundle(self.root))

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


if __name__ == '__main__':
    unittest.main()
