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
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile
import zlib

ROOT = Path(__file__).resolve().parent.parent


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
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
                 f'mapping-{VERSION}-play.txt', f'mapping-{VERSION}-zapstore.txt', 'release-notes-en-US.txt', f'store-assets-{VERSION}.zip']
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
        for distribution in ('play', 'zapstore'):
            mapping = self.path / f'mapping-{VERSION}-{distribution}.txt'
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


    def test_github_draft_exposes_only_apk_manifest_and_matching_checksums(self):
        published = {}
        def fake_command(*args):
            if args[:2] == ('gh', 'api'):
                return b'[[]]'
            self.assertEqual(args[:3], ('gh', 'release', 'create'))
            self.assertIn('--draft', args)
            for name in args[-3:]:
                path = Path(name)
                published[path.name] = path.read_bytes()
            return b''
        with patch.object(bundle, 'check_tag'), patch.object(bundle, 'command', side_effect=fake_command):
            bundle.github_draft(SimpleNamespace(directory=self.path))
        apk = f'whitenoise-android-{VERSION}-arm64-v8a.apk'
        self.assertEqual(set(published), {apk, 'release-manifest.json', 'checksums-sha256.txt'})
        expected = {f'{hashlib.sha256(published[name]).hexdigest()}  ./{name}'
                    for name in (apk, 'release-manifest.json')}
        self.assertEqual(set(published['checksums-sha256.txt'].decode().splitlines()), expected)

    def test_github_draft_rejects_published_wrong_source_and_private_assets(self):
        release = dict(tag_name=f'android-v{VERSION}', draft=True, target_commitish=SOURCE, assets=[])
        for changes, message in ((dict(draft=False), 'published'),
                                 (dict(target_commitish='master'), 'target'),
                                 (dict(assets=[dict(name=f'whitenoise-android-{VERSION}-play.aab')]), 'Unexpected')):
            with self.subTest(changes=changes), patch.object(bundle, 'check_tag'):
                with patch.object(bundle, 'command', return_value=json.dumps([[{**release, **changes}]]).encode()) as command:
                    with self.assertRaisesRegex(ValueError, message):
                        bundle.github_draft(SimpleNamespace(directory=self.path))
                    self.assertEqual(command.call_count, 1)

    def test_github_draft_resume_requires_identical_asset_bytes(self):
        apk = f'whitenoise-android-{VERSION}-arm64-v8a.apk'
        release = dict(tag_name=f'android-v{VERSION}', draft=True, target_commitish=SOURCE,
                       assets=[dict(name=apk, id=1)])
        for matches in (False, True):
            with self.subTest(matches=matches):
                def download(*args, stdout, **kwargs):
                    stdout.write((self.path / apk).read_bytes() if matches else b'other candidate')
                def command(*args):
                    return json.dumps([[release]]).encode() if args[:2] == ('gh', 'api') else b''
                with patch.object(bundle, 'check_tag'), patch.object(bundle, 'command', side_effect=command) as calls:
                    with patch.object(bundle.subprocess, 'run', side_effect=download):
                        if matches:
                            bundle.github_draft(SimpleNamespace(directory=self.path))
                            self.assertEqual(calls.call_args.args[:3], ('gh', 'release', 'upload'))
                        else:
                            with self.assertRaisesRegex(ValueError, 'another candidate'):
                                bundle.github_draft(SimpleNamespace(directory=self.path))
                            self.assertEqual(calls.call_count, 1)


class ProvenanceTests(unittest.TestCase):
    def test_policy_reader_normalizes_whitespace_and_rejects_missing_values(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'config').mkdir()
            policy = root / 'config/android-release.properties'
            with patch.object(bundle, 'ROOT', root):
                policy.write_text('  # comment\n APP_SIGNING_SHA256 = abc123  \n\n')
                self.assertEqual(bundle.properties(), {'APP_SIGNING_SHA256': 'abc123'})
                policy.write_text('APP_SIGNING_SHA256=\n')
                with self.assertRaisesRegex(ValueError, 'Invalid release property'):
                    bundle.properties()
                policy.write_text('ZSP_VERSION=0.4.17\n ZSP_VERSION = 0.4.18\n')
                with self.assertRaisesRegex(ValueError, 'Duplicate release property'):
                    bundle.properties()

    def test_shell_and_metadata_use_the_shared_policy_parser(self):
        expected = bundle.properties()
        self.assertEqual(metadata.release_properties(), expected)
        for name in ('ZSP_VERSION', 'PLAY_UPLOAD_SHA256', 'BUNDLETOOL_SHA256'):
            result = subprocess.check_output(
                ['bash', '-c', 'repo_dir="$1"; source "$repo_dir/scripts/release-properties.sh"; release_property "$2"',
                 'fixture', str(ROOT), name], text=True)
            self.assertEqual(result.strip(), expected[name])

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
                for name in ('publish-zapstore.sh', 'release_bundle.py', 'release-properties.sh'):
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
                             f'mapping-{VERSION}-play.txt', f'mapping-{VERSION}-zapstore.txt'):
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
                self.assertIn('--no-compress', calls[0])
                if signer_matches:
                    self.assertEqual(result.returncode, 0, result.stderr)
                    self.assertEqual(len(calls), 2)
                    self.assertNotIn('--offline', calls[1])
                    self.assertIn('--no-compress', calls[1])
                    self.assertIn('--skip-metadata', calls[1])
                    self.assertIn('--skip-certificate-linking', calls[1])
                else:
                    self.assertNotEqual(result.returncode, 0)
                    self.assertEqual(len(calls), 1)
                    self.assertIn('signer differs', result.stderr)


class BundleSignatureTests(unittest.TestCase):
    def test_signature_gate_rejects_unsigned_partial_tampered_and_wrong_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            keystore = root / 'fixture.jks'
            def run(*args):
                return subprocess.check_output(args, stderr=subprocess.STDOUT)
            run('keytool', '-genkeypair', '-alias', 'fixture', '-keyalg', 'RSA', '-validity', '3650',
                '-dname', 'CN=Disposable Release Test', '-keystore', str(keystore),
                '-storepass', 'fixturepass', '-keypass', 'fixturepass')
            certificate = run('keytool', '-exportcert', '-alias', 'fixture', '-keystore', str(keystore),
                              '-storepass', 'fixturepass')
            fingerprint = hashlib.sha256(certificate).hexdigest()
            archive = root / 'candidate with spaces.aab'
            def verify(expected=fingerprint):
                return subprocess.run(['bash', str(ROOT / 'scripts/verify-play-bundle-signature.sh'),
                                       str(archive), expected], capture_output=True, text=True)
            with zipfile.ZipFile(archive, 'w') as z:
                z.writestr('payload.txt', 'original')
            self.assertNotEqual(verify().returncode, 0)
            run('jarsigner', '-keystore', str(keystore), '-storepass', 'fixturepass',
                '-keypass', 'fixturepass', str(archive), 'fixture')
            signed = archive.read_bytes()
            result = verify()
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout.strip(), fingerprint)
            self.assertNotEqual(verify('0' * 64).returncode, 0)
            with zipfile.ZipFile(archive, 'a') as z:
                z.writestr('unsigned-extra.txt', 'extra')
            self.assertNotEqual(verify().returncode, 0)
            with zipfile.ZipFile(io.BytesIO(signed)) as original, zipfile.ZipFile(archive, 'w') as changed:
                for name in original.namelist():
                    changed.writestr(name, b'tampered' if name == 'payload.txt' else original.read(name))
            self.assertNotEqual(verify().returncode, 0)


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
    def png(alpha=255, *, color=6, raw=None, compressed=None):
        def chunk(kind, data):
            return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
        if raw is None:
            raw = bytes([0, 10, 20, 30] + ([alpha] if color == 6 else []))
        if compressed is None:
            compressed = zlib.compress(raw)
        return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 1, 1, 8, color, 0, 0, 0)) +
                chunk(b'IDAT', compressed) + chunk(b'IEND', b''))

    def test_complete_rgb_and_rgba_assets_are_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'image.png'
            for color in (2, 6):
                path.write_bytes(self.png(color=color))
                metadata.require_png(path, dimensions=(1, 1), color_type=color)

    def test_damaged_rgb_and_rgba_assets_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'image.png'
            for color in (2, 6):
                valid = self.png(color=color)
                bad_crc = bytearray(valid)
                bad_crc[41] ^= 1
                compressed = zlib.compress(bytes([0, 10, 20, 30] + ([255] if color == 6 else [])))
                cases = [valid[:7], valid[:27], valid[:-13], valid[:-1], valid + b'extra',
                         bytes(bad_crc), self.png(color=color, raw=b'\x00'),
                         self.png(color=color, compressed=b'invalid'),
                         self.png(color=color, compressed=compressed[:-1]),
                         self.png(color=color, compressed=compressed + b'extra'),
                         self.png(color=color, raw=bytes([5, 10, 20, 30] + ([255] if color == 6 else [])))]
                for payload in cases:
                    with self.subTest(color=color, payload=payload):
                        path.write_bytes(payload)
                        with self.assertRaises(SystemExit):
                            metadata.require_png(path, dimensions=(1, 1), color_type=color)

    def test_opaque_rgba_is_accepted_and_transparency_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'image.png'
            path.write_bytes(self.png(255))
            metadata.require_png(path, dimensions=(1, 1), color_type=2, allow_opaque_rgba=True)
            path.write_bytes(self.png(254))
            with self.assertRaises(SystemExit):
                metadata.require_png(path, dimensions=(1, 1), color_type=2, allow_opaque_rgba=True)

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
