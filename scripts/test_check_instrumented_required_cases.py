"""Behavioral tests for the required instrumented-case results check."""

import importlib.util
import io
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    'instrumented_required_cases', ROOT / 'scripts/check_instrumented_required_cases.py',
)
required = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(required)
sys.path.insert(0, str(ROOT / 'scripts'))
SHARDS_SPEC = importlib.util.spec_from_file_location(
    'compare_instrumented_shards', ROOT / 'scripts/compare_instrumented_shards.py',
)
shards = importlib.util.module_from_spec(SHARDS_SPEC)
SHARDS_SPEC.loader.exec_module(shards)

RECIPIENT = (
    'dev.ipf.whitenoise.android.ui.chats.newchat.NewMessagePreparationAndroidTest'
    '#recipientRemainsActionableWhileProfilePrewarmAndLookupAreDelayed'
)


def testcase(case, body=''):
    """Render one JUnit testcase element for a `Class#method` case."""
    classname, name = case.split('#')
    return f'<testcase name="{name}" classname="{classname}" time="1.0">{body}</testcase>'


class RequiredInstrumentedCasesTest(unittest.TestCase):
    """Required device regressions must execute, not merely leave the runner green."""

    def run_check(self, *cases, required_text=RECIPIENT + '\n', raw_xml=None):
        """Write connected results in AGP's nested layout and run the CLI against them."""
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        results = root / 'connected/debug/flavors/devZapstore'
        results.mkdir(parents=True)
        suite = raw_xml or '<testsuite name="device">' + ''.join(cases) + '</testsuite>'
        (results / 'TEST-emulator-5554 - 14-_app-devZapstore.xml').write_text(suite)
        required_file = root / 'required.txt'
        required_file.write_text(required_text)
        out, err = io.StringIO(), io.StringIO()
        status = required.main([str(root / 'connected'), '--required', str(required_file)], out=out, err=err)
        return status, out.getvalue(), err.getvalue()

    def test_repository_declares_the_recipient_regression(self):
        """The checked-in list requires the delayed-recipient preparation case."""
        cases, problems = required.parse_required(required.REQUIRED.read_text())
        self.assertEqual(problems, [])
        self.assertIn(RECIPIENT, cases)

    def test_executed_passing_case_passes(self):
        """A required case that ran and passed satisfies the check."""
        status, out, err = self.run_check(testcase(RECIPIENT), testcase('a.b.OtherTest#other'))
        self.assertEqual(status, 0, err)
        self.assertIn('1 executed and passed', out)

    def test_missing_case_fails(self):
        """A case filtered out before execution (e.g. by @SdkSuppress) fails as missing."""
        status, _, err = self.run_check(testcase('a.b.OtherTest#other'))
        self.assertEqual(status, 1)
        self.assertIn(f'{RECIPIENT} did not run', err)

    def test_skipped_case_fails(self):
        """A reported but skipped case does not count as executed."""
        status, _, err = self.run_check(testcase(RECIPIENT, '<skipped/>'))
        self.assertEqual(status, 1)
        self.assertIn(f'{RECIPIENT} was skipped', err)

    def test_failed_case_fails(self):
        """A failure or error result is reported even if Gradle output was ignored."""
        for body in ('<failure message="boom"/>', '<error message="boom"/>'):
            with self.subTest(body=body):
                status, _, err = self.run_check(testcase(RECIPIENT, body))
                self.assertEqual(status, 1)
                self.assertIn(f'{RECIPIENT} failed', err)

    def test_skip_on_any_device_fails(self):
        """With several devices, one skipped execution still fails the requirement."""
        status, _, err = self.run_check(testcase(RECIPIENT), testcase(RECIPIENT, '<skipped/>'))
        self.assertEqual(status, 1)
        self.assertIn('was skipped', err)

    def test_missing_results_directory_fails(self):
        """No connected results at all cannot pass vacuously."""
        out, err = io.StringIO(), io.StringIO()
        with tempfile.TemporaryDirectory() as directory:
            status = required.main([str(Path(directory) / 'absent')], out=out, err=err)
        self.assertEqual(status, 1)
        self.assertIn('missing connected results directory', err.getvalue())

    def test_unreadable_results_fail_closed(self):
        """A truncated JUnit report fails instead of being silently ignored."""
        status, _, err = self.run_check(raw_xml='<testsuite><testcase name=')
        self.assertEqual(status, 1)
        self.assertIn('unreadable connected result', err)

    def test_malformed_duplicate_or_empty_lists_fail(self):
        """The case list accepts only unique fully qualified Class#method lines."""
        for text, message in (
            ('NewMessagePreparationAndroidTest\n', 'expected fully.qualified.Class#method'),
            (f'{RECIPIENT}\n{RECIPIENT}\n', 'duplicate case'),
            ('# only comments\n', 'no required cases declared'),
        ):
            with self.subTest(text=text):
                status, _, err = self.run_check(testcase(RECIPIENT), required_text=text)
                self.assertEqual(status, 1)
                self.assertIn(message, err)


class InstrumentedShardComparisonTest(unittest.TestCase):
    """Sharded runs must execute every test exactly once, not only the required ones."""

    A, B, C = (f'dev.ipf.whitenoise.android.SampleAndroidTest#case{n}' for n in 'abc')

    def run_compare(self, *shard_cases, baseline=None):
        """Write each case list as one report directory and run the comparison CLI."""
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)

        def write(name, cases):
            results = root / name / 'debug/flavors/devZapstore'
            results.mkdir(parents=True)
            (results / 'TEST-emulator-5554.xml').write_text(
                '<testsuite name="device">' + ''.join(map(testcase, cases)) + '</testsuite>')
            return str(root / name)

        argv = [write(f'shard-{index}', cases) for index, cases in enumerate(shard_cases)]
        if baseline is not None:
            argv += ['--baseline', write('unsharded', baseline)]
        out, err = io.StringIO(), io.StringIO()
        status = shards.main(argv, out=out, err=err)
        return status, out.getvalue(), err.getvalue()

    def test_disjoint_shards_matching_the_unsharded_run_pass(self):
        """Every unsharded test ran in exactly one shard."""
        status, out, _ = self.run_compare([self.A, self.C], [self.B], baseline=[self.A, self.B, self.C])
        self.assertEqual(status, 0)
        self.assertIn('3 tests ran exactly once (2 + 1), matching 3 unsharded', out)

    def test_test_in_two_shards_fails(self):
        """Overlapping shards fail even without a baseline."""
        status, _, err = self.run_compare([self.A, self.B], [self.B])
        self.assertEqual(status, 1)
        self.assertIn(f'{self.B} ran in shards [0, 1]', err)

    def test_unsharded_test_missing_from_every_shard_fails(self):
        """A test outside the required list that no shard ran is reported."""
        status, _, err = self.run_compare([self.A], [self.B], baseline=[self.A, self.B, self.C])
        self.assertEqual(status, 1)
        self.assertIn(f'{self.C} ran unsharded but in no shard', err)

    def test_shard_only_test_fails_against_the_baseline(self):
        """Shards cannot report tests that the unsharded run did not."""
        status, _, err = self.run_compare([self.A], [self.B, self.C], baseline=[self.A, self.B])
        self.assertEqual(status, 1)
        self.assertIn(f'{self.C} ran in a shard but not unsharded', err)

    def test_empty_shards_fail(self):
        """Shards without any testcase cannot pass vacuously."""
        status, _, err = self.run_compare([], [])
        self.assertEqual(status, 1)
        self.assertIn('no testcases in any shard', err)


if __name__ == '__main__':
    unittest.main()
