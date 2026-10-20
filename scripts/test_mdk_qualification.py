"""Negative controls for qualification evidence: adb success is not a test pass."""

from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from scripts import run_mdk_qualification as campaign
from scripts.verify_mdk_qualification_pair import APP, fixture_certificate, package_identity


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


class QualificationPackageTest(unittest.TestCase):
    """AGP test manifests can omit versions, while app update identity must remain strict."""

    def test_fixture_certificate_accepts_sdk_formats_but_not_mixed_signers(self):
        """Current build-tools output must still bind every SDK range to the same signer."""
        digest = 'a' * 64
        for prefix in ('Signer #1', 'V2 Signer:', 'Signer (minSdkVersion=33, maxSdkVersion=2147483647)'):
            output = f'{prefix} certificate SHA-256 digest: {digest}'
            self.assertEqual(fixture_certificate(output), digest)
            with self.assertRaises(ValueError):
                fixture_certificate(output + '\nV3.1 Signer: certificate SHA-256 digest: ' + 'b' * 64)
        with self.assertRaises(ValueError):
            fixture_certificate('Source Stamp Signer certificate SHA-256 digest: ' + digest)

    def test_test_manifest_version_is_optional(self):
        """Both absent and empty test versions retain the exact instrumentation package identity."""
        for suffix in ('', " versionCode=''", " versionCode='0'"):
            package, version = package_identity(f"package: name='{APP}.test'{suffix}", 'test')
            self.assertEqual(package, APP + '.test')
            self.assertEqual(version, '0' if suffix.endswith("'0'") else None)

    def test_app_manifest_requires_numeric_version_and_exact_package(self):
        """Missing versions and another package cannot bypass the in-place update precondition."""
        self.assertEqual(package_identity(f"package: name='{APP}' versionCode='12'", 'app'), (APP, '12'))
        for manifest in (f"package: name='{APP}'", f"package: name='{APP}' versionCode=''",
                         f"package: name='{APP}' versionCode='invalid'", "package: name='other' versionCode='12'"):
            with self.subTest(manifest=manifest), self.assertRaises(ValueError):
                package_identity(manifest, 'app')


if __name__ == '__main__':
    unittest.main()
