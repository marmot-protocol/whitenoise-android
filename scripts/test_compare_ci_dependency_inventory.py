"""Exact-source CI baseline retrieval without resolution or compilation."""
import io
import json
from pathlib import Path
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
            {'id': 2, 'name': 'fixture', 'expired': False, 'size_in_bytes': len(stream.getvalue())}]}, stream.getvalue()]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); after = root / 'after.json'; output = root / 'delta.json'
            after.write_text(json.dumps(inventory))
            with mock.patch.object(ci, 'api', side_effect=responses), mock.patch('sys.argv', [
                    'compare', '--base-source', SOURCE, '--artifact-name', 'fixture',
                    '--after', str(after), '--output', str(output)]):
                ci.main()
            delta = json.loads(output.read_text())
            self.assertEqual(delta['status'], 'compared')
            self.assertEqual(delta['baseline'], {'run_id': 1, 'artifact_id': 2})
            self.assertEqual(delta['added'], [])

    def test_missing_baseline_is_unavailable_and_wrong_source_is_rejected(self):
        """An expired/missing baseline is not an empty comparison or a selection removal."""
        with mock.patch.object(ci, 'api', return_value={'workflow_runs': []}):
            self.assertIsNone(ci.baseline(SOURCE, 'fixture')[0])
        with mock.patch.object(ci, 'api', return_value={'workflow_runs': [{'head_sha': 'b' * 40}]}):
            with self.assertRaisesRegex(ValueError, 'Wrong-source'):
                ci.baseline(SOURCE, 'fixture')


if __name__ == '__main__':
    unittest.main()
