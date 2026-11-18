"""Exercise the size-baseline ratchet checker on a fixture and the real baseline file."""
from pathlib import Path
import unittest

from scripts.check_source_size_baseline import BASELINE, DECLARATION, BaselineError, loosened, parse

ROOT = Path(__file__).resolve().parents[1]
TEXT = f'''package dev.ipf.whitenoise.android

{DECLARATION}
    mapOf(
        "dev/A.kt" to 700,
        "dev/B.kt" to 650,
    )
'''
ENTRIES = parse(TEXT)
FIRST = 'dev/A.kt'


class SourceSizeBaselineCheckTest(unittest.TestCase):
    def test_real_baseline_parses(self):
        real = parse((ROOT / BASELINE).read_text(encoding='utf-8'))
        self.assertEqual(loosened(real, real), [])

    def test_fixture_parses(self):
        self.assertEqual(ENTRIES, {'dev/A.kt': 700, 'dev/B.kt': 650})

    def test_deleting_the_last_entry_passes(self):
        for empty in ('    mapOf(\n    )\n', '    mapOf()\n'):
            with self.subTest(empty=empty):
                head = parse(TEXT[: TEXT.index('    mapOf(')] + empty)
                self.assertEqual(head, {})
                self.assertEqual(loosened(ENTRIES, head), [])

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
        line = '        "dev/A.kt" to 700,'
        with self.assertRaisesRegex(BaselineError, 'duplicate'):
            parse(TEXT.replace(line + '\n', line + '\n' + line + '\n', 1))


if __name__ == '__main__':
    unittest.main()
