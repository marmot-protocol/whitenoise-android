"""Exact-source CI baseline retrieval without resolution or compilation."""
from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock
import zipfile

from scripts import compare_ci_dependency_inventory as ci
from scripts.test_dependency_inventory import SOURCE, report
from scripts.dependency_inventory import selected_inventory


class CiInventoryComparisonTest(unittest.TestCase):
    """Missing evidence is explicit; stale evidence must fail instead of becoming an empty delta."""

    def test_exact_base_artifact_is_compared_by_cli(self):
        """Exercise run, artifact and embedded source binding through the real entrypoint."""
        inventory = selected_inventory([report()], SOURCE)
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, 'w') as archive:
            archive.writestr('inventory/selected.json', json.dumps(inventory))
        run = {'id': 1, 'head_sha': SOURCE, 'head_branch': 'master', 'event': 'push', 'conclusion': 'success',
               'path': '.github/workflows/android-ci.yml', 'repository': {'full_name': ci.REPOSITORY},
               'head_repository': {'full_name': ci.REPOSITORY}}
        responses = [{'workflow_runs': [run]}, {'artifacts': [
            {'id': 2, 'name': 'fixture', 'expired': False, 'created_at': '2026-10-15T10:00:00Z',
             'size_in_bytes': len(stream.getvalue())}]}, stream.getvalue()]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); after = root / 'after.json'; output = root / 'delta.json'
            after.write_text(json.dumps(inventory))
            with mock.patch.object(ci, 'api', side_effect=responses), mock.patch('sys.argv', [
                    'compare', '--base-source', SOURCE, '--artifact-name', 'fixture',
                    '--after', str(after), '--after-source', SOURCE, '--output', str(output)]):
                ci.main()
            delta = json.loads(output.read_text())
            self.assertEqual(delta['status'], 'compared')
            self.assertEqual(delta['baseline'], {'run_id': 1, 'artifact_id': 2})
            self.assertEqual(delta['added'], [])

    def test_large_valid_baseline_retains_a_bounded_uncompressed_limit(self):
        """Current tooling inventories exceed 32 MiB; oversized members still fail closed."""
        inventory = selected_inventory([report()], SOURCE)
        encoded = json.dumps(inventory).encode()
        run = {'id': 1, 'head_sha': SOURCE, 'head_branch': 'master', 'event': 'push', 'conclusion': 'success',
               'path': '.github/workflows/android-ci.yml', 'repository': {'full_name': ci.REPOSITORY},
               'head_repository': {'full_name': ci.REPOSITORY}}
        for size, accepted in [(32 * 1024 * 1024 + 1, True), (64 * 1024 * 1024 + 1, False)]:
            stream = io.BytesIO()
            with zipfile.ZipFile(stream, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
                archive.writestr('inventory/selected.json', encoded + b' ' * (size - len(encoded)))
            responses = [{'workflow_runs': [run]}, {'artifacts': [
                {'id': 2, 'name': 'fixture', 'expired': False, 'created_at': '2026-10-15T10:00:00Z',
                 'size_in_bytes': len(stream.getvalue())}]}, stream.getvalue()]
            with self.subTest(size=size), mock.patch.object(ci, 'api', side_effect=responses):
                if accepted:
                    value, evidence = ci.baseline(SOURCE, 'fixture')
                    self.assertEqual(value, inventory)
                    self.assertEqual(evidence, {'run_id': 1, 'artifact_id': 2})
                else:
                    with self.assertRaisesRegex(ValueError, 'Missing or oversized baseline inventory'):
                        ci.baseline(SOURCE, 'fixture')

    def test_rerun_uses_newest_unexpired_matching_artifact(self):
        """Retained older attempts and timestamp ties cannot make a successful rerun ambiguous."""
        inventory = selected_inventory([report()], SOURCE)
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, 'w') as archive:
            archive.writestr('inventory/selected.json', json.dumps(inventory))
        run = {'id': 1, 'head_sha': SOURCE, 'head_branch': 'master', 'event': 'push', 'conclusion': 'success',
               'path': '.github/workflows/android-ci.yml', 'repository': {'full_name': ci.REPOSITORY},
               'head_repository': {'full_name': ci.REPOSITORY}}
        artifact = {'name': 'fixture', 'expired': False, 'size_in_bytes': len(stream.getvalue())}
        artifacts = [{**artifact, 'id': 3, 'created_at': '2026-10-15T11:00:00Z'},
                     {**artifact, 'id': 2, 'created_at': '2026-10-15T10:00:00Z'},
                     {**artifact, 'id': 5, 'created_at': '2026-10-15T12:00:00Z', 'expired': True},
                     {**artifact, 'id': 4, 'created_at': '2026-10-15T11:00:00Z'},
                     {**artifact, 'id': 6, 'created_at': '2026-10-15T13:00:00Z', 'name': 'other'}]
        responses = [{'workflow_runs': [run]}, {'artifacts': artifacts}, stream.getvalue()]
        with mock.patch.object(ci, 'api', side_effect=responses) as api:
            value, evidence = ci.baseline(SOURCE, 'fixture')
        self.assertEqual(value, inventory)
        self.assertEqual(evidence, {'run_id': 1, 'artifact_id': 4})
        self.assertEqual(api.call_args, mock.call('actions/artifacts/4/zip', binary=True))

    def test_api_failure_retains_unavailable_receipt_without_traceback(self):
        """Transport, rate-limit and timeout failures warn without claiming an empty delta."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); after = root / 'after.json'; output = root / 'delta.json'
            after.write_text(json.dumps(selected_inventory([report()], SOURCE)))
            errors = [subprocess.CalledProcessError(1, ['gh', 'api']),
                      subprocess.TimeoutExpired(['gh', 'api'], 60), OSError('gh unavailable')]
            for error in errors:
                with self.subTest(error=type(error).__name__), mock.patch.object(ci, 'api', side_effect=error), \
                        mock.patch('sys.argv', ['compare', '--base-source', SOURCE, '--artifact-name', 'fixture',
                                   '--after', str(after), '--after-source', SOURCE, '--output', str(output)]), \
                        redirect_stdout(io.StringIO()) as stdout:
                    ci.main()
                delta = json.loads(output.read_text())
                self.assertEqual(delta['status'], 'unavailable')
                self.assertEqual((delta['before_source'], delta['after_source']), (SOURCE, SOURCE))
                self.assertIn(type(error).__name__, delta['reason'])
                self.assertIn('::warning::', stdout.getvalue())
                self.assertNotIn('added', delta)

    def test_missing_baseline_is_unavailable_and_wrong_source_is_rejected(self):
        """An expired/missing baseline is not an empty comparison or a selection removal."""
        with mock.patch.object(ci, 'api', return_value={'workflow_runs': []}):
            self.assertIsNone(ci.baseline(SOURCE, 'fixture')[0])
        with mock.patch.object(ci, 'api', return_value={'workflow_runs': [{'head_sha': 'b' * 40}]}):
            with self.assertRaisesRegex(ValueError, 'Wrong-source'):
                ci.baseline(SOURCE, 'fixture')

    def test_non_json_api_response_is_retrieval_unavailable(self):
        """Error pages and truncated metadata are transport gaps, not invalid retained inventories."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); after = root / 'after.json'; output = root / 'delta.json'
            after.write_text(json.dumps(selected_inventory([report()], SOURCE)))
            for response in (b'<html>Bad Gateway</html>', b'{"workflow_runs":', b'\xff'):
                with self.subTest(response=response), \
                        mock.patch.object(ci.subprocess, 'check_output', return_value=response), \
                        mock.patch('sys.argv', ['compare', '--base-source', SOURCE, '--artifact-name', 'fixture',
                                   '--after', str(after), '--after-source', SOURCE, '--output', str(output)]):
                    ci.main()
                delta = json.loads(output.read_text())
                self.assertEqual(delta['status'], 'unavailable')
                self.assertIn('GitHub baseline retrieval unavailable', delta['reason'])
                self.assertNotIn('added', delta)

    def test_rejected_baseline_retains_source_bound_receipt_and_failure_exit(self):
        """Invalid evidence remains rejected while its reason survives the informational step."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); after = root / 'after.json'; output = root / 'delta.json'
            after.write_text(json.dumps(selected_inventory([report()], SOURCE)))
            errors = [ValueError('wrong source or duplicate keys'), json.JSONDecodeError('truncated inventory', '{', 1),
                      KeyError('missing API field'),
                      TypeError('malformed field'), AttributeError('non-object JSON'), zipfile.BadZipFile('bad ZIP')]
            for error in errors:
                with self.subTest(error=type(error).__name__), mock.patch.object(ci, 'baseline', side_effect=error), \
                        mock.patch('sys.argv', ['compare', '--base-source', SOURCE, '--artifact-name', 'fixture',
                                   '--after', str(after), '--after-source', SOURCE, '--output', str(output)]), \
                        redirect_stdout(io.StringIO()) as stdout, self.assertRaises(SystemExit) as failure:
                    ci.main()
                delta = json.loads(output.read_text())
                self.assertEqual(failure.exception.code, 1)
                self.assertEqual(delta['status'], 'rejected')
                self.assertEqual(delta['error_type'], type(error).__name__)
                self.assertEqual((delta['before_source'], delta['after_source']), (SOURCE, SOURCE))
                self.assertIn('::warning::Baseline evidence rejected:', stdout.getvalue())
                self.assertNotIn('added', delta)

    def test_legacy_raw_report_artifact_is_an_unavailable_baseline(self):
        """Pre-inventory CI artifacts retain their valid old contract without failing adoption."""
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, 'w') as archive:
            archive.writestr('_app-project-runtime.json', '{}')
        run = {'id': 1, 'head_sha': SOURCE, 'head_branch': 'master', 'event': 'push', 'conclusion': 'success',
               'path': '.github/workflows/android-ci.yml', 'repository': {'full_name': ci.REPOSITORY},
               'head_repository': {'full_name': ci.REPOSITORY}}
        responses = [{'workflow_runs': [run]}, {'artifacts': [
            {'id': 2, 'name': 'fixture', 'expired': False, 'created_at': '2026-10-15T10:00:00Z',
             'size_in_bytes': len(stream.getvalue())}]}, stream.getvalue()]
        with mock.patch.object(ci, 'api', side_effect=responses):
            self.assertIsNone(ci.baseline(SOURCE, 'fixture')[0])

    def test_baseline_duplicate_keys_are_rejected_before_returning_evidence(self):
        """Neither a shadow source identity nor a shadow graph can survive artifact decoding."""
        value = json.dumps(selected_inventory([report()], SOURCE))
        run = {'id': 1, 'head_sha': SOURCE, 'head_branch': 'master', 'event': 'push', 'conclusion': 'success',
               'path': '.github/workflows/android-ci.yml', 'repository': {'full_name': ci.REPOSITORY},
               'head_repository': {'full_name': ci.REPOSITORY}}
        for raw in [value.replace('{', '{"source_sha": "' + 'b' * 40 + '", ', 1),
                    value.replace('"release_runtime": {', '"release_runtime": {}, "release_runtime": {', 1)]:
            stream = io.BytesIO()
            with zipfile.ZipFile(stream, 'w') as archive:
                archive.writestr('inventory/selected.json', raw)
            responses = [{'workflow_runs': [run]}, {'artifacts': [
                {'id': 2, 'name': 'fixture', 'expired': False, 'created_at': '2026-10-15T10:00:00Z',
                 'size_in_bytes': len(stream.getvalue())}]}, stream.getvalue()]
            with self.subTest(raw=raw[:80]), mock.patch.object(ci, 'api', side_effect=responses):
                with self.assertRaisesRegex(ValueError, 'Duplicate inventory/evidence key'):
                    ci.baseline(SOURCE, 'fixture')

    def test_candidate_is_validated_even_when_no_baseline_is_available(self):
        """Candidate source and graph validity are independent of historical coverage."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); path = root / 'after.json'
            value = selected_inventory([report()], SOURCE)
            for mutate in [lambda v: v.update(source_sha='b' * 40), lambda v: v.update(schema=2)]:
                candidate = dict(value); mutate(candidate); path.write_text(json.dumps(candidate))
                with mock.patch.object(ci, 'baseline') as baseline, mock.patch('sys.argv', [
                        'compare', '--base-source', SOURCE, '--artifact-name', 'fixture',
                        '--after', str(path), '--after-source', SOURCE, '--output', str(root / 'delta.json')]):
                    with self.assertRaises(ValueError):
                        ci.main()
                    baseline.assert_not_called()



if __name__ == '__main__':
    unittest.main()
