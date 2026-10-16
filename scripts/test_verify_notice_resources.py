"""Verify the real APK-resource boundary without compiling a replacement APK."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from scripts.verify_notice_resources import notice_rows, packaged_notices, require_baseline


def write_retained_baseline(repository, baseline, metadata, texts):
    """Retain original notice bytes under a receipt and bind the baseline to them."""
    evidence = repository / 'config/licenses/evidence'
    evidence.mkdir(parents=True, exist_ok=True)
    files = {}
    for name, data in [('third_party_license_metadata', metadata), ('third_party_licenses', texts)]:
        (evidence / name).write_bytes(data)
        files['config/licenses/evidence/' + name] = hashlib.sha256(data).hexdigest()
    receipt = evidence / 'capture.json'
    receipt.write_text(json.dumps({'schema': 1, 'source_sha': baseline['source_sha'], 'files': files}))
    baseline['retained'] = {'path': 'config/licenses/evidence/capture.json',
                            'sha256': hashlib.sha256(receipt.read_bytes()).hexdigest()}
    baseline['resources'] = {'metadata_sha256': files['config/licenses/evidence/third_party_license_metadata'],
                             'text_sha256': files['config/licenses/evidence/third_party_licenses']}
    path = repository / 'config/licenses/baseline.json'
    path.write_text(json.dumps(baseline))
    return path


class PackagedNoticeTest(unittest.TestCase):
    """Retained resources must preserve source-bound attribution, not just file presence."""

    def setUp(self):
        """Use obfuscated APK paths so checks must follow aapt2's resource table."""
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.text = b'Copyright owner\n'
        self.metadata = f'0:{len(self.text)} Library\n'.encode()
        self.icons = b'Copyright icon owners\nApache 2.0\n'
        self.expected = {
            'schema': 1, 'source_sha': 'a' * 40,
            'material_icons_sha256': hashlib.sha256(self.icons).hexdigest(),
            'required_notices': [{'name': 'Library', 'text_sha256': hashlib.sha256(self.text).hexdigest()}],
        }
        self.baseline = write_retained_baseline(self.root, self.expected, self.metadata, self.text)
        self.dump = '''resource 0x7f010001 raw/third_party_license_metadata
  () (file) res/a
resource 0x7f010002 raw/third_party_licenses
  () (file) res/b
resource 0x7f010003 raw/material_icons_notice
  () (file) res/c
'''

    def test_actual_obfuscated_resource_bytes_are_checked(self):
        """A valid table maps stable R names to the packaged payloads used by Android."""
        apk = self.root / 'release.apk'
        with zipfile.ZipFile(apk, 'w') as archive:
            for path, data in [('res/a', self.metadata), ('res/b', self.text), ('res/c', self.icons)]:
                archive.writestr(path, data)
        with patch('scripts.verify_notice_resources.subprocess.check_output', return_value=self.dump):
            packaged_notices(apk, Path('fixture-aapt2'), self.baseline)

    def test_well_formed_but_incomplete_or_changed_attribution_fails(self):
        """Syntactically valid notices cannot replace the original notice or copyright."""
        with self.assertRaisesRegex(ValueError, 'lost or changed'):
            require_baseline(b'0:5 Other library\n', b'Other', self.icons, self.baseline)
        with self.assertRaisesRegex(ValueError, 'copyright/provenance'):
            require_baseline(self.metadata, self.text, b'Unrelated licence', self.baseline)

    def test_truncation_and_invalid_encoding_fail(self):
        """Offsets and valid UTF-8 remain necessary even when baseline hashes are present."""
        with self.assertRaisesRegex(ValueError, 'truncated'):
            require_baseline(self.metadata, self.text[:-1], self.icons, self.baseline)
        with self.assertRaises(UnicodeDecodeError):
            require_baseline(b'0:1 Library\n', b'\xff', self.icons, self.baseline)

    def test_baseline_must_equal_the_retained_original_output(self):
        """Editing the baseline together with its own pin cannot drop an original notice."""
        cases = [
            ({key: value for key, value in self.expected.items() if key != 'retained'}, 'missing source-bound'),
            (dict(self.expected, required_notices=[{'name': 'Other', 'text_sha256': 'b' * 64}]),
             'differs from the retained original'),
            (dict(self.expected, resources={'metadata_sha256': 'c' * 64,
                                            'text_sha256': self.expected['resources']['text_sha256']}),
             'do not match the baseline'),
        ]
        for baseline, message in cases:
            with self.subTest(message=message), self.assertRaisesRegex(ValueError, message):
                self.baseline.write_text(json.dumps(baseline))
                require_baseline(self.metadata, self.text, self.icons, self.baseline)

    def test_rehashed_original_evidence_cannot_drop_a_notice(self):
        """Rewriting the retained index and re-pinning its receipt still fails the baseline rows."""
        other = f'0:{len(self.text)} Other library\n'.encode()
        baseline = write_retained_baseline(self.root, dict(self.expected), other, self.text)
        with self.assertRaisesRegex(ValueError, 'differs from the retained original'):
            require_baseline(self.metadata, self.text, self.icons, baseline)

    def test_notice_rows_keep_duplicate_index_entries(self):
        """Repeated original rows stay visible so the baseline row count is exact."""
        metadata = self.metadata + self.metadata
        self.assertEqual(2, len(notice_rows(metadata, self.text)))

    def test_missing_resource_reference_fails(self):
        """A removed stable raw-resource ID fails before an installation is attempted."""
        with patch('scripts.verify_notice_resources.subprocess.check_output', return_value=''):
            with self.assertRaisesRegex(ValueError, 'dropped required'):
                packaged_notices(self.root / 'unused.apk', Path('fixture-aapt2'), self.baseline)


if __name__ == '__main__':
    unittest.main()
