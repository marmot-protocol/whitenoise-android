"""Exercise attribution failures and the actual reader's UTF-8 range contract."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from scripts import generate_local_notices as notices


class LocalNoticesTest(unittest.TestCase):
    """Use independently decoded output rather than duplicating generator internals."""

    def setUp(self):
        """Create two binary identities sharing one non-ASCII licence text."""
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / 'texts').mkdir()
        self.text = 'Copyright Café\n许可文本\n'.encode('utf-8')
        sha = hashlib.sha256(self.text).hexdigest()
        (self.root / 'texts/notice.txt').write_bytes(self.text)
        self.selected = [
            {'coordinate': 'example:one:1.0', 'name': 'one-1.0.jar', 'sha256': 'a' * 64},
            {'coordinate': 'example:two:1.0', 'name': 'two-1.0.aar', 'sha256': 'b' * 64},
        ]
        self.catalog = {
            'schema': 1,
            'reviewed_from': {'source_sha': 'c' * 40, 'evidence': 'Synthetic local source fixture'},
            'artifacts': [dict(a, notice_ids=[str(i)]) for i, a in enumerate(self.selected)],
            'notices': [
                {'id': str(i), 'name': name, 'text': 'texts/notice.txt',
                 'sha256': sha, 'source': 'Synthetic reviewed copyright fixture'}
                for i, name in enumerate(['Zêta library', 'Alpha library'])
            ],
        }
        self.path = self.root / 'catalog.json'

    def write_catalog(self):
        """Persist the case's independent inputs before invoking the production generator."""
        self.path.write_text(json.dumps(self.catalog), encoding='utf-8')

    def test_byte_ranges_and_order_are_deterministic(self):
        """Multibyte text uses byte lengths, shared storage, and stable visible names."""
        self.write_catalog()
        first = self.root / 'first'
        second = self.root / 'second'
        self.assertEqual(2, notices.generate(self.path, self.selected, first))
        self.catalog['artifacts'].reverse()
        self.catalog['notices'].reverse()
        self.write_catalog()
        notices.generate(self.path, list(reversed(self.selected)), second)
        metadata = (first / 'third_party_license_metadata').read_bytes()
        data = (first / 'third_party_licenses').read_bytes()
        self.assertEqual(metadata, (second / 'third_party_license_metadata').read_bytes())
        self.assertEqual(data, (second / 'third_party_licenses').read_bytes())
        decoded = []
        for line in metadata.decode('utf-8').splitlines():
            span, name = line.split(' ', 1)
            offset, length = map(int, span.split(':'))
            decoded.append((name, data[offset:offset + length].decode('utf-8')))
        self.assertEqual([('Alpha library', self.text.decode()), ('Zêta library', self.text.decode())], decoded)
        self.assertEqual(len(self.text) + 1, len(data))

    def test_changed_artifact_or_unreviewed_dependency_fails(self):
        """The selected graph cannot inherit attribution from a different byte identity."""
        self.write_catalog()
        changed = dict(self.selected[0], sha256='d' * 64)
        unknown = dict(self.selected[0], coordinate='example:new:1.0')
        for item in [changed, unknown]:
            with self.subTest(item=item), self.assertRaisesRegex(ValueError, 'Unreviewed runtime artifact'):
                notices.generate(self.path, [item], self.root / 'output')
        self.assertFalse((self.root / 'output').exists())

    def test_missing_or_truncated_text_fails(self):
        """A catalogue entry cannot mask incomplete or missing checked-in attribution."""
        self.write_catalog()
        text = self.root / 'texts/notice.txt'
        text.write_bytes(self.text[:-1])
        with self.assertRaisesRegex(ValueError, 'Changed or truncated'):
            notices.generate(self.path, self.selected, self.root / 'output')
        text.unlink()
        with self.assertRaisesRegex(ValueError, 'Missing notice text'):
            notices.generate(self.path, self.selected, self.root / 'output')

    def test_omitted_attribution_and_empty_selection_fail(self):
        """Neither unassigned binaries nor an unresolved empty graph can produce resources."""
        self.catalog['artifacts'][0]['notice_ids'] = []
        self.write_catalog()
        with self.assertRaisesRegex(ValueError, 'Missing or ambiguous artifact attribution'):
            notices.generate(self.path, self.selected, self.root / 'output')
        self.catalog['artifacts'][0]['notice_ids'] = ['0']
        self.write_catalog()
        with self.assertRaisesRegex(ValueError, 'Empty selected runtime'):
            notices.generate(self.path, [], self.root / 'output')

    def test_wrong_provenance_and_escaped_text_fail(self):
        """Reviewed source identity and local path containment are required independently."""
        self.catalog['reviewed_from']['source_sha'] = 'unknown'
        self.write_catalog()
        with self.assertRaisesRegex(ValueError, 'provenance'):
            notices.generate(self.path, self.selected, self.root / 'output')
        self.catalog['reviewed_from']['source_sha'] = 'c' * 40
        self.catalog['notices'][0]['text'] = '../outside.txt'
        self.write_catalog()
        with self.assertRaisesRegex(ValueError, 'Unsafe notice text path'):
            notices.generate(self.path, self.selected, self.root / 'output')

    def test_invalid_utf8_is_rejected_even_when_digest_matches(self):
        """A byte fingerprint does not make undecodable text compatible with the reader."""
        text = b'\xffinvalid'
        (self.root / 'texts/notice.txt').write_bytes(text)
        for entry in self.catalog['notices']:
            entry['sha256'] = hashlib.sha256(text).hexdigest()
        self.write_catalog()
        with self.assertRaises(UnicodeDecodeError):
            notices.generate(self.path, self.selected, self.root / 'output')

    def test_duplicate_selection_and_json_keys_fail(self):
        """Ambiguous input records cannot be silently overwritten or counted once."""
        self.write_catalog()
        with self.assertRaisesRegex(ValueError, 'Duplicate selected runtime artifact'):
            notices.generate(self.path, [self.selected[0]] * 2, self.root / 'output')
        self.path.write_text('{"schema":1,"schema":2}', encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'Duplicate JSON key'):
            notices.read_catalog(self.path)


if __name__ == '__main__':
    unittest.main()
