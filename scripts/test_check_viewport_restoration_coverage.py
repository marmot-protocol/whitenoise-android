import tempfile
import unittest
from pathlib import Path

from scripts.check_viewport_restoration_coverage import OWNER, check_coverage, owner_class


def counter(kind, covered, missed):
    return f'<counter type="{kind}" covered="{covered}" missed="{missed}"/>'


def klass(name, lines=(100, 0), branches=(100, 0)):
    return (f'<class name="{name}">' + counter('LINE', *lines)
            + counter('BRANCH', *branches) + '</class>')


class ViewportRestorationCoverageTest(unittest.TestCase):
    def check(self, classes):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / 'report.xml'
            report.write_text(f'<report><package>{classes}</package></report>', encoding='utf-8')
            return check_coverage(report)

    def test_generated_coroutines_and_compose_helpers_are_counted_once(self):
        totals = self.check(klass(OWNER) + klass(OWNER + '$commitInitialPosition$1')
                            + klass(OWNER + 'Kt') + klass(OWNER + 'Kt$rememberOwner$1'))
        self.assertEqual({'LINE': [400, 400], 'BRANCH': [400, 400]}, totals)

    def test_similarly_named_unrelated_class_does_not_inflate_coverage(self):
        self.assertFalse(owner_class(OWNER + 'Other'))
        with self.assertRaisesRegex(ValueError, 'BRANCH'):
            self.check(klass(OWNER, branches=(79, 21)) + klass(OWNER + 'Other'))

    def test_generated_class_without_the_real_owner_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'missing'):
            self.check(klass(OWNER + '$generated'))

    def test_missing_eligible_lines_or_branches_is_rejected(self):
        for kind in ('LINE', 'BRANCH'):
            content = f'<class name="{OWNER}">' + counter(kind, 10, 0) + '</class>'
            with self.assertRaises(ValueError):
                self.check(content)

    def test_invalid_and_duplicate_counters_are_rejected(self):
        for content in (klass(OWNER, lines=('-1', 0)), klass(OWNER, branches=('1.5', 0)),
                        klass(OWNER).replace('</class>', counter('BRANCH', 100, 0) + '</class>')):
            with self.assertRaises(ValueError):
                self.check(content)

    def test_floor_comparison_is_not_rounded_up(self):
        with self.assertRaisesRegex(ValueError, 'LINE'):
            self.check(klass(OWNER, lines=(899, 101)))
        with self.assertRaisesRegex(ValueError, 'BRANCH'):
            self.check(klass(OWNER, branches=(799, 201)))
        self.check(klass(OWNER, lines=(90, 10), branches=(80, 20)))

    def test_generated_class_without_branches_is_valid(self):
        no_branches = f'<class name="{OWNER}$generated">' + counter('LINE', 10, 0) + '</class>'
        self.check(klass(OWNER) + no_branches)


if __name__ == '__main__':
    unittest.main()
