"""Negative controls for qualification evidence: adb success is not a test pass."""

from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from scripts import run_mdk_qualification as campaign


def transcript(cases, finish='INSTRUMENTATION_CODE: -1'):
    """Encode actual AndroidJUnitRunner terminal records, including ignored and failed cases."""
    return '\n'.join(f'INSTRUMENTATION_STATUS: test={name}\nINSTRUMENTATION_STATUS_CODE: {code}'
                     for name, code in cases) + '\n' + finish


class QualificationEvidenceTest(unittest.TestCase):
    """Prevent skipped, missing, crashed or failed cases from silently satisfying the native adoption gate."""

    def invoke(self, output, expected):
        """Keep report writes isolated while returning the injected adb success transcript."""
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(campaign, 'REPORTS', Path(directory)), patch.object(campaign, 'adb', return_value=output):
                campaign.instrument('fixture', campaign.UPGRADE, expected)

    def test_pass_requires_each_named_case(self):
        self.invoke(transcript([('retained', 0)]), {'retained'})
        for cases in ([], [('retained', -3)], [('retained', -2)], [('different', 0)]):
            with self.subTest(cases=cases), self.assertRaises(RuntimeError):
                self.invoke(transcript(cases), {'retained'})

    def test_process_abort_after_a_case_cannot_pass(self):
        with self.assertRaises(RuntimeError):
            self.invoke(transcript([('retained', 0)], 'INSTRUMENTATION_FAILED: crashed'), {'retained'})

    def test_extra_failure_cannot_hide_behind_expected_pass(self):
        with self.assertRaises(RuntimeError):
            self.invoke(transcript([('retained', 0), ('unexpected', -2)]), {'retained'})


if __name__ == '__main__':
    unittest.main()
