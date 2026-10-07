"""Reject wrong-source APKs and ensure the optional pilot cannot replace normal CI."""

from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import re
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock
import zipfile

from scripts import maestro_apk as pilot


ROOT = Path(__file__).resolve().parents[1]
SOURCE = 'a' * 40


def producer():
    return {'id': 123, 'run_attempt': 1, 'head_sha': SOURCE, 'head_branch': 'feature',
            'repository': {'full_name': pilot.REPOSITORY}, 'head_repository': {'full_name': pilot.REPOSITORY},
            'path': pilot.WORKFLOW, 'event': 'pull_request', 'status': 'completed', 'conclusion': 'success'}


def artifact():
    return {'id': 456, 'expired': False, 'size_in_bytes': 100,
            'expires_at': (datetime.now(timezone.utc) + timedelta(days=1)).isoformat(),
            'digest': 'sha256:' + 'b' * 64, 'workflow_run': {'id': 123, 'head_sha': SOURCE},
            'name': f'maestro-dev-apk-{SOURCE}-123-1'}


class MaestroArtifactTest(unittest.TestCase):
    def test_exact_successful_internal_producer(self):
        pilot.validate_metadata(artifact(), producer(), '456', SOURCE)

    def test_wrong_provenance_is_rejected(self):
        for key, value in [('head_sha', 'c' * 40), ('path', '.github/workflows/other.yml'),
                           ('conclusion', 'failure'), ('status', 'in_progress'), ('event', 'workflow_dispatch'),
                           ('head_repository', {'full_name': 'someone/fork'}), ('run_attempt', 2)]:
            with self.subTest(field=key):
                run = producer()
                run[key] = value
                with self.assertRaises(ValueError):
                    pilot.validate_metadata(artifact(), run, '456', SOURCE)
        for key, value in [('expired', True), ('expires_at', '2020-01-01T00:00:00+00:00'),
                           ('digest', ''), ('digest', None), ('digest', 123),
                           ('id', 457), ('size_in_bytes', pilot.MAX_APK_BYTES * 2),
                           ('workflow_run', {'id': 999, 'head_sha': SOURCE}), ('name', 'unrelated-apk')]:
            with self.subTest(field=key):
                selected = artifact()
                selected[key] = value
                with self.assertRaises(ValueError):
                    pilot.validate_metadata(selected, producer(), '456', SOURCE)

    def test_untrusted_inputs_are_not_shell_commands(self):
        pilot.validate_inputs('456', SOURCE, '20')
        for values in [('', SOURCE, '1'), ('456;echo bad', SOURCE, '1'), ('456', 'main', '1'),
                       ('456', SOURCE, '0'), ('456', SOURCE, '21'), ('456', SOURCE, '1\n2')]:
            with self.subTest(values=values), self.assertRaises(ValueError):
                pilot.validate_inputs(*values)

    def test_busy_and_incomplete_capacity_fail_closed(self):
        pilot.check_idle_runs({'total_count': 1, 'workflow_runs': [{'id': 99, 'path': 'pilot.yml'}]}, '99')
        for payload in [{'total_count': 100, 'workflow_runs': []}, {},
                        {'total_count': 1, 'workflow_runs': [{'id': 98, 'path': pilot.WORKFLOW}]},
                        {'total_count': 1, 'workflow_runs': [{'id': 98}]}]:
            with self.subTest(payload=payload), self.assertRaises(ValueError):
                pilot.check_idle_runs(payload, '99')

    def make_archive(self, directory, *, arm64=False, manifest_changes=None, extra=None):
        apk = directory / 'fixture.apk'
        elf = b'\x7fELF\x02\x01' + bytes(12) + b'\x3e\x00'
        with zipfile.ZipFile(apk, 'w') as zipped:
            zipped.writestr('lib/arm64-v8a/libmarmot_uniffi.so' if arm64 else pilot.NATIVE_LIBRARY, elf)
        manifest = {'schema': 1, 'repository': pilot.REPOSITORY, 'workflow': pilot.WORKFLOW,
                    'source_sha': SOURCE, 'checkout_sha': 'd' * 40, 'run_id': 123, 'run_attempt': 1,
                    'application_id': pilot.PACKAGE, 'variant': 'devZapstoreBenchmarkRelease',
                    'apk_sha256': pilot.sha256(apk)}
        manifest.update(manifest_changes or {})
        archive = directory / 'artifact.zip'
        with zipfile.ZipFile(archive, 'w') as zipped:
            zipped.write(apk, 'app.apk')
            zipped.writestr('provenance.json', json.dumps(manifest))
            if extra:
                zipped.writestr(extra, b'bad')
        selected = artifact()
        selected['digest'] = 'sha256:' + pilot.sha256(archive)
        return archive, selected

    def test_archive_integrity_and_native_architecture(self):
        for changes in [{}, {'arm64': True}, {'extra': '../escape'},
                        {'manifest_changes': {'source_sha': 'e' * 40}},
                        {'manifest_changes': {'checkout_sha': None}},
                        {'manifest_changes': {'apk_sha256': 'f' * 64}}]:
            with self.subTest(changes=changes), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary)
                archive, selected = self.make_archive(directory, **changes)
                if not changes:
                    result = pilot.extract_verified(archive, directory / 'out', selected, producer(), SOURCE)
                    self.assertEqual(result['source_sha'], SOURCE)
                else:
                    with self.assertRaises(ValueError):
                        pilot.extract_verified(archive, directory / 'out', selected, producer(), SOURCE)

    def test_api_digest_is_checked_before_extraction(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            archive, selected = self.make_archive(directory)
            selected['digest'] = 'sha256:' + '0' * 64
            with self.assertRaisesRegex(ValueError, 'archive digest'):
                pilot.extract_verified(archive, directory / 'out', selected, producer(), SOURCE)
            self.assertFalse((directory / 'out').exists())


    def test_stage_records_existing_apk_without_a_build(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            self.make_archive(directory)
            with mock.patch.dict(os.environ, {'APK_SOURCE_SHA': SOURCE, 'GITHUB_RUN_ID': '123',
                                              'GITHUB_RUN_ATTEMPT': '1'}), \
                    mock.patch.object(pilot.subprocess, 'check_output', return_value='d' * 40 + '\n') as git:
                pilot.stage(directory / 'fixture.apk', directory / 'stage')
            git.assert_called_once_with(['git', 'rev-parse', 'HEAD'], text=True)
            manifest = json.loads((directory / 'stage/provenance.json').read_text())
            self.assertEqual(manifest['source_sha'], SOURCE)
            self.assertEqual(manifest['checkout_sha'], 'd' * 40)
            self.assertEqual((manifest['run_id'], manifest['run_attempt']), (123, 1))
            self.assertEqual(manifest['apk_sha256'], pilot.sha256(directory / 'stage/app.apk'))
            self.assertEqual((directory / 'stage/app.apk').read_bytes(), (directory / 'fixture.apk').read_bytes())

    def test_fetch_records_identity_and_rejects_wrong_archive_size(self):
        for wrong_size in (False, True):
            with self.subTest(wrong_size=wrong_size), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary)
                archive, selected = self.make_archive(directory)
                selected['size_in_bytes'] = archive.stat().st_size + int(wrong_size)
                destination = directory / 'reports/apk'
                def download(command, *, stdout, check, timeout):
                    self.assertEqual(command, ['gh', 'api', f'repos/{pilot.REPOSITORY}/actions/artifacts/456/zip'])
                    self.assertTrue(check)
                    self.assertEqual(timeout, 120)
                    stdout.write(archive.read_bytes())
                with mock.patch.dict(os.environ, {'GITHUB_SHA': 'e' * 40, 'GITHUB_RUN_ID': '999'}), \
                        mock.patch.object(pilot, 'idle') as idle, \
                        mock.patch.object(pilot, 'api', side_effect=[selected, producer()]), \
                        mock.patch.object(pilot.subprocess, 'run', side_effect=download):
                    if wrong_size:
                        with self.assertRaisesRegex(ValueError, 'archive size'):
                            pilot.fetch('456', SOURCE, '20', destination)
                        self.assertFalse(destination.exists())
                    else:
                        pilot.fetch('456', SOURCE, '20', destination)
                        evidence = json.loads((destination.parent / 'selection.json').read_text())
                        self.assertEqual(evidence['producer']['source_sha'], SOURCE)
                        self.assertEqual(evidence['artifact_digest'], selected['digest'])
                        self.assertEqual(evidence['pilot_sha'], 'e' * 40)
                        self.assertEqual(evidence['repetitions'], 20)
                    idle.assert_called_once_with()


class MaestroLocaleTest(unittest.TestCase):
    def test_persisted_and_fresh_image_properties_follow_android_order(self):
        cases = [({'persist.sys.locale': 'en-US'}, 'en-US'),
                 ({'ro.product.locale': 'en-US'}, 'en-US'),
                 ({'persist.sys.locale': 'null', 'ro.product.locale': 'en-US'}, 'en-US'),
                 ({'persist.sys.locale': 'fr-FR', 'ro.product.locale': 'en-US'}, 'fr-FR'),
                 ({'persist.sys.language': 'fr', 'persist.sys.country': 'CA',
                   'ro.product.locale': 'en-US'}, 'fr-CA'),
                 ({'ro.product.locale.language': 'en', 'ro.product.locale.region': 'US'}, 'en-US')]
        for properties, expected in cases:
            with self.subTest(properties=properties), mock.patch.dict(os.environ, {'GITHUB_ACTIONS': 'true'}):
                def getprop(command, *, text, timeout):
                    self.assertEqual(command[:5], ['adb', '-s', 'emulator-5554', 'shell', 'getprop'])
                    self.assertTrue(text)
                    self.assertEqual(timeout, 10)
                    return properties.get(command[-1], '') + '\r\n'
                with mock.patch.object(pilot.subprocess, 'check_output', side_effect=getprop):
                    self.assertEqual(pilot.device_locale(), expected)

    def test_unknown_locale_and_non_ci_device_probe_fail_closed(self):
        with mock.patch.dict(os.environ, {'GITHUB_ACTIONS': 'true'}), \
                mock.patch.object(pilot.subprocess, 'check_output', return_value='null\n'):
            with self.assertRaisesRegex(ValueError, 'Cannot determine'):
                pilot.device_locale()
        with mock.patch.dict(os.environ, {'GITHUB_ACTIONS': 'false'}), \
                mock.patch.object(pilot.subprocess, 'check_output') as adb:
            with self.assertRaisesRegex(ValueError, 'disposable'):
                pilot.device_locale()
            adb.assert_not_called()


class MaestroWorkflowTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = (ROOT / '.github/workflows/android-instrumented.yml').read_text()

    def job(self, name):
        text = self.workflow.split(f'\n  {name}:\n', 1)[1]
        return re.split(r'\n  [a-z][a-z-]*:\n', text, maxsplit=1)[0]

    def enabled(self, name, event, selected):
        expression = re.search(r'^    if: (.+)$', self.job(name), re.MULTILINE).group(1)
        expression = expression.replace('&&', ' and ').replace('||', ' or ').replace('!inputs.', 'not inputs.')
        return eval(expression, {'__builtins__': {}},
                    {'github': SimpleNamespace(event_name=event), 'inputs': SimpleNamespace(maestro_pilot=selected)})

    def test_pilot_and_normal_jobs_are_exclusive_for_every_event(self):
        for event in ['push', 'pull_request', 'workflow_dispatch']:
            for selected in [True, False]:
                with self.subTest(event=event, selected=selected):
                    pilot_enabled = event == 'workflow_dispatch' and selected
                    self.assertEqual(self.enabled('maestro-pilot', event, selected), pilot_enabled)
                    for normal in ['instrumented', 'attachment-fixture']:
                        self.assertEqual(self.enabled(normal, event, selected), not pilot_enabled)

    def test_no_pilot_build_secrets_or_cancellation_of_normal_runs(self):
        job = self.job('maestro-pilot')
        self.assertNotIn('gradlew', job)
        self.assertNotIn('secrets.', job)
        self.assertIn('actions: read', job)
        self.assertIn("&& 'android-maestro-pilot' || format('android-instrumented-{0}-{1}'", self.workflow)
        self.assertIn("cancel-in-progress: ${{ !(github.event_name == 'workflow_dispatch' && inputs.maestro_pilot) }}", self.workflow)
        self.assertIn('if: always()', job)
        self.assertIn('retention-days: 7', job)
        self.assertIn('python3 scripts/maestro_apk.py idle', job)

    def test_real_journey_and_negative_control_keep_assertions(self):
        flow = (ROOT / '.maestro/onboarding.yaml').read_text()
        self.assertIn('clearState: true', flow)
        self.assertIn('all: deny', flow)
        self.assertEqual(flow.count('- assertVisible: "Sign Up"'), 2)
        self.assertIn('- assertVisible: "Private key"', flow)
        self.assertIn('- assertNotVisible: "Private key"', flow)
        self.assertNotIn('inputText', flow)
        runner = (ROOT / 'scripts/run-maestro-pilot.sh').read_text()
        self.assertIn('MAESTRO_NEGATIVE_CONTROL_IMPOSSIBLE_3141', runner)
        self.assertNotIn('|| true', runner.split('maestro --device', 1)[1])

    def test_pilot_retention_cannot_fail_required_baseline_verification(self):
        workflow = (ROOT / '.github/workflows/android-ci.yml').read_text()
        def step(name):
            return workflow.split(f'      - name: {name}\n', 1)[1].split('\n      - name:', 1)[0]
        baseline = step('Verify packaged Baseline Profile assets')
        stage = step('Stage the existing APK for optional Maestro testing')
        upload = step('Retain the existing APK for optional Maestro testing')
        self.assertNotIn('continue-on-error:', baseline)
        self.assertNotIn('maestro_apk.py', baseline)
        self.assertIn('verify-baseline-profile.sh', baseline)
        self.assertIn('continue-on-error: true', stage)
        self.assertIn('continue-on-error: true', upload)
        self.assertIn("if: steps.maestro_stage.outcome == 'success'", upload)
        expression = re.search(r'^        if: (.+)$', stage, re.MULTILINE).group(1)
        expression = expression.replace('&&', ' and ').replace('||', ' or ')
        for event in ('push', 'pull_request', 'schedule', 'workflow_dispatch'):
            for internal in (True, False):
                for phase in ('tooling', 'baseline'):
                    with self.subTest(event=event, internal=internal, phase=phase):
                        repo = pilot.REPOSITORY if internal else 'someone/fork'
                        github = SimpleNamespace(event_name=event, repository=pilot.REPOSITORY,
                            event=SimpleNamespace(pull_request=SimpleNamespace(
                                head=SimpleNamespace(repo=SimpleNamespace(full_name=repo)))))
                        enabled = eval(expression, {'__builtins__': {}},
                                       {'github': github, 'matrix': SimpleNamespace(phase=phase)})
                        self.assertEqual(enabled, phase == 'baseline' and
                                         (event == 'push' or (event == 'pull_request' and internal)))


if __name__ == '__main__':
    unittest.main()
