"""Negative controls for qualification evidence: adb success is not a test pass."""

from pathlib import Path
import tempfile
import unittest
import queue
import zipfile
from unittest.mock import patch

from scripts import run_mdk_qualification as campaign
from scripts import run_mdk_faults as faults
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

    def test_component_disable_requires_the_reported_state(self):
        """A zero-exit pm command reporting DEFAULT cannot satisfy the background isolation precondition."""
        with patch.object(campaign, 'adb', return_value='Component fixture new state: default\n'):
            with self.assertRaises(RuntimeError):
                campaign.disable_background_entries()
        with patch.object(campaign, 'adb', return_value='Component fixture new state: disabled\n') as shell:
            campaign.disable_background_entries()
            self.assertEqual(shell.call_count, 2)
            for call in shell.call_args_list:
                self.assertEqual(call.args[:5], ('shell', 'pm', 'disable', '--user', '0'))
                self.assertTrue(call.args[5].startswith(campaign.APP + '/'))

    def test_native_hook_rejects_another_published_library(self):
        """A familiar instruction or filename cannot authorize offsets in a different complete binary."""
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / 'candidate.apk'
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('lib/x86_64/libmarmot_uniffi.so', b'not-the-reviewed-library')
            with self.assertRaises(RuntimeError):
                faults.verify_native(apk)

    def test_unexpected_or_failed_hook_cannot_pass_the_crash_barrier(self):
        """A script error or different event is not a witnessed publication/deletion boundary."""
        for message in ({'type': 'error'}, {'type': 'send', 'payload': {'event': 'armed'}}):
            events = queue.Queue()
            events.put(message)
            facts = {'events': []}
            with self.assertRaises(RuntimeError):
                faults.event(events, 'published-before-legacy-delete', facts)
            self.assertEqual(facts['events'], [])

    def test_fault_volume_follows_the_legacy_sender_not_account_sort_order(self):
        """The first account can be the already-file-backed receiver and must never own the write barrier."""
        root = '/data/user/0/' + faults.APP + '/files'
        paths = [root + '/Marmot/a-receiver/session.sqlite', root + '/Marmot/z-sender/session.sqlite']
        self.assertEqual(faults.legacy_media_root(paths, root, {'sender': 'z-sender'}),
                         root + '/Marmot/z-sender/media')
        for receipt in ({}, {'sender': 'missing'}, {'sender': None}):
            with self.subTest(receipt=receipt), self.assertRaises(RuntimeError):
                faults.legacy_media_root(paths, root, receipt)

    def test_hook_timeout_is_a_recordable_failure_not_an_uncaught_queue_error(self):
        """A missing write barrier must fail upgrade while allowing independent recovery cases to run."""
        events = queue.Queue()
        facts = {'events': []}
        with patch.object(events, 'get', side_effect=queue.Empty):
            with self.assertRaisesRegex(RuntimeError, 'timed out waiting for admitted-media-write-blocked'):
                faults.event(events, 'admitted-media-write-blocked', facts)
        self.assertEqual(facts['events'], [])


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


class QualificationUiTest(unittest.TestCase):
    """Reach the retained conversation through the real consent icon and chat-list transition."""

    def test_consent_icon_is_dismissed_before_opening_retained_chat(self):
        """A content-description close icon is actionable; a chat preview alone cannot qualify the UI."""
        windows = [
            '<hierarchy><node text="Help Improve White Noise"/>'
            '<node content-desc="Close" enabled="true" bounds="[10,10][30,30]"/></hierarchy>',
            '<hierarchy><node resource-id="chats.scope.chats"/>'
            '<node text="Retained qualification history 0"/>'
            '<node text="Nightly upgrade qualification" bounds="[30,30][90,90]"/></hierarchy>',
            '<hierarchy><node text="Nightly upgrade qualification" bounds="[0,0][120,40]"/>'
            '<node resource-id="composer-pill-surface"/>'
            '<node text="Retained qualification history 0"/></hierarchy>',
        ]
        taps = []

        def fixture_adb(*args, **kwargs):
            """Only user-like taps advance the synthetic UI; screenshots contain no account data."""
            if args[:2] == ('shell', 'cat'):
                return windows[len(taps)]
            if args[:3] == ('shell', 'input', 'tap'):
                taps.append(args[3:])
            return b'image' if kwargs.get('binary') else ''

        with tempfile.TemporaryDirectory() as directory:
            with patch.object(campaign, 'REPORTS', Path(directory)), \
                    patch.object(campaign, 'adb', side_effect=fixture_adb), patch.object(campaign.time, 'sleep'):
                campaign.usable_chat('fixture')
                self.assertEqual(taps, [('20', '20'), ('60', '60')])
                self.assertEqual((Path(directory) / 'fixture.png').read_bytes(), b'image')

    def test_restored_conversation_header_is_not_a_chat_list_row(self):
        """An already-open retained conversation must pass without tapping its identically named group header."""
        window = ('<hierarchy><node text="Nightly upgrade qualification" bounds="[0,0][120,40]"/>'
                  '<node resource-id="composer-pill-surface"/>'
                  '<node text="Retained qualification history 0"/></hierarchy>')

        def fixture_adb(*args, **kwargs):
            """Expose the restored conversation and reject any navigation away from its retained messages."""
            if args[:2] == ('shell', 'cat'):
                return window
            if args[:3] == ('shell', 'input', 'tap'):
                self.fail('The conversation header must not be opened')
            return b'image' if kwargs.get('binary') else ''

        with tempfile.TemporaryDirectory() as directory:
            with patch.object(campaign, 'REPORTS', Path(directory)), \
                    patch.object(campaign, 'adb', side_effect=fixture_adb):
                campaign.usable_chat('restored')
                self.assertTrue((Path(directory) / 'restored.png').exists())


if __name__ == '__main__':
    unittest.main()
