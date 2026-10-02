"""Behavioral tests for the required instrumented-case results check."""

import importlib.util
import io
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    'instrumented_required_cases', ROOT / 'scripts/check_instrumented_required_cases.py',
)
required = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(required)

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


if __name__ == '__main__':
    unittest.main()
