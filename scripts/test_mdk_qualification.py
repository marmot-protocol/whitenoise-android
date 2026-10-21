"""Negative controls for qualification evidence: adb success is not a test pass."""

from pathlib import Path
import tempfile
import json
import unittest
import queue
import subprocess
import zipfile
from unittest.mock import Mock, patch

from scripts import run_mdk_qualification as campaign
from scripts import run_mdk_faults as faults
from scripts import qualify_signed_preview as preview
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

    def test_pending_upload_failure_stays_failed_while_original_media_recovery_runs(self):
        """Independent receiver-file checks must not hide a failed sender promotion or require it to pass."""
        facts = {'phases': {'cold_restart': 'passed'}}
        blob = Mock()

        def fixture_instrument(name, *args):
            """Fail only promotion, preserving the already-verified original media for separate recovery."""
            if name == 'pending-promotion':
                raise RuntimeError('pending upload unavailable')

        with patch.object(campaign, 'stop'), patch.object(campaign, 'instrument', side_effect=fixture_instrument) as run:
            campaign.post_upgrade_campaigns((), blob, facts)
        self.assertEqual([call.args[0] for call in run.call_args_list],
                         ['pending-promotion', 'media-corrupt', 'media-lost'])
        self.assertIn('pending_promotion_campaign', facts['phases'])
        self.assertNotIn('pending_promotion', facts['phases'])
        self.assertNotIn('promoted_cold_restart', facts['phases'])
        self.assertEqual(facts['phases']['media_corrupt_recovery'], 'passed')
        self.assertEqual(facts['phases']['media_lost_recovery'], 'passed')
        blob.acquisition_unavailable.clear.assert_called_once_with()

    def test_post_upgrade_campaigns_reject_an_unqualified_cold_fixture(self):
        """A missing cold-restart pass forbids both promotion and destructive disposable-file checks."""
        with patch.object(campaign, 'instrument') as run:
            with self.assertRaisesRegex(RuntimeError, 'require the retained cold-restart'):
                campaign.post_upgrade_campaigns((), Mock(), {'phases': {}})
            run.assert_not_called()

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

    def test_extra_ignored_case_cannot_hide_behind_expected_pass(self):
        """Upgrade phases allow no skipped cases, even when every required case also passed."""
        with self.assertRaises(RuntimeError):
            self.invoke(transcript([('retained', 0), ('unexpected', -3)]), {'retained'})

    def test_failed_instrumentation_retains_adb_diagnostics_and_cannot_pass(self):
        """A failed adb command must preserve the actual platform error instead of only its exit status."""
        failure = subprocess.CalledProcessError(1, ['adb'], output='partial status', stderr='activity service unavailable')
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(campaign, 'REPORTS', Path(directory)), patch.object(campaign, 'adb', side_effect=failure):
                with self.assertRaises(subprocess.CalledProcessError):
                    campaign.instrument('fixture', campaign.UPGRADE, {'retained'})
                evidence = (Path(directory) / 'fixture-command-error.txt').read_text()
                self.assertIn('partial status', evidence)
                self.assertIn('activity service unavailable', evidence)
                self.assertFalse((Path(directory) / 'fixture.json').exists())

    def test_diagnostic_capture_is_bounded_and_health_errors_remain_evidence(self):
        """Broken service probes cannot replace the original failure, and captured output stays bounded."""
        failure = subprocess.TimeoutExpired(['adb'], 10, output=b'x' * 65536, stderr=b'transport timeout')
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(campaign, 'REPORTS', Path(directory)), patch.object(campaign, 'adb', side_effect=failure):
                campaign.retain_device_health()
                evidence = (Path(directory) / 'activity-service-command-error.txt').read_text()
                captured_stdout = evidence.split('stdout:\n', 1)[1].split('\nstderr:\n', 1)[0]
                self.assertEqual(len(captured_stdout), 32768)
                self.assertIn('transport timeout', evidence)
                self.assertEqual(len(list(Path(directory).glob('*-command-error.txt'))), 4)

    def test_recovery_allows_only_the_two_documented_upstream_ignores(self):
        """A changed recovery skip set needs explicit review rather than silently passing the campaign."""
        passed = [(name, 0) for name in campaign.RECOVERY_CASES]
        for skipped in (campaign.IGNORED, set(), campaign.IGNORED | {'unexpected'}):
            output = transcript(passed + [(name, -3) for name in skipped])
            with tempfile.TemporaryDirectory() as directory:
                with patch.object(campaign, 'REPORTS', Path(directory)), patch.object(campaign, 'adb', return_value=output):
                    if skipped == campaign.IGNORED:
                        campaign.instrument('recovery', campaign.RECOVERY, campaign.RECOVERY_CASES)
                    else:
                        with self.assertRaises(RuntimeError):
                            campaign.instrument('recovery', campaign.RECOVERY, campaign.RECOVERY_CASES)

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

    def test_preview_text_child_cannot_enable_its_disabled_compose_action(self):
        """The observed baseline demo label is enabled even while its clickable parent is disabled."""
        window = ('<hierarchy><node enabled="false" clickable="true">'
                  '<node text="Create demo conversation" enabled="true"/></node></hierarchy>')
        with patch.object(preview, 'adb', return_value=window):
            ui = preview.PreviewUi()
            ui.snapshot()
            self.assertEqual(ui.matches('Create demo conversation'), [])

    def test_baseline_demo_navigation_waits_for_coordinator_completion(self):
        """A transient profile label must not interrupt demo setup; compact input is then exercised explicitly."""
        windows = iter([
            '<hierarchy><node text="Johnny Appleseed" enabled="true"/></hierarchy>',
            '<hierarchy><node text="Replies and reactions work across both profiles." enabled="true"/>'
            '<node text="Great — the demo conversation is ready to explore." enabled="true"/>'
            '<node text="Message" enabled="true"/><node content-desc="Add attachment" enabled="true"/>'
            '<node content-desc="Dictate text" enabled="true"/></hierarchy>',
        ])

        def fixture_adb(*args, **kwargs):
            """Advance only on fresh UI reads, never by tapping a profile while setup owns it."""
            if args[:2] == ('shell', 'cat'):
                return next(windows)
            return b'image' if kwargs.get('binary') else ''

        with tempfile.TemporaryDirectory() as directory:
            with patch.object(preview, 'REPORTS', Path(directory)), \
                    patch.object(preview, 'adb', side_effect=fixture_adb), patch.object(preview.time, 'sleep'):
                ui = preview.PreviewUi()
                with patch.object(ui, 'tap_node', side_effect=AssertionError('Premature profile navigation')), \
                        patch.object(ui, 'focus_composer') as focus:
                    ui.retained_chat('baseline')
                    focus.assert_called_once_with('baseline')
                    self.assertTrue((Path(directory) / 'baseline.png').exists())

    def test_baseline_returned_chat_list_reopens_explicit_demo_ready_action(self):
        """Returning to the original profile's list is a navigation recovery, never permission to switch accounts."""
        windows = iter([
            '<hierarchy><node text="Johnny Appleseed" enabled="true"/>'
            '<node content-desc="Chats" enabled="true"/>'
            '<node content-desc="Switch Profile, PR3371 qualification" enabled="true"/>'
            '<node text="Great — the demo conversation is ready to explore." enabled="true"/></hierarchy>',
            '<hierarchy><node text="Replies and reactions work across both profiles." enabled="true"/>'
            '<node text="Great — the demo conversation is ready to explore." enabled="true"/>'
            '<node text="Message" enabled="true"/><node content-desc="Add attachment" enabled="true"/>'
            '<node content-desc="Dictate text" enabled="true"/></hierarchy>',
        ])

        def fixture_adb(*args, **kwargs):
            """Expose the completed demo through its user action while rejecting direct profile-row taps."""
            if args[:2] == ('shell', 'cat'):
                return next(windows)
            return b'image' if kwargs.get('binary') else ''

        with tempfile.TemporaryDirectory() as directory:
            with patch.object(preview, 'REPORTS', Path(directory)), \
                    patch.object(preview, 'adb', side_effect=fixture_adb), patch.object(preview.time, 'sleep'):
                ui = preview.PreviewUi()
                with patch.object(ui, 'tap_node', side_effect=AssertionError('Direct profile navigation')), \
                        patch.object(ui, 'open_completed_demo') as reopen, patch.object(ui, 'focus_composer'):
                    ui.retained_chat('baseline')
                    reopen.assert_called_once_with()

    def composer_window(self, field_class='android.widget.ScrollView', focused='true',
                        bounds='[30,315][290,363]', package=preview.APP, enabled='true'):
        """Mirror the observed empty BasicTextField: scroll semantics and a separate overlaid placeholder."""
        return (f'<hierarchy><node enabled="{enabled}">'
                f'<node class="{field_class}" focused="{focused}" package="{package}" '
                f'enabled="true" bounds="{bounds}"/>'
                '<node text="Message" enabled="true" bounds="[30,327][99,351]"/>'
                '<node content-desc="Add attachment" enabled="true"/>'
                '<node content-desc="Dictate text" enabled="true"/></node></hierarchy>')

    def test_composer_focus_accepts_observed_scroll_semantics_but_not_unrelated_focus(self):
        """A focused view qualifies only inside the composer, over its placeholder and in the app package."""
        cases = [({}, True), ({'field_class': 'android.widget.EditText'}, True),
                 ({'field_class': 'android.widget.TextView'}, False), ({'focused': 'false'}, False),
                 ({'bounds': '[30,20][290,68]'}, False), ({'package': 'another.app'}, False),
                 ({'enabled': 'false'}, False)]
        for changes, expected in cases:
            with self.subTest(changes=changes), patch.object(preview, 'adb', return_value=self.composer_window(**changes)):
                ui = preview.PreviewUi()
                ui.snapshot()
                self.assertEqual(ui.focused_composer_node() is not None, expected)
        unrelated = (f'<hierarchy><node class="android.widget.ScrollView" focused="true" package="{preview.APP}" '
                     'bounds="[30,315][290,363]"/><node><node text="Message" enabled="true" '
                     'bounds="[30,327][99,351]"/></node></hierarchy>')
        with patch.object(preview, 'adb', return_value=unrelated):
            ui = preview.PreviewUi()
            ui.snapshot()
            self.assertIsNone(ui.focused_composer_node())

    def test_composer_focus_still_requires_a_shown_keyboard_and_retains_failure_observation(self):
        """The corrected Compose selector must not turn focus without an IME into a passing observation."""
        for shown in (False, True):
            def fixture_adb(*args, **kwargs):
                """Expose real composer geometry independently of the keyboard-visible state."""
                if args[:2] == ('shell', 'cat'):
                    return self.composer_window()
                if args == ('shell', 'dumpsys', 'input_method'):
                    return 'mInputShown=' + str(shown).lower()
                return b'image' if kwargs.get('binary') else ''

            with self.subTest(shown=shown), tempfile.TemporaryDirectory() as directory:
                with patch.object(preview, 'REPORTS', Path(directory)), \
                        patch.object(preview, 'adb', side_effect=fixture_adb) as command, \
                        patch.object(preview.time, 'monotonic', side_effect=[0, 0, 31]), patch.object(preview.time, 'sleep'):
                    ui = preview.PreviewUi()
                    with patch.object(ui, 'click'):
                        if shown:
                            ui.focus_composer('baseline')
                            command.assert_any_call('shell', 'input', 'keyevent', 'KEYCODE_BACK')
                        else:
                            with self.assertRaisesRegex(RuntimeError, 'did not accept focus'):
                                ui.focus_composer('baseline')
                    observation = json.loads((Path(directory) / 'baseline-composer.json').read_text())
                    self.assertTrue(observation['focused_composer'])
                    self.assertEqual(observation['keyboard_shown'], shown)
                    self.assertEqual((Path(directory) / 'baseline-composer.png').exists(), shown)

    def test_settings_requires_arrival_after_a_lost_signup_navigation_tap(self):
        """A visible original avatar and a dispatched tap do not prove that Settings opened."""
        chat = ('<hierarchy><node content-desc="Open settings, PR3371 qualification" enabled="true" '
                'bounds="[12,32][60,80]"/></hierarchy>')
        settings = ('<hierarchy><node text="Settings" enabled="true"/>'
                    '<node content-desc="Back" enabled="true"/>'
                    '<node text="Profile Keys" enabled="true"/></hierarchy>')
        windows = iter([chat, chat, settings])

        def fixture_adb(*args, **kwargs):
            """Keep the first tap on the chat list; only the second reaches the observed destination."""
            return next(windows) if args[:2] == ('shell', 'cat') else ''

        with patch.object(preview, 'adb', side_effect=fixture_adb), patch.object(preview.time, 'sleep'):
            ui = preview.PreviewUi()
            with patch.object(ui, 'tap_node') as tap:
                ui.open_settings()
                self.assertEqual(tap.call_count, 2)
                self.assertTrue(ui.matches('Profile Keys'))

    def test_two_profile_settings_opens_settings_without_selecting_an_account(self):
        """The selector's Settings row cannot count as arrival, and no profile row may be selected."""
        windows = iter([
            '<hierarchy><node content-desc="Switch Profile, PR3371 qualification" enabled="true"/></hierarchy>',
            '<hierarchy><node text="Switch Profile" enabled="true"/><node text="Settings" enabled="true"/>'
            '<node text="Johnny Appleseed" enabled="true"/></hierarchy>',
            '<hierarchy><node text="Settings" enabled="true"/><node content-desc="Back" enabled="true"/>'
            '<node text="Profile Keys" enabled="true"/></hierarchy>',
        ])

        def fixture_adb(*args, **kwargs):
            """Advance the three distinct screens only when the driver refreshes the visible hierarchy."""
            return next(windows) if args[:2] == ('shell', 'cat') else ''

        with patch.object(preview, 'adb', side_effect=fixture_adb), patch.object(preview.time, 'sleep'):
            ui = preview.PreviewUi()
            with patch.object(ui, 'tap_node') as tap:
                ui.open_settings()
                labels = [call.args[0].get('text') or call.args[0].get('content-desc') for call in tap.call_args_list]
                self.assertEqual(labels, ['Switch Profile, PR3371 qualification', 'Settings'])
                self.assertTrue(ui.matches('Profile Keys'))

    def test_settings_restored_viewport_returns_to_public_key_row(self):
        """A retained lower Settings viewport must scroll upward before public identity capture."""
        windows = iter([
            '<hierarchy><node text="Settings" enabled="true"/><node content-desc="Back" enabled="true"/>'
            '<node text="Developer Tools" enabled="true"/></hierarchy>',
            '<hierarchy><node text="Settings" enabled="true"/><node content-desc="Back" enabled="true"/>'
            '<node text="Profile Keys" enabled="true"/></hierarchy>',
        ])

        def fixture_adb(*args, **kwargs):
            """Supply the actual small emulator geometry and record the direction of its settings scroll."""
            if args[:2] == ('shell', 'cat'):
                return next(windows)
            return 'Physical size: 320x640' if args == ('shell', 'wm', 'size') else ''

        with patch.object(preview, 'adb', side_effect=fixture_adb) as command, patch.object(preview.time, 'sleep'):
            ui = preview.PreviewUi()
            with patch.object(ui, 'tap_node', side_effect=AssertionError('Unexpected navigation')):
                ui.open_settings()
                command.assert_any_call('shell', 'input', 'swipe', '160', '213', '160', '512', '400')

    def test_demo_reopen_requires_enabled_ready_action_and_ready_status(self):
        """A running or disabled demo action cannot qualify coordinator completion."""
        windows = iter([
            '<hierarchy><node text="Open demo conversation" enabled="true"/></hierarchy>',
            '<hierarchy><node text="Open demo conversation" enabled="false"/>'
            '<node text="The real two-profile conversation is ready." enabled="true"/></hierarchy>',
            '<hierarchy><node text="Open demo conversation" enabled="true"/>'
            '<node text="The real two-profile conversation is ready." enabled="true"/></hierarchy>',
        ])

        def fixture_adb(*args, **kwargs):
            """Advance asynchronous coordinator status without executing any demo action."""
            return next(windows) if args[:2] == ('shell', 'cat') else ''

        with tempfile.TemporaryDirectory() as directory:
            with patch.object(preview, 'REPORTS', Path(directory)), \
                    patch.object(preview, 'adb', side_effect=fixture_adb), patch.object(preview.time, 'sleep'):
                ui = preview.PreviewUi()
                with patch.object(ui, 'open_settings'), patch.object(ui, 'click'), patch.object(ui, 'tap_node') as tap:
                    ui.open_completed_demo()
                    tap.assert_called_once()
                    self.assertEqual(tap.call_args.args[0].get('enabled'), 'true')
                    self.assertTrue((Path(directory) / 'baseline-demo-ready.xml').exists())


if __name__ == '__main__':
    unittest.main()
