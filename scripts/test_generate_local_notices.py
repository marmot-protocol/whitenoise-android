"""Exercise attribution failures and the actual reader's UTF-8 range contract."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import copy

from scripts import generate_local_notices as notices


class LocalNoticesTest(unittest.TestCase):
    """Use independently decoded output rather than duplicating generator internals."""

    def setUp(self):
        """Create two binary identities sharing one non-ASCII licence text."""
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / 'config/licenses'
        (self.root / 'texts').mkdir(parents=True)
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
        icons = self.root.parents[1] / 'app/src/main/res/raw/material_icons_notice.txt'
        icons.parent.mkdir(parents=True)
        icons.write_bytes(b'Synthetic icon attribution')
        self.baseline = {'schema': 1, 'source_sha': 'c' * 40,
                         'material_icons_sha256': hashlib.sha256(icons.read_bytes()).hexdigest(),
                         'required_notices': [{'name': n['name'], 'text_sha256': n['sha256']}
                                              for n in self.catalog['notices']]}
        baseline = self.root / 'baseline.json'
        baseline.write_text(json.dumps(self.baseline))
        self.catalog['baseline'] = {'source_sha': 'c' * 40,
                                    'sha256': hashlib.sha256(baseline.read_bytes()).hexdigest()}

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

    def test_catalogue_cannot_drop_an_original_notice(self):
        """A valid remaining assignment cannot silently remove a Firebase/other baseline notice."""
        self.catalog['artifacts'][1]['notice_ids'] = ['0']
        self.write_catalog()
        with self.assertRaisesRegex(ValueError, 'original notices were lost or changed'):
            notices.generate(self.path, self.selected, self.root / 'output')
        self.assertFalse((self.root / 'output').exists())

    def test_wrong_source_baseline_and_icon_loss_fail_generation(self):
        """Source and icon retention qualify generation before any APK workflow runs."""
        self.write_catalog()
        baseline = self.root / 'baseline.json'
        original = baseline.read_bytes()
        changed = dict(self.baseline, source_sha='d' * 40)
        baseline.write_text(json.dumps(changed))
        with self.assertRaisesRegex(ValueError, 'Wrong-source or changed'):
            notices.generate(self.path, self.selected, self.root / 'output')
        baseline.write_bytes(original)
        (self.root.parents[1] / 'app/src/main/res/raw/material_icons_notice.txt').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'icon copyright'):
            notices.generate(self.path, self.selected, self.root / 'output')

    def test_committed_catalogue_preserves_every_baseline_notice(self):
        """Use real reviewed inputs and prove a valid Firebase attribution deletion is rejected."""
        root = Path(__file__).resolve().parents[1]
        path = root / 'config/licenses/catalog.json'
        catalog = json.loads(path.read_text())
        output = self.root / 'real-output'
        notices.generate(path, catalog['artifacts'], output)
        changed = copy.deepcopy(catalog)
        removed = next(n for n in changed['notices'] if n['name'] == 'Firebase')
        replacement = next(n['id'] for n in changed['notices'] if n['id'] != removed['id'])
        for artifact in changed['artifacts']:
            artifact['notice_ids'] = list(dict.fromkeys(replacement if i == removed['id'] else i
                                                       for i in artifact['notice_ids']))
        changed_path = self.root / 'changed-catalog.json'
        # Retain real reviewed text paths beside the edited catalogue, with pinned bytes.
        for notice in changed['notices']:
            target = self.root / notice['text']
            target.write_bytes((path.parent / notice['text']).read_bytes())
        changed_path.write_text(json.dumps(changed))
        with self.assertRaisesRegex(ValueError, 'original notices were lost or changed'):
            notices.generate(changed_path, changed['artifacts'], self.root / 'changed-output',
                             root / 'config/licenses/baseline.json',
                             root / 'app/src/main/res/raw/material_icons_notice.txt')


if __name__ == '__main__':
    unittest.main()
