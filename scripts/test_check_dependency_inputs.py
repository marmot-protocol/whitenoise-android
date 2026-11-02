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
            'files': {}, 'coverage': [], 'suppliers': {}}
        # The audit's supplier records; the manifest keeps only each record's confirming URL.
        self.provenance = [{
            'coordinate': 'example:library:1.0', 'name': 'library-1.0.jar', 'sha256': 'a' * 64,
            'source': 'https://repo.maven.apache.org/maven2/example/library/1.0/library-1.0.jar.sha256',
            'method': 'published-sha256'}]
        self.manifest['suppliers'] = {r['coordinate'] + '/' + r['name']: r['source'] for r in self.provenance}
        self.observations = []
        for module in ['', 'app/', 'benchmark/', 'cryptoBenchmark/', 'fuzz/']:
            (self.root / module).mkdir(exist_ok=True)
            (self.root / module / 'buildscript-gradle.lockfile').write_text('empty=classpath\n')
            self.observations.append({'project': ':' + module.rstrip('/'),
                                      'scope': 'plugin', 'configuration': 'classpath'})
            if module:
                (self.root / module / 'gradle.lockfile').write_text('empty=\n')
        (self.root / 'settings-gradle.lockfile').write_text('empty=incomingCatalogForLibs0\n')
        names = [variant + suffix for variant in [
            'devPlayDebug', 'devZapstoreDebug', 'productionPlayRelease', 'productionZapstoreRelease',
            'stagingPlayRelease', 'stagingZapstoreRelease', 'previewPlayRelease', 'previewZapstoreRelease',
            'previewPlayDebug', 'previewZapstoreDebug'] for suffix in ['CompileClasspath', 'RuntimeClasspath']]
        (self.root / 'app/gradle.lockfile').write_text('empty=' + ','.join(sorted(names)) + '\n')
        self.observations.extend({'project': ':app', 'scope': 'project', 'configuration': name} for name in names)
        self.manifest['coverage'] = sorted(' '.join(row.values()) for row in self.observations)
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
        (capture / 'resolution.json').write_text(json.dumps({'schema': 1, 'observations': self.observations}))
        runtime = capture / 'artifact-evidence.json'
        runtime.write_text(json.dumps({'schema': 1, 'artifacts': [{
            'coordinate': 'example:library:1.0', 'name': 'library-1.0.jar', 'sha256': 'a' * 64}]}))
        self.assertEqual(runtime_identities(runtime), {('example:library:1.0', 'library-1.0.jar')})
        review = self.root / 'review'; review.mkdir()
        (review / 'provenance.json').write_text(json.dumps(self.provenance))
        destination = self.root / 'staged-candidate'
        result = assemble(capture, review, destination, self.manifest['reviewed_from']['evidence'])
        self.assertEqual(result['reviewed_from']['source_sha'], 'b' * 40)
        self.assertEqual((10, 1), inputs.validate_bundle(destination))
        self.assertEqual(self.metadata.read_text(), XML)
        runtime.write_text('[]')
        with self.assertRaisesRegex(ValueError, 'Expected captured'):
            runtime_identities(runtime)

    def test_assembly_carries_reviewed_exceptions_and_their_retained_evidence(self):
        """A new capture keeps reviewed post-lock exceptions instead of silently dropping them."""
        from scripts.assemble_dependency_inputs import assemble
        retained = self.root / 'gradle/dependency-evidence/post-lock-selection/report.json.gz'
        retained.parent.mkdir(parents=True)
        retained.write_bytes(b'retained report')
        self.manifest['post_lock_selection'] = [{
            'scope': 'plugin', 'configuration': 'classpath', 'project': ':',
            'additional_coordinates': [], 'additional_metadata': ['example:library:1.0/library-1.0.jar'],
            'reason': 'Reviewed fixture exception',
            'evidence': 'https://github.com/marmot-protocol/whitenoise-android/actions/runs/1/artifacts/2',
            'retained': {'path': str(retained.relative_to(self.root)),
                         'sha256': hashlib.sha256(retained.read_bytes()).hexdigest()}}]
        self.manifest['supplemental_tools'] = {'fixture': 'reviewed'}
        self.save()
        self.assertEqual((10, 1), inputs.validate_bundle(self.root))
        capture, review = self.stage_capture()
        destination = self.root / 'staged-candidate'
        result = assemble(capture, review, destination, self.manifest['reviewed_from']['evidence'],
                          carry_forward=self.root / 'gradle/dependency-inputs.json')
        self.assertEqual(self.manifest['post_lock_selection'], result['post_lock_selection'])
        self.assertEqual({'fixture': 'reviewed'}, result['supplemental_tools'])
        self.assertTrue((destination / retained.relative_to(self.root)).is_file())

    def stage_capture(self):
        """Build a capture and supplier review that assemble accepts for the fixture's committed inputs."""
        import shutil
        capture = self.root / 'capture'; capture.mkdir()
        for relative in self.manifest['files']:
            destination = capture / 'candidate-inputs' / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(self.root / relative, destination)
        (capture / 'source.txt').write_text('b' * 40 + '\n')
        for name in ['source-inputs.json', 'artifact-evidence.json']:
            (capture / name).write_text(json.dumps({'schema': 1, 'source_sha': 'b' * 40, 'artifacts': []}))
        (capture / 'resolution.json').write_text(json.dumps({'schema': 1, 'observations': self.observations}))
        review = self.root / 'review'; review.mkdir()
        (review / 'provenance.json').write_text(json.dumps(self.provenance))
        return capture, review

    def stage_dual_capture(self):
        """Use the producer's nested normal-pass layout without synthesizing a combined report."""
        import copy
        import shutil
        capture, review = self.stage_capture()
        (capture / 'source-inputs.json').write_text(json.dumps({
            'schema': 1, 'source_sha': 'b' * 40, 'files': {'build.gradle.kts': 'plugins {}'}}))
        app_lock = capture / 'candidate-inputs/app/gradle.lockfile'
        app_lock.write_text(app_lock.read_text().strip() + ',fixtureBenchmark,fixtureNormal\n')
        normal = self.root / 'normal-capture'
        shutil.copytree(capture, normal)
        normal.rename(capture / 'default-capture')
        benchmark_rows = copy.deepcopy(self.observations)
        benchmark_rows.append({'project': ':app', 'scope': 'project', 'configuration': 'fixtureBenchmark'})
        normal_rows = copy.deepcopy(self.observations)
        normal_rows.append({'project': ':app', 'scope': 'project', 'configuration': 'fixtureNormal'})
        (capture / 'resolution.json').write_text(json.dumps({'schema': 1, 'observations': benchmark_rows}))
        (capture / 'default-capture/resolution.json').write_text(json.dumps({
            'schema': 1, 'observations': normal_rows}))
        return capture, review

    def test_dual_capture_assembly_retains_raw_passes_and_actual_coverage_union(self):
        """The primary trust root binds both unmodified reports and their observed configuration union."""
        import gzip
        from scripts.assemble_dependency_inputs import assemble
        capture, review = self.stage_dual_capture()
        destination = self.root / 'staged-candidate'
        result = assemble(capture, review, destination, self.manifest['reviewed_from']['evidence'])
        expected = set(self.manifest['coverage']) | {
            ':app project fixtureNormal', ':app project fixtureBenchmark'}
        self.assertEqual(set(result['coverage']), expected)
        self.assertEqual((10, 1), inputs.validate_bundle(destination))
        for prefix in ('', 'default-capture/'):
            for name in ('resolution.json', 'artifact-evidence.json', 'source-inputs.json'):
                retained = destination / 'gradle/dependency-evidence' / (prefix + name + '.gz')
                self.assertEqual(gzip.decompress(retained.read_bytes()), (capture / (prefix + name)).read_bytes())
            retained = destination / 'gradle/dependency-evidence' / (prefix + 'captured-inputs/app/gradle.lockfile.gz')
            self.assertEqual(gzip.decompress(retained.read_bytes()),
                             (capture / (prefix + 'candidate-inputs/app/gradle.lockfile')).read_bytes())
        nested = destination / 'gradle/dependency-evidence/default-capture/resolution.json.gz'
        nested.write_bytes(b'changed normal report')
        with self.assertRaisesRegex(ValueError, 'retained dependency evidence asset'):
            inputs.validate_bundle(destination)

    def test_dual_capture_assembly_rejects_different_nested_source_before_output(self):
        """A complete second pass from another revision cannot supply extra coverage."""
        from scripts.assemble_dependency_inputs import assemble
        capture, review = self.stage_dual_capture()
        normal = capture / 'default-capture'
        (normal / 'source.txt').write_text('c' * 40 + '\n')
        snapshot = json.loads((normal / 'source-inputs.json').read_text())
        snapshot['source_sha'] = 'c' * 40
        (normal / 'source-inputs.json').write_text(json.dumps(snapshot))
        destination = self.root / 'staged-candidate'
        with self.assertRaisesRegex(ValueError, 'Capture passes differ in source or build inputs'):
            assemble(capture, review, destination, self.manifest['reviewed_from']['evidence'])
        self.assertFalse(destination.exists())

    def test_dual_capture_assembly_rejects_different_nested_inputs_before_output(self):
        """Matching revision labels do not hide different actual captured build inputs."""
        from scripts.assemble_dependency_inputs import assemble
        capture, review = self.stage_dual_capture()
        path = capture / 'default-capture/source-inputs.json'
        snapshot = json.loads(path.read_text()); snapshot['files']['build.gradle.kts'] = 'changed'
        path.write_text(json.dumps(snapshot))
        destination = self.root / 'staged-candidate'
        with self.assertRaisesRegex(ValueError, 'Capture passes differ in source or build inputs'):
            assemble(capture, review, destination, self.manifest['reviewed_from']['evidence'])
        self.assertFalse(destination.exists())

    def test_dual_capture_assembly_rejects_conflicting_repeated_configuration(self):
        """Repeated configuration names must retain the same actual component observations."""
        from scripts.assemble_dependency_inputs import assemble
        capture, review = self.stage_dual_capture()
        path = capture / 'default-capture/resolution.json'
        resolution = json.loads(path.read_text())
        resolution['observations'][0]['components'] = [{'coordinate': 'example:changed:2.0'}]
        path.write_text(json.dumps(resolution))
        destination = self.root / 'staged-candidate'
        with self.assertRaisesRegex(ValueError, 'Conflicting captured configuration'):
            assemble(capture, review, destination, self.manifest['reviewed_from']['evidence'])
        self.assertFalse(destination.exists())

    def test_dual_capture_assembly_rejects_unresolved_nested_configuration(self):
        """A successful primary pass cannot hide failures in the retained normal pass."""
        from scripts.assemble_dependency_inputs import assemble
        capture, review = self.stage_dual_capture()
        path = capture / 'default-capture/resolution.json'
        resolution = json.loads(path.read_text()); resolution['observations'][0]['unresolved'] = ['missing']
        path.write_text(json.dumps(resolution))
        destination = self.root / 'staged-candidate'
        with self.assertRaisesRegex(ValueError, 'Unresolved captured dependency configuration'):
            assemble(capture, review, destination, self.manifest['reviewed_from']['evidence'])
        self.assertFalse(destination.exists())

    def test_dual_capture_assembly_rejects_unaudited_normal_only_artifact(self):
        """Coverage from the second pass cannot smuggle a runtime artifact outside the trust root."""
        from scripts.assemble_dependency_inputs import assemble
        capture, review = self.stage_dual_capture()
        path = capture / 'default-capture/artifact-evidence.json'
        path.write_text(json.dumps({'schema': 1, 'artifacts': [{
            'coordinate': 'example:normal-only:2.0', 'name': 'normal-only-2.0.jar', 'sha256': 'b' * 64}]}))
        destination = self.root / 'staged-candidate'
        with self.assertRaisesRegex(ValueError, 'Captured artifact lacks reviewed verification'):
            assemble(capture, review, destination, self.manifest['reviewed_from']['evidence'])
        self.assertFalse(destination.exists())

    def test_dual_capture_assembly_checks_artifact_hashes_in_every_pass(self):
        """A known coordinate does not authorize different bytes in either raw pass."""
        from scripts.assemble_dependency_inputs import assemble
        capture, review = self.stage_dual_capture()
        destination = self.root / 'staged-candidate'
        for prefix in ('', 'default-capture/'):
            with self.subTest(prefix=prefix):
                path = capture / (prefix + 'artifact-evidence.json')
                original = path.read_bytes()
                path.write_text(json.dumps({'schema': 1, 'artifacts': [{
                    'coordinate': 'example:library:1.0', 'name': 'library-1.0.jar', 'sha256': 'b' * 64}]}))
                with self.assertRaisesRegex(ValueError, 'Captured artifact lacks reviewed verification'):
                    assemble(capture, review, destination, self.manifest['reviewed_from']['evidence'])
                self.assertFalse(destination.exists())
                path.write_bytes(original)

    def test_dual_capture_assembly_accepts_audited_normal_only_artifact(self):
        """The second pass may retain extra runtime rows when their bytes are already independently checked."""
        import gzip
        from scripts.assemble_dependency_inputs import assemble
        capture, review = self.stage_dual_capture()
        path = capture / 'default-capture/artifact-evidence.json'
        path.write_text(json.dumps({'schema': 1, 'artifacts': [{
            'coordinate': 'example:library:1.0', 'name': 'library-1.0.jar', 'sha256': 'a' * 64}]}))
        destination = self.root / 'staged-candidate'
        assemble(capture, review, destination, self.manifest['reviewed_from']['evidence'])
        self.assertEqual((10, 1), inputs.validate_bundle(destination))
        retained = destination / 'gradle/dependency-evidence/default-capture/artifact-evidence.json.gz'
        self.assertEqual(gzip.decompress(retained.read_bytes()), path.read_bytes())

    def test_assembly_rejects_unsafe_retained_paths_before_writing_anything(self):
        """Traversal, absolute, foreign-prefix and symlinked retained paths fail before any file is written.

        The carried manifest and the candidate both live under the fixture root, so any escaping write would appear
        in the snapshot of that root.
        """
        from scripts.assemble_dependency_inputs import assemble
        capture, review = self.stage_capture()
        outside = self.root / 'outside'
        outside.mkdir()
        (outside / 'secret.txt').write_text('outside')
        linked = self.root / 'gradle/dependency-evidence/linked'
        linked.symlink_to(outside, target_is_directory=True)
        unsafe = ['../outside.txt', str(outside / 'secret.txt'), 'gradle/dependency-evidence/../../outside.txt',
                  'gradle/elsewhere.txt', 'gradle/dependency-evidence/linked/secret.txt']
        for path in unsafe:
            with self.subTest(path=path):
                self.manifest['post_lock_selection'] = [{
                    'scope': 'plugin', 'reason': 'fixture', 'additional_coordinates': [], 'additional_metadata': [],
                    'evidence': 'https://github.com/marmot-protocol/whitenoise-android/actions/runs/1/artifacts/2',
                    'retained': {'path': path, 'sha256': 'a' * 64}}]
                self.save()
                before = sorted(p for p in self.root.rglob('*'))
                destination = self.root / 'staged-candidate'
                with self.assertRaisesRegex(ValueError, 'Unsafe retained'):
                    assemble(capture, review, destination, self.manifest['reviewed_from']['evidence'],
                             carry_forward=self.root / 'gradle/dependency-inputs.json')
                self.assertFalse(destination.exists())
                self.assertEqual(before, sorted(p for p in self.root.rglob('*')))

    def test_exceptions_must_name_locked_coordinates_and_checked_metadata(self):
        """Free-text exceptions cannot claim selections or metadata the trust root lacks."""
        base = {'scope': 'plugin', 'configuration': 'classpath', 'reason': 'Reviewed fixture exception',
                'evidence': 'https://github.com/marmot-protocol/whitenoise-android/actions/runs/1/job/2'}
        for item, message in [
                (dict(base, additional_coordinates=['example:unlocked:1.0']), 'absent from committed locks'),
                (dict(base, additional_metadata=['example:library:1.0/library-1.0.pom']), 'without a checksum'),
                (dict(base, reason=' '), 'lacks scope, reason or evidence'),
                (dict(base, retained={'path': 'gradle/dependency-evidence/missing.gz', 'sha256': 'a' * 64}),
                 'retained dependency exception evidence')]:
            with self.subTest(message=message), self.assertRaisesRegex(ValueError, message):
                self.manifest['post_lock_selection'] = [item]
                self.save()
                inputs.validate_bundle(self.root)

    def test_post_lock_selection_requires_declared_plugin_classpath(self):
        """An app selection cannot stand in for the reviewed root plugin graph."""
        self.manifest['post_lock_selection'] = [{
            'scope': 'plugin', 'project': ':', 'configuration': 'classpath',
            'additional_coordinates': ['example:library:1.0'],
            'reason': 'Normal plugin graph retains this already checked coordinate',
            'evidence': 'https://github.com/marmot-protocol/whitenoise-android/actions/runs/1/job/2'}]
        app = self.root / 'app/gradle.lockfile'
        rows = app.read_text().splitlines()
        rows = ['empty=' + ','.join(c for c in row[6:].split(',') if c != 'devPlayDebugRuntimeClasspath')
                if row.startswith('empty=') else row for row in rows]
        app.write_text('\n'.join(rows) + '\nexample:library:1.0=devPlayDebugRuntimeClasspath\n')
        self.manifest['files']['app/gradle.lockfile'] = hashlib.sha256(app.read_bytes()).hexdigest()
        self.save()
        with self.assertRaisesRegex(ValueError, 'declared project/configuration'):
            inputs.validate_bundle(self.root)
        plugin = self.root / 'buildscript-gradle.lockfile'
        plugin.write_text('example:library:1.0=otherConfiguration\nempty=classpath\n')
        self.manifest['files']['buildscript-gradle.lockfile'] = hashlib.sha256(plugin.read_bytes()).hexdigest()
        self.save()
        with self.assertRaisesRegex(ValueError, 'declared project/configuration'):
            inputs.validate_bundle(self.root)
        plugin.write_text('example:library:1.0=classpath\nempty=\n')
        self.manifest['files']['buildscript-gradle.lockfile'] = hashlib.sha256(plugin.read_bytes()).hexdigest()
        self.save()
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
        self.manifest['suppliers'] = {}
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
        self.manifest['suppliers']['example:library:1.0/library-1.0-unreviewed.jar'] = (
            'https://repo.maven.apache.org/maven2/example/library/1.0/library-1.0-unreviewed.jar')
        self.save()
        with self.assertRaisesRegex(ValueError, 'supplier evidence'):
            inputs.validate_bundle(self.root)

    def use_artifact(self, coordinate, name, source):
        """Make one exact artifact, and its confirming URL, the fixture's whole trust root."""
        group, module, version = coordinate.split(':')
        self.metadata.write_text(XML.replace('group="example" name="library" version="1.0"',
                                             f'group="{group}" name="{module}" version="{version}"')
                                 .replace('name="library-1.0.jar"', f'name="{name}"'))
        self.manifest['files']['gradle/verification-metadata.xml'] = hashlib.sha256(self.metadata.read_bytes()).hexdigest()
        self.manifest['suppliers'] = {coordinate + '/' + name: source}
        self.save()

    def test_cross_publication_requires_authenticated_module_mapping(self):
        """Capability aliases need pinned module metadata; arbitrary alternate paths fail."""
        self.manifest['suppliers'] = {
            'example:library:1.0/library-1.0.jar': 'https://repo.maven.apache.org/maven2/example/library/2.0/library-2.0.jar'}
        self.save()
        with self.assertRaisesRegex(ValueError, 'without authenticated mapping'):
            inputs.validate_bundle(self.root)
        metadata_name = 'library-1.0.module'
        publication = json.dumps({'variants': [{'files': [{
            'name': 'library-1.0.jar', 'url': '../2.0/library-2.0.jar', 'sha256': 'a' * 64}]}]}).encode()
        metadata_sha = hashlib.sha256(publication).hexdigest()
        folder = self.root / 'gradle/publication-metadata'
        folder.mkdir()
        mirror = folder / (metadata_sha + '.module')
        mirror.write_bytes(publication)
        self.metadata.write_text(XML.replace('</component>', '<artifact name="' + metadata_name +
                                            '"><sha256 value="' + metadata_sha + '"/></artifact></component>'))
        self.manifest['files']['gradle/verification-metadata.xml'] = hashlib.sha256(self.metadata.read_bytes()).hexdigest()
        self.manifest['suppliers']['example:library:1.0/' + metadata_name] = (
            'https://repo.maven.apache.org/maven2/example/library/1.0/' + metadata_name)
        self.save()
        self.assertEqual((10, 2), inputs.validate_bundle(self.root))
        self.manifest['suppliers']['example:library:1.0/library-1.0.jar'] = (
            'https://repo.maven.apache.org/maven2/unrelated/artifact/3.0/other.jar')
        self.save()
        with self.assertRaisesRegex(ValueError, 'URL is not authenticated'):
            inputs.validate_bundle(self.root)
        self.manifest['suppliers']['example:library:1.0/library-1.0.jar'] = (
            'https://repo.maven.apache.org/maven2/example/library/2.0/library-2.0.jar')
        self.save()
        mirror.write_bytes(publication + b' ')
        with self.assertRaisesRegex(ValueError, 'Changed or truncated'):
            inputs.validate_bundle(self.root)
        mirror.unlink()
        with self.assertRaisesRegex(ValueError, 'without authenticated mapping'):
            inputs.validate_bundle(self.root)

    def test_supplier_url_with_an_inserted_path_segment_is_not_the_declared_coordinate(self):
        """An extra directory before the coordinate path is another location, not evidence for this artifact."""
        self.manifest['suppliers']['example:library:1.0/library-1.0.jar'] = (
            'https://repo.maven.apache.org/maven2/not-the-declared-coordinate/example/library/1.0/library-1.0.jar.sha256')
        self.save()
        with self.assertRaisesRegex(ValueError, 'without authenticated mapping'):
            inputs.validate_bundle(self.root)

    def test_supplier_url_filename_must_be_the_recorded_artifact_name(self):
        """The coordinate directory alone is not enough when the final URL names a different file."""
        self.manifest['suppliers']['example:library:1.0/library-1.0.jar'] = (
            'https://repo.maven.apache.org/maven2/example/library/1.0/library-1.0-sources.jar.sha256')
        self.save()
        with self.assertRaisesRegex(ValueError, 'without authenticated mapping'):
            inputs.validate_bundle(self.root)

    def test_multiplatform_display_name_may_cite_the_coordinates_canonical_file(self):
        """Gradle's short display name still resolves to the coordinate's own module-version file."""
        directory = 'https://dl.google.com/dl/android/maven2/example/library-android/1.0/'
        for name in ['library.aar', 'library-release.aar', 'library-android.aar']:
            with self.subTest(name=name):
                self.use_artifact('example:library-android:1.0', name, directory + 'library-android-1.0.aar.sha256')
                self.assertEqual((10, 1), inputs.validate_bundle(self.root))
        for name, file in [('library-sources.aar', 'library-android-1.0.aar'),
                           ('library.aar', 'library-android-1.0-sources.aar'),
                           ('library.aar', 'library-android-1.0.jar'),
                           ('other.aar', 'library-android-1.0.aar')]:
            with self.subTest(name=name, file=file), self.assertRaisesRegex(ValueError, 'without authenticated mapping'):
                self.use_artifact('example:library-android:1.0', name, directory + file)
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

    def test_scalar_text_must_match_what_gradle_parses(self):
        """Text Gradle reads as false or ignores cannot pass after ElementTree normalization."""
        variants = [
            XML.replace('>true<', '>true <'),
            XML.replace('>true<', '>\n  true\n<'),
            XML.replace('>true<', '>true<!-- reviewed --> <'),
            XML.replace('>true<', '>tru&#101;<'),
            XML.replace('>true<', '><![CDATA[true]]><'),
            XML.replace('>false<', '>false <'),
            XML.replace('<component ', '<g:component xmlns:g="urn:example" ').replace(
                '</component>', '</g:component>'),
        ]
        for text in variants:
            with self.subTest(text=text), self.assertRaises(ValueError):
                self.metadata.write_text(text)
                inputs.validate_verification(self.metadata)


if __name__ == '__main__':
    unittest.main()
