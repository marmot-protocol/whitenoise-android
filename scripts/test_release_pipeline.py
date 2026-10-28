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
import xml.etree.ElementTree as ET
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
apk_signature = load('apk_signature', 'scripts/verify_apk_signature.py')
publisher = load('zapstore_publish', 'scripts/zapstore_publish.py')
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
        policy = f'ZSP_VERSION={self.manifest["zspVersion"]}\n'.encode()
        gradle = (f'applicationId = "{self.manifest["applicationId"]}"\n'
                  f'versionCode = {self.manifest["versionCode"]}\nversionName = "2026.1.1"\n').encode()
        with patch.object(bundle, 'command', side_effect=[policy, gradle]) as calls:
            with self.assertRaisesRegex(ValueError, 'Bundle identity differs'):
                bundle.verify_source_metadata(self.path, self.manifest)
        self.assertEqual(calls.call_count, 2)


    def test_producer_tool_version_must_match_immutable_source_policy(self):
        with patch.object(bundle, 'command', return_value=b'ZSP_VERSION=0.4.17\n'):
            with self.assertRaisesRegex(ValueError, 'Producer ZSP version differs'):
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


class PublisherPolicyTests(unittest.TestCase):
    def test_publisher_npub_is_derived_from_single_hex_pin(self):
        self.assertEqual(publisher.publisher_npub(bundle.properties()['ZAPSTORE_PUBLISHER_PUBKEY']),
                         'npub1whtn0s68y3cs98zysa4nxrfzss5g5snhndv35tk5m2sudsr7ltms48r3ec')
        for invalid in ('', 'a' * 63, 'A' * 64, 'private-auth-url'):
            with self.assertRaises(ValueError):
                publisher.publisher_npub(invalid)

    def test_stable_v2_error_codes_are_retained_without_free_text(self):
        for code in ('invalid_arguments', 'apk_not_checked', 'apk_selection_required', 'source_failed',
                     'no_apk', 'too_many_candidates', 'publish_failed', 'signer_unavailable'):
            self.assertEqual(publisher.error_code(dict(error=dict(code=code, detail='private-auth-url'))), code)
        self.assertEqual(publisher.error_code(dict(error=dict(code='private-auth-url'))), 'unclassified_failure')
        self.assertEqual(publisher.error_code(dict(error=dict(code='invalid_config'))), 'unclassified_failure')


class ZapstoreIntegrationTests(unittest.TestCase):
    def publication_fixture(self, root, signer_matches=True, zsp_body='', readback_body='', producer_version=None):
        (root / 'scripts').mkdir()
        (root / 'config').mkdir()
        (root / 'app').mkdir()
        for name in ('publish-zapstore.sh', 'zapstore_publish.py', 'release_bundle.py', 'release-properties.sh'):
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
        if producer_version:
            p = root / 'config/android-release.properties'
            p.write_text(p.read_text().replace('ZSP_VERSION='+policy['ZSP_VERSION'], 'ZSP_VERSION='+producer_version))
        git('init')
        git('add', '.')
        git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '-m', 'Fixture')
        source = git('rev-parse', 'HEAD')
        (root / 'config/android-release.properties').write_bytes((ROOT / 'config/android-release.properties').read_bytes())
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
                        playUploadCertificateSha256=policy['PLAY_UPLOAD_SHA256'], zspVersion=producer_version or policy['ZSP_VERSION'],
                        files={f.name: {'bytes': f.stat().st_size, 'sha256': bundle.sha256(f)} for f in out.iterdir()})
        (out / 'release-manifest.json').write_text(json.dumps(manifest))
        digest = bundle.sha256(out / 'release-manifest.json')
        (out / 'checksums-sha256.txt').write_text(''.join(
            f'{bundle.sha256(out / name)}  ./{name}\n' for name in sorted(set(manifest['files']) | {'release-manifest.json'})))
        npub = 'npub1whtn0s68y3cs98zysa4nxrfzss5g5snhndv35tk5m2sudsr7ltms48r3ec'
        check = dict(ok=True, operation='check', signer=npub if signer_matches else 'npub1wrong', authorized=True,
                     proof=dict(owner=npub, expires_at='2099-01-01T00:00:00Z'), published_version_code=12,
                     apk=dict(app_id=policy['APPLICATION_ID'], version_name=VERSION, version_code=13,
                              certificate_hash=policy['APP_SIGNING_SHA256'],
                              hash=manifest['files'][f'whitenoise-android-{VERSION}-arm64-v8a.apk']['sha256']))
        fake = root / 'fake-zsp'
        fake.write_text('#!/usr/bin/env python3\nimport json,os,sys,time\n'
                        f'with open({str(root / "calls")!r}, "a") as log: log.write(" ".join(sys.argv[1:])+"\\n")\n'
                        'mode = "check" if "check" in sys.argv else "publish"\n'
                        f'document = {check!r} if mode == "check" else '
                        f'dict(ok=True, operation="publish", id="d"*64, status="pending", app_id={policy["APPLICATION_ID"]!r}, version_name={VERSION!r}, version_code=13)\n'
                        f'{zsp_body}\n'
                        'print(json.dumps(document))\n')
        fake.chmod(0o755)
        readback = root / 'fake-readback'
        readback.write_text('#!/usr/bin/env python3\nimport os,sys,json\n'
                            f'with open({str(root / "calls")!r}, "a") as log: log.write("readback "+" ".join(sys.argv[1:])+"\\n")\n'
                            'mode = sys.argv[1]\n'
                            f'{readback_body}\n'
                            'print(json.dumps(dict(App="a"*64,Release="d"*64,Asset="b"*64,URL="https://cdn.zapstore.dev/fixture.apk")))\n')
        readback.chmod(0o755)
        gh = root / 'gh'
        gh.write_text('#!/usr/bin/env python3\nimport os,sys,json\n'
                      'if os.environ.get("PRIOR_INTENT") != "true": print("[]")\n'
                      'elif "--paginate" in sys.argv: print(json.dumps([dict(artifacts=[dict(name="zapstore-submission-intent-"+os.environ["EXPECTED_VERSION"],workflow_run=dict(id=122))])]))\n'
                      'else: print(json.dumps(dict(path=".github/workflows/android-zapstore-publish.yml",head_branch="master")))\n')
        gh.chmod(0o755)
        env = {**os.environ, 'PYTHONDONTWRITEBYTECODE': '1', 'GITHUB_ACTIONS': 'true',
               'GITHUB_REF': 'refs/heads/master', 'GITHUB_WORKFLOW': 'Android Zapstore - PUBLIC Publication',
               'EXPECTED_VERSION': VERSION, 'CONFIRMATION': f'PUBLISH ZAPSTORE {VERSION}',
               'MANIFEST_SHA256': digest, 'SIGN_WITH': 'bunker://' + 'b' * 64,
               'BUNKER_CLIENT_KEY': 'c' * 64, 'ZSP_CALL_LOG': str(root / 'calls'),
               'PATH': str(root)+os.pathsep+os.environ['PATH'], 'GITHUB_RUN_ATTEMPT':'1',
               'GITHUB_RUN_ID':'123', 'ZSP_INTENT_RECORDED':'true',
               'ZSP_PREFLIGHT_TIMEOUT_SECONDS': '1', 'ZSP_PUBLISH_TIMEOUT_SECONDS': '1', 'ZSP_READBACK_TIMEOUT_SECONDS':'1'}
        def publish(**overrides):
            fixture_env = {**env, **overrides}
            results = []
            for phase in ('prepare','publish'):
                result = subprocess.run(['bash', str(root / 'scripts/publish-zapstore.sh'), str(fake), str(readback), phase],
                                        env=fixture_env, capture_output=True, text=True, timeout=20)
                results.append(result)
                if result.returncode: break
            result.stdout = ''.join(r.stdout for r in results)
            result.stderr = ''.join(r.stderr for r in results)
            calls = (root / 'calls').read_text().splitlines() if (root / 'calls').exists() else []
            return result, calls
        return publish

    def test_v2_pending_submission_is_verified_once_after_matching_preflight(self):
        for matches in (False, True):
            with self.subTest(matches=matches), tempfile.TemporaryDirectory() as directory:
                result,calls=self.publication_fixture(Path(directory),matches)()
                self.assertIn('--json check',calls[0])
                if matches:
                    self.assertEqual(result.returncode,0,result.stderr)
                    self.assertEqual(len(calls),4)
                    self.assertIn('--json publish',calls[2])
                    self.assertIn('--apk-hash',calls[2])
                    self.assertIn('--no-compress',calls[2])
                    self.assertNotIn('--offline',calls[2])
                    self.assertNotIn('--skip-certificate-linking',calls[2])
                    self.assertIn('-release-id '+ 'd'*64,calls[3])
                    self.assertIn('-apk-bytes',calls[3])
                    self.assertIn('verified public',result.stdout)
                else:
                    self.assertNotEqual(result.returncode,0)
                    self.assertEqual(len(calls),1)
                    self.assertIn('signer differs',result.stderr)

    def test_proof_and_candidate_failures_stop_before_public_writes(self):
        for body in [
            'if mode == "check": document["proof"] = None',
            'if mode == "check": document["authorized"] = False',
            'if mode == "check": document["proof"]["expires_at"] = "2000-01-01T00:00:00Z"',
            'if mode == "check": document["apk"]["hash"] = "0"*64',
            'if mode == "check": document["published_version_code"] = 14',
            'if mode == "check": document["next_step"] = {"requires_human":True}',
            'if mode == "check": time.sleep(10)',
        ]:
            with self.subTest(body=body), tempfile.TemporaryDirectory() as directory:
                result,calls=self.publication_fixture(Path(directory),zsp_body=body)()
                self.assertNotEqual(result.returncode,0)
                self.assertEqual(len(calls),1)

    def test_intent_must_be_retained_before_submission(self):
        with tempfile.TemporaryDirectory() as directory:
            result,calls=self.publication_fixture(Path(directory))(ZSP_INTENT_RECORDED='false')
            self.assertNotEqual(result.returncode,0)
            self.assertFalse(any('--json publish' in call for call in calls))

    def test_timeout_rejection_and_pending_timeout_never_resubmit(self):
        for body in ['', 'if mode == "publish": time.sleep(10)',
                     'if mode == "publish": document = dict(ok=False,error=dict(code="publish_rejected",detail="private-auth-url-secret")); print(json.dumps(document)); sys.exit(1)']:
            with self.subTest(body=body), tempfile.TemporaryDirectory() as directory:
                root=Path(directory)
                result,calls=self.publication_fixture(root,zsp_body=body,readback_body='if mode == "verify": sys.exit(1)')()
                self.assertNotEqual(result.returncode,0)
                self.assertEqual(sum('--json publish' in call for call in calls),1)
                self.assertIn('No automatic resubmission',result.stderr)
                receipt=(root/'build/zapstore-publication/receipt.json').read_text()
                self.assertNotIn('private-auth',result.stdout+result.stderr+receipt)
                self.assertNotIn('bunker://',receipt)
                self.assertIn('submission_unknown' if body else 'pending',receipt)

    def test_readback_failure_exposes_public_reason(self):
        with tempfile.TemporaryDirectory() as directory:
            body = 'if mode == "verify": print("error: CDN blob size differs from reviewed APK", file=sys.stderr); sys.exit(1)'
            result,calls=self.publication_fixture(Path(directory),readback_body=body)(VERIFY_ONLY='true')
            self.assertNotEqual(result.returncode,0)
            self.assertIn('CDN blob size differs',result.stderr)
            self.assertFalse(any('--json publish' in call for call in calls))

    def test_existing_public_candidate_is_only_verified(self):
        with tempfile.TemporaryDirectory() as directory:
            result,calls=self.publication_fixture(Path(directory),zsp_body='if mode == "check": document["published_version_code"] = 13')()
            self.assertEqual(result.returncode,0,result.stderr)
            self.assertEqual(len(calls),2)
            self.assertIn('readback verify',calls[1])

    def test_reruns_and_explicit_verification_cannot_publish(self):
        for override in [dict(GITHUB_RUN_ATTEMPT='2'),dict(VERIFY_ONLY='true'),dict(PRIOR_INTENT='true')]:
            with tempfile.TemporaryDirectory() as directory:
                result,calls=self.publication_fixture(Path(directory))(**override)
                self.assertEqual(result.returncode,0,result.stderr)
                self.assertEqual(len(calls),1)
                self.assertIn('readback verify',calls[0])

    def test_immutable_v1_candidate_can_use_v2_publisher(self):
        with tempfile.TemporaryDirectory() as directory:
            result,calls=self.publication_fixture(Path(directory),producer_version='0.4.17')()
            self.assertEqual(result.returncode,0,result.stderr)
            self.assertEqual(sum('--json publish' in call for call in calls),1)

    def test_existing_partial_or_unknown_release_blocks_submission(self):
        for code in (1,3):
            with tempfile.TemporaryDirectory() as directory:
                result,calls=self.publication_fixture(Path(directory),readback_body=f'if mode == "absent": sys.exit({code})')()
                self.assertNotEqual(result.returncode,0)
                self.assertFalse(any('--json publish' in call for call in calls))


class ApkCertificateOutputTests(unittest.TestCase):
    def test_numbered_and_sdk_range_signers_accept_only_the_pinned_identity(self):
        expected = 'a' * 64
        stamp = 'Source Stamp Signer certificate SHA-256 digest: ' + 'b' * 64
        numbered = f'Signer #1 certificate SHA-256 digest: {expected}'
        ranges = (f'Signer (minSdkVersion=33, maxSdkVersion=2147483647) certificate SHA-256 digest: {expected}\n'
                  f'Signer (minSdkVersion=28, maxSdkVersion=32) certificate SHA-256 digest: {expected}')
        dev = f'Signer (minSdkVersion=33 (dev release=true), maxSdkVersion=2147483647) certificate SHA-256 digest: {expected}'
        scheme = f'V2 Signer: certificate SHA-256 digest: {expected}'
        for output in (numbered, ranges, dev, scheme, numbered + '\n' + stamp):
            with self.subTest(output=output):
                self.assertEqual(apk_signature.verify_certificates(output, expected), expected)
        for output in ('', stamp, numbered.replace(expected, 'b' * 64),
                       ranges.replace(expected, 'b' * 64, 1),
                       numbered + '\nSigner #2 certificate SHA-256 digest: ' + 'b' * 64,
                       scheme.replace(expected, 'b' * 64),
                       scheme + '\nV3.1 Signer: certificate SHA-256 digest: ' + 'b' * 64,
                       numbered + '\nUnknown Signer certificate SHA-256 digest: ' + 'b' * 64,
                       numbered.replace(expected, 'invalid')):
            with self.subTest(output=output), self.assertRaises(ValueError):
                apk_signature.verify_certificates(output, expected)

    def test_runner_v2_output_distinguishes_certificate_from_public_key_digest(self):
        expected = 'e3ca27b35a14f3e67d0fa68c270c9a330539ac89930d89e6e5c05beba8f03b7a'
        output = ('Verifies\nVerified using v2 scheme (APK Signature Scheme v2): true\n'
                  'Number of signers: 1\n'
                  f'V2 Signer: certificate SHA-256 digest: {expected}\n'
                  'V2 Signer: key algorithm: RSA\nV2 Signer: key size (bits): 4096\n'
                  'V2 Signer: public key SHA-256 digest: b99ee1be64c78663ee09854acb45b205d3354983a2e904f980d07a9abe8b412d\n')
        self.assertEqual(apk_signature.verify_certificates(output, expected), expected)

    def test_invalid_signature_cannot_pass_with_matching_printed_certificate(self):
        expected = 'a' * 64
        result = SimpleNamespace(returncode=1, stderr='DOES NOT VERIFY',
                                 stdout=f'Signer #1 certificate SHA-256 digest: {expected}')
        with patch.object(apk_signature.subprocess, 'run', return_value=result) as run:
            with self.assertRaisesRegex(ValueError, 'signature verification failed'):
                apk_signature.verify('/sdk/apksigner', '/candidate with spaces.apk', expected)
            self.assertEqual(run.call_args.args[0],
                             ['/sdk/apksigner', 'verify', '--verbose', '--print-certs', '/candidate with spaces.apk'])


class KeystoreCertificateTests(unittest.TestCase):
    def test_certificate_identity_and_password_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            keystore = Path(directory) / 'fixture with spaces.p12'
            password = 'disposable-keystore-password'
            env = {**os.environ, 'RELEASE_FIXTURE_PASSWORD': password}
            subprocess.run(['keytool', '-genkeypair', '-alias', 'fixture', '-keyalg', 'RSA',
                            '-validity', '1', '-dname', 'CN=Disposable Certificate Test',
                            '-keystore', str(keystore), '-storepass:env', 'RELEASE_FIXTURE_PASSWORD'],
                           env=env, check=True, capture_output=True)
            certificate = subprocess.check_output(
                ['keytool', '-exportcert', '-alias', 'fixture', '-keystore', str(keystore),
                 '-storepass:env', 'RELEASE_FIXTURE_PASSWORD'], env=env)
            fingerprint = hashlib.sha256(certificate).hexdigest()
            for expected, supplied, success in [(fingerprint, password, True),
                                                ('0' * 64, password, False),
                                                (fingerprint, 'wrong-password', False),
                                                (fingerprint, '', False)]:
                with self.subTest(expected=expected, password_present=bool(supplied)):
                    result = subprocess.run(
                        ['bash', str(ROOT / 'scripts/verify-keystore-certificate.sh'),
                         str(keystore), 'fixture', 'RELEASE_FIXTURE_PASSWORD', expected],
                        env={**env, 'RELEASE_FIXTURE_PASSWORD': supplied},
                        text=True, capture_output=True)
                    self.assertEqual(result.returncode == 0, success, result.stderr)
                    self.assertNotIn(password, result.stdout + result.stderr)
                    if success:
                        self.assertIn(fingerprint, result.stdout)
                    elif expected != fingerprint:
                        self.assertIn(fingerprint, result.stderr)
                        self.assertIn(expected, result.stderr)


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
        self.assertEqual(re.findall(r'^          tracks: (.+)$', distribute, re.MULTILINE), ['internal', 'internal'])
        self.assertEqual(
            re.findall(r'^          changesNotSentForReview: (.+)$', distribute, re.MULTILINE), ['true']
        )
        self.assertIn("if: steps.play_deferred.outcome == 'failure'", distribute)
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


# Source policy only; merged APKs and MTE runtime support require separate evidence.
ANDROID = "{http://schemas.android.com/apk/res/android}"
TOOLS = "{http://schemas.android.com/tools}"


def check_policy(manifests):
    """Reject opt-outs and process overrides that bypass application inheritance."""
    if not {"main", "debug"}.issubset(manifests):
        raise AssertionError("main and debug application manifests are required")
    for source_set, manifest in manifests.items():
        application = manifest.find("application")
        if source_set in ("main", "debug"):
            expected = "sync" if source_set == "debug" else "async"
            if application is None or application.get(ANDROID + "memtagMode") != expected:
                raise AssertionError(f"{source_set}: expected application memtagMode={expected}")
        if source_set == "debug":
            replacements = application.get(TOOLS + "replace", "").split(",")
            if "android:memtagMode" not in [item.strip() for item in replacements]:
                raise AssertionError("debug: memtagMode must replace the main manifest value")
        if application is not None:
            removed = application.get(TOOLS + "remove", "").split(",")
            if application.get(TOOLS + "node") in ("replace", "remove", "removeAll") or (
                "android:memtagMode" in [item.strip() for item in removed]
            ):
                raise AssertionError(f"{source_set}: application merger marker discards tagging policy")
        for node in manifest.iter():
            if ANDROID + "memtagMode" not in node.attrib:
                continue
            if node is not application or source_set not in ("main", "debug"):
                raise AssertionError(f"{source_set}: unexpected memory-tagging override")


class MemoryTaggingPolicyTest(unittest.TestCase):
    """Guard source-level tagging policy; runtime qualification remains separate."""
    def manifests(self):
        """Load each shipped source-set manifest for policy validation."""
        return {
            path.parent.name: ET.parse(path).getroot()
            for path in (ROOT / "app/src").glob("*/AndroidManifest.xml")
        }

    def test_shipped_source_manifests_preserve_application_inheritance(self):
        """Check release and debug policy across all shipped source sets."""
        check_policy(self.manifests())

    def test_missing_or_weaker_application_policy_is_rejected(self):
        """Reject absent, disabled or incorrectly selected application modes."""
        for source_set in ("main", "debug"):
            for mode in (None, "off", "default", "sync" if source_set == "main" else "async"):
                with self.subTest(source_set=source_set, mode=mode):
                    manifests = self.manifests()
                    application = manifests[source_set].find("application")
                    if mode is None:
                        del application.attrib[ANDROID + "memtagMode"]
                    else:
                        application.set(ANDROID + "memtagMode", mode)
                    with self.assertRaisesRegex(AssertionError, "expected application"):
                        check_policy(manifests)

    def test_debug_requires_explicit_merger_replacement(self):
        """Require debug to replace the inherited release mode explicitly."""
        manifests = self.manifests()
        manifests["debug"].find("application").set(TOOLS + "replace", "android:name")
        with self.assertRaisesRegex(AssertionError, "must replace"):
            check_policy(manifests)

    def test_missing_manifest_is_not_an_inheritance_pass(self):
        """Treat missing required source manifests as incomplete evidence."""
        for source_set in ("main", "debug"):
            with self.subTest(source_set=source_set):
                manifests = self.manifests()
                del manifests[source_set]
                with self.assertRaisesRegex(AssertionError, "manifests are required"):
                    check_policy(manifests)

    def test_flavor_and_process_overrides_are_rejected(self):
        """Reject flavor and process overrides of the inherited application policy."""
        for source_set, tag in (("zapstore", "application"), ("main", "process")):
            with self.subTest(source_set=source_set, tag=tag):
                manifests = self.manifests()
                application = manifests[source_set].find("application")
                if application is None:
                    application = ET.SubElement(manifests[source_set], "application")
                node = application if tag == "application" else ET.SubElement(application, "process")
                node.set(ANDROID + "memtagMode", "off")
                with self.assertRaisesRegex(AssertionError, "override"):
                    check_policy(manifests)

    def test_merger_markers_cannot_discard_inherited_policy(self):
        """Reject merger directives that erase the inherited tagging policy."""
        for marker, value in (("node", "replace"), ("node", "remove"), ("remove", "android:memtagMode")):
            with self.subTest(marker=marker, value=value):
                manifests = self.manifests()
                application = manifests["zapstore"].find("application")
                if application is None:
                    application = ET.SubElement(manifests["zapstore"], "application")
                application.set(TOOLS + marker, value)
                with self.assertRaisesRegex(AssertionError, "discards tagging policy"):
                    check_policy(manifests)


if __name__ == '__main__':
    unittest.main()
