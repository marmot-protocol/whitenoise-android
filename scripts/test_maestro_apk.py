"""Reject wrong-source APKs and ensure the optional pilot cannot replace normal CI."""

from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import re
import tempfile
from types import SimpleNamespace
import unittest
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
                           ('digest', ''), ('id', 457), ('size_in_bytes', pilot.MAX_APK_BYTES * 2),
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


if __name__ == '__main__':
    unittest.main()
