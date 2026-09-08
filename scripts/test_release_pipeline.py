"""Network-free regression coverage for artifact promotion and public-release guards."""
import copy
import hashlib
import importlib.util
import io
import json
import os
import re
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile
import zlib

ROOT = Path(__file__).resolve().parent.parent


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


bundle = load('release_bundle', 'scripts/release_bundle.py')
metadata = load('release_metadata', 'scripts/check-release-metadata.py')
VERSION = '2026.9.9'
SOURCE = 'a' * 40
RUN_ID = '12345'


class BundleTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name)
        self.policy = bundle.properties()
        names = [f'whitenoise-android-{VERSION}-arm64-v8a.apk', f'whitenoise-android-{VERSION}-play.aab',
                 f'mapping-{VERSION}.txt', 'release-notes-en-US.txt', f'store-assets-{VERSION}.zip']
        files = {}
        for name in names:
            path = self.path / name
            path.write_bytes(('fixture ' + name).encode())
            files[name] = {'bytes': path.stat().st_size, 'sha256': bundle.sha256(path)}
        self.manifest = dict(schemaVersion=2, sourceCommit=SOURCE, versionName=VERSION, versionCode=13,
                             buildRunId=RUN_ID, buildRunAttempt='1', worktreeDirty=False,
                             productionRuntimeConfigurationComplete=True, missingProductionRuntimeConfiguration=[],
                             applicationId=self.policy['APPLICATION_ID'],
                             appSigningCertificateSha256=self.policy['APP_SIGNING_SHA256'],
                             playUploadCertificateSha256=self.policy['PLAY_UPLOAD_SHA256'],
                             zspVersion=self.policy['ZSP_VERSION'], files=files)
        self.write_manifest()

    def write_manifest(self):
        (self.path / 'release-manifest.json').write_text(json.dumps(self.manifest))
        self.digest = bundle.sha256(self.path / 'release-manifest.json')
        (self.path / 'checksums-sha256.txt').write_text(''.join(
            f'{bundle.sha256(self.path / name)}  ./{name}\n'
            for name in sorted(set(self.manifest['files']) | {'release-manifest.json'})
            if '/' not in name))

    def verify(self):
        return bundle.verify_bundle(self.path, self.digest, VERSION, SOURCE, RUN_ID, 1, self.policy)

    def test_complete_candidate_is_accepted(self):
        self.assertEqual(self.verify()['versionCode'], 13)

    def test_reviewed_manifest_digest_is_required(self):
        (self.path / 'release-manifest.json').write_text('{}')
        with self.assertRaisesRegex(ValueError, 'reviewed SHA'):
            self.verify()

    def test_modified_payload_is_rejected(self):
        (self.path / f'whitenoise-android-{VERSION}-play.aab').write_bytes(b'other build')
        with self.assertRaisesRegex(ValueError, 'digest/size'):
            self.verify()

    def test_rehearsal_wrong_source_signer_and_attempt_are_rejected(self):
        cases = [('sourceCommit', 'b' * 40), ('buildRunAttempt', '2'), ('buildRunId', '12346'),
                 ('versionName', '2026.9.10'), ('worktreeDirty', True),
                 ('productionRuntimeConfigurationComplete', False), ('missingProductionRuntimeConfiguration', ['TOKEN']),
                 ('appSigningCertificateSha256', 'c' * 64), ('playUploadCertificateSha256', 'c' * 64)]
        for field, value in cases:
            with self.subTest(field=field):
                original = self.manifest[field]
                self.manifest[field] = value
                self.write_manifest()
                with self.assertRaises(ValueError):
                    self.verify()
                self.manifest[field] = original

    def test_missing_mapping_or_extra_apk_is_rejected(self):
        mapping = self.path / f'mapping-{VERSION}.txt'
        mapping.unlink()
        with self.assertRaisesRegex(ValueError, 'bundle files'):
            self.verify()
        mapping.write_bytes(b'fixture ' + mapping.name.encode())
        (self.path / 'unexpected.apk').write_bytes(b'other build')
        with self.assertRaisesRegex(ValueError, 'bundle files'):
            self.verify()

    def test_symlinked_payload_is_rejected(self):
        apk = self.path / f'whitenoise-android-{VERSION}-arm64-v8a.apk'
        apk.unlink()
        apk.symlink_to('release-notes-en-US.txt')
        with self.assertRaisesRegex(ValueError, 'regular files'):
            self.verify()

    def test_checksum_inventory_is_verified(self):
        (self.path / 'checksums-sha256.txt').write_text('')
        with self.assertRaisesRegex(ValueError, 'Checksum inventory'):
            self.verify()

    def test_source_version_mismatch_is_rejected(self):
        with patch.object(bundle, 'command', return_value=b'versionName = "2026.1.1"'):
            with self.assertRaisesRegex(ValueError, 'source commit'):
                bundle.verify_source_metadata(self.path, self.manifest)


class ProvenanceTests(unittest.TestCase):
    def setUp(self):
        self.run = dict(id=int(RUN_ID), repository={'full_name': bundle.REPOSITORY},
                        head_repository={'full_name': bundle.REPOSITORY}, path=bundle.BUILD_WORKFLOW,
                        event='workflow_dispatch', head_branch='master', head_sha=SOURCE,
                        status='completed', conclusion='success', run_attempt=1)

    def test_successful_manual_master_build_is_accepted(self):
        bundle.validate_run(self.run, RUN_ID)

    def test_non_release_runs_cannot_be_promoted(self):
        for field, value in [('path', '.github/workflows/android-pr-apk.yml'), ('event', 'pull_request'),
                             ('head_branch', 'feature'), ('status', 'in_progress'), ('conclusion', 'failure'),
                             ('id', 1), ('head_repository', {'full_name': 'someone/fork'})]:
            with self.subTest(field=field):
                run = copy.deepcopy(self.run)
                run[field] = value
                with self.assertRaises(ValueError):
                    bundle.validate_run(run, RUN_ID)

    def test_existing_annotated_tag_must_peel_to_candidate(self):
        tag = f'refs/tags/android-v{VERSION}'
        refs = f'{"c" * 40}\t{tag}\n{SOURCE}\t{tag}^{{}}\n'.encode()
        with patch.object(bundle, 'command', return_value=refs):
            bundle.check_tag(SOURCE, VERSION)
            with self.assertRaisesRegex(ValueError, 'different source'):
                bundle.check_tag('b' * 40, VERSION)

    def test_network_failure_is_not_treated_as_absent_tag(self):
        with patch.object(bundle, 'command', side_effect=subprocess.CalledProcessError(1, 'git')):
            with self.assertRaises(subprocess.CalledProcessError):
                bundle.check_tag(SOURCE, VERSION)

    def test_artifact_archive_cannot_escape_destination(self):
        for name in ('../escape.apk', '/escape.apk', 'nested/file.apk'):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                archive = io.BytesIO()
                with zipfile.ZipFile(archive, 'w') as z:
                    z.writestr(name, b'payload')
                archive.seek(0)
                with self.assertRaisesRegex(ValueError, 'top-level'):
                    bundle.extract_flat_archive(archive, Path(directory))


class PublicationGuardTests(unittest.TestCase):
    def test_publication_refuses_local_wrong_workflow_branch_or_confirmation(self):
        cases = [dict(), dict(GITHUB_ACTIONS='true', GITHUB_REF='refs/heads/master', GITHUB_WORKFLOW='Android Production Build'),
                 dict(GITHUB_ACTIONS='true', GITHUB_REF='refs/heads/feature', GITHUB_WORKFLOW='Android Zapstore - PUBLIC Publication'),
                 dict(GITHUB_ACTIONS='true', GITHUB_REF='refs/heads/master', GITHUB_WORKFLOW='Android Zapstore - PUBLIC Publication',
                      EXPECTED_VERSION=VERSION, CONFIRMATION='PUBLISH ZAPSTORE 2026.9.8')]
        for values in cases:
            with self.subTest(values=values):
                env = {k: v for k, v in os.environ.items() if not k.startswith(('GITHUB_', 'SIGN_WITH', 'BUNKER_', 'CONFIRMATION', 'EXPECTED_VERSION'))}
                env.update(values)
                result = subprocess.run(['bash', str(ROOT / 'scripts/publish-zapstore.sh'), '/must-not-execute'],
                                        capture_output=True, text=True, env=env)
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn('No such file', result.stderr)
                self.assertIn('error:', result.stderr)

    def test_skip_build_option_cannot_stamp_stale_artifacts(self):
        result = subprocess.run(['bash', str(ROOT / 'scripts/prepare-production-release.sh'), '--skip-build'],
                                capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('unknown argument: --skip-build', result.stderr)


class ZapstoreIntegrationTests(unittest.TestCase):
    def test_online_publication_only_follows_matching_signed_preflight(self):
        for signer_matches in (False, True):
            with self.subTest(signer_matches=signer_matches), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                (root / 'scripts').mkdir()
                (root / 'config').mkdir()
                (root / 'app').mkdir()
                for name in ('publish-zapstore.sh', 'release_bundle.py'):
                    (root / 'scripts' / name).write_bytes((ROOT / 'scripts' / name).read_bytes())
                (root / 'config/android-release.properties').write_bytes((ROOT / 'config/android-release.properties').read_bytes())
                policy = bundle.properties()
                (root / 'app/build.gradle.kts').write_text(
                    f'applicationId = "{policy["APPLICATION_ID"]}"\nversionName = "{VERSION}"\nversionCode = 13\n')
                notes_name = 'fastlane/metadata/android/en-US/changelogs/13.txt'
                notes = root / notes_name
                notes.parent.mkdir(parents=True)
                notes.write_text('Release notes')
                (root / 'zapstore.yaml').write_text('release_source: ./build/production-release/*.apk\n')
                def git(*args):
                    return subprocess.check_output(['git', *args], cwd=root, stderr=subprocess.DEVNULL).decode().strip()
                git('init')
                git('add', '.')
                git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '-m', 'Fixture')
                source = git('rev-parse', 'HEAD')
                git('remote', 'add', 'origin', str(root))
                out = root / 'build/production-release'
                out.mkdir(parents=True)
                for name in (f'whitenoise-android-{VERSION}-arm64-v8a.apk', f'whitenoise-android-{VERSION}-play.aab',
                             f'mapping-{VERSION}.txt'):
                    (out / name).write_bytes(b'synthetic fixture, never a real artifact')
                (out / 'release-notes-en-US.txt').write_text('Release notes')
                with zipfile.ZipFile(out / f'store-assets-{VERSION}.zip', 'w') as z:
                    for name in (notes_name, 'zapstore.yaml'):
                        z.write(root / name, name)
                manifest = dict(schemaVersion=2, sourceCommit=source, versionName=VERSION, versionCode=13,
                                buildRunId=RUN_ID, buildRunAttempt='1', worktreeDirty=False,
                                productionRuntimeConfigurationComplete=True, missingProductionRuntimeConfiguration=[],
                                applicationId=policy['APPLICATION_ID'], appSigningCertificateSha256=policy['APP_SIGNING_SHA256'],
                                playUploadCertificateSha256=policy['PLAY_UPLOAD_SHA256'], zspVersion=policy['ZSP_VERSION'],
                                files={f.name: {'bytes': f.stat().st_size, 'sha256': bundle.sha256(f)} for f in out.iterdir()})
                (out / 'release-manifest.json').write_text(json.dumps(manifest))
                digest = bundle.sha256(out / 'release-manifest.json')
                (out / 'checksums-sha256.txt').write_text(''.join(
                    f'{bundle.sha256(out / name)}  ./{name}\n' for name in sorted(set(manifest['files']) | {'release-manifest.json'})))
                publisher = policy['ZAPSTORE_PUBLISHER_PUBKEY'] if signer_matches else '0' * 64
                events = [dict(kind=kind, pubkey=publisher, sig='f' * 128) for kind in (32267, 30063)]
                fake = root / 'fake-zsp'
                fake.write_text('#!/usr/bin/env python3\nimport json,os,sys\n'
                                'with open(os.environ["ZSP_CALL_LOG"], "a") as log: log.write(" ".join(sys.argv[1:])+"\\n")\n'
                                f'if "--offline" in sys.argv:\n    for event in {events!r}: print(json.dumps(event))\n')
                fake.chmod(0o755)
                env = {**os.environ, 'PYTHONDONTWRITEBYTECODE': '1', 'GITHUB_ACTIONS': 'true',
                       'GITHUB_REF': 'refs/heads/master', 'GITHUB_WORKFLOW': 'Android Zapstore - PUBLIC Publication',
                       'EXPECTED_VERSION': VERSION, 'CONFIRMATION': f'PUBLISH ZAPSTORE {VERSION}',
                       'MANIFEST_SHA256': digest, 'SIGN_WITH': 'bunker://' + 'b' * 64,
                       'BUNKER_CLIENT_KEY': 'c' * 64, 'ZSP_CALL_LOG': str(root / 'calls')}
                result = subprocess.run(['bash', str(root / 'scripts/publish-zapstore.sh'), str(fake)],
                                        env=env, capture_output=True, text=True)
                calls = (root / 'calls').read_text().splitlines()
                self.assertIn('--offline', calls[0])
                if signer_matches:
                    self.assertEqual(result.returncode, 0, result.stderr)
                    self.assertEqual(len(calls), 2)
                    self.assertNotIn('--offline', calls[1])
                    self.assertIn('--skip-metadata', calls[1])
                    self.assertIn('--skip-linking', calls[1])
                else:
                    self.assertNotEqual(result.returncode, 0)
                    self.assertEqual(len(calls), 1)
                    self.assertIn('signer differs', result.stderr)


class WorkflowBoundaryTests(unittest.TestCase):
    def test_release_workflows_have_only_manual_entrypoints(self):
        for name in ('android-production-release.yml', 'android-release-distribute.yml', 'android-zapstore-publish.yml'):
            text = (ROOT / '.github/workflows' / name).read_text()
            trigger_block = text.split('\non:\n', 1)[1].split('\nconcurrency:', 1)[0]
            self.assertEqual(re.findall(r'^  ([a-z_]+):', trigger_block, re.MULTILINE), ['workflow_dispatch'])

    def test_build_and_internal_distribution_cannot_reach_zapstore(self):
        build = (ROOT / '.github/workflows/android-production-release.yml').read_text()
        distribute = (ROOT / '.github/workflows/android-release-distribute.yml').read_text()
        for text in (build, distribute):
            self.assertNotIn('zapstore-production', text)
            self.assertNotIn('SIGN_WITH', text)
            self.assertNotIn('publish-zapstore.sh', text)
        self.assertEqual(re.findall(r'^          tracks: (.+)$', distribute, re.MULTILINE), ['internal'])
        self.assertNotIn('upload-google-play', build)
        self.assertNotIn('gh release', build)
        public = (ROOT / '.github/workflows/android-zapstore-publish.yml').read_text()
        self.assertIn('needs: confirm-publication', public)
        self.assertIn('environment: zapstore-production', public)


class ScreenshotTests(unittest.TestCase):
    @staticmethod
    def png(alpha):
        def chunk(kind, data):
            return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
        return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 1, 1, 8, 6, 0, 0, 0)) +
                chunk(b'IDAT', zlib.compress(bytes([0, 10, 20, 30, alpha]))) + chunk(b'IEND', b''))

    def test_opaque_rgba_is_accepted_and_transparency_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'image.png'
            path.write_bytes(self.png(255))
            metadata.require_opaque_rgba(path, 1, 1)
            path.write_bytes(self.png(254))
            with self.assertRaises(SystemExit):
                metadata.require_opaque_rgba(path, 1, 1)

    def test_generator_preserves_curated_screenshots(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'scripts').mkdir()
            (root / 'bin').mkdir()
            images = root / 'fastlane/metadata/android/en-US/images/phoneScreenshots'
            images.mkdir(parents=True)
            (images / 'custom.png').write_bytes(b'user artwork')
            script = root / 'scripts/generate-store-assets.sh'
            script.write_bytes((ROOT / 'scripts/generate-store-assets.sh').read_bytes())
            magick = root / 'bin/magick'
            magick.write_text('#!/bin/sh\nexit 0\n')
            magick.chmod(0o755)
            subprocess.run(['bash', str(script)], env={**os.environ, 'PATH': f'{root / "bin"}:{os.environ["PATH"]}'},
                           check=True, capture_output=True)
            self.assertEqual(list(images.iterdir()), [images / 'custom.png'])
            self.assertEqual((images / 'custom.png').read_bytes(), b'user artwork')


if __name__ == '__main__':
    unittest.main()
