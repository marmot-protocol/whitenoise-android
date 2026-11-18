"""Exercise the size-baseline ratchet against the real baseline file."""
from pathlib import Path
import unittest

from scripts.check_source_size_baseline import BASELINE, BaselineError, loosened, parse

ROOT = Path(__file__).resolve().parents[1]
TEXT = (ROOT / BASELINE).read_text(encoding='utf-8')
ENTRIES = parse(TEXT)
FIRST = next(iter(ENTRIES))


class SourceSizeBaselineCheckTest(unittest.TestCase):
    def test_real_baseline_parses(self):
        self.assertTrue(ENTRIES)
        self.assertEqual(loosened(ENTRIES, ENTRIES), [])

    def test_lowering_and_deleting_pass(self):
        head = {path: limit - 1 for path, limit in ENTRIES.items() if path != FIRST}
        self.assertEqual(loosened(ENTRIES, head), [])

    def test_raising_a_limit_fails(self):
        head = dict(ENTRIES, **{FIRST: ENTRIES[FIRST] + 1})
        self.assertEqual(loosened(ENTRIES, head), [f'raised {FIRST} from {ENTRIES[FIRST]} to {ENTRIES[FIRST] + 1}'])

    def test_adding_an_entry_fails(self):
        head = dict(ENTRIES, **{'dev/ipf/whitenoise/android/New.kt': 700})
        self.assertEqual(loosened(ENTRIES, head), ['added entry dev/ipf/whitenoise/android/New.kt (700)'])

    def test_other_entry_syntax_fails_closed(self):
        for line in ('Pair("dev/A.kt", 700),', '"dev/A.kt" to 700, "dev/B.kt" to 701,', '// "dev/A.kt" to 700,'):
            with self.subTest(line=line), self.assertRaises(BaselineError):
                parse(TEXT.replace('    mapOf(\n', f'    mapOf(\n        {line}\n'))

    def test_second_map_fails_closed(self):
        with self.assertRaises(BaselineError):
            parse(TEXT + '\ninternal val extra = mapOf("dev/A.kt" to 700)\n')

    def test_code_after_map_fails_closed(self):
        with self.assertRaises(BaselineError):
            parse(TEXT.rstrip('\n') + '.plus("dev/A.kt" to 700)\n')
        with self.assertRaisesRegex(BaselineError, 'after'):
            parse(TEXT.rstrip('\n') + '\n        .plus(emptyMap())\n')

    def test_changed_declaration_fails_closed(self):
        with self.assertRaises(BaselineError):
            parse(TEXT.replace('Map<String, Int> =\n', 'Map<String, Int> = extra +\n'))

    def test_duplicate_entry_fails(self):
        line = TEXT.splitlines()[[i for i, s in enumerate(TEXT.splitlines()) if 'mapOf(' in s][0] + 1]
        with self.assertRaisesRegex(BaselineError, 'duplicate'):
            parse(TEXT.replace(line + '\n', line + '\n' + line + '\n', 1))


if __name__ == '__main__':
    unittest.main()
