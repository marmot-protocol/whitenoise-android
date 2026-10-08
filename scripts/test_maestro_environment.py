"""Platform qualification must describe the observed emulator, never the requested defaults alone."""

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import yaml

from scripts import maestro_environment as platform
from scripts import maestro_runtime as runtime


class EnvironmentTest(unittest.TestCase):
    ENV = {'GITHUB_ACTIONS': 'true', 'GITHUB_SHA': 'a' * 40,
           'GITHUB_RUN_ID': '123', 'GITHUB_RUN_ATTEMPT': '1'}

    def observed(self, *arguments):
        """Model only a successfully observed API-34 three-button disposable image."""
        if arguments == ('getprop', 'ro.kernel.qemu'):
            return '1'
        if arguments == ('getprop', 'ro.build.version.sdk'):
            return '34'
        if arguments == ('settings', 'get', 'secure', 'navigation_mode'):
            return '0'
        if arguments == ('cmd', 'overlay', 'list', '--user', '0'):
            return '[x] com.android.internal.systemui.navbar.threebutton'
        if arguments == ('getprop', 'ro.build.fingerprint'):
            return 'synthetic-image'
        return ''

    def test_unsupported_request_fails_before_device_commands(self):
        """Reject unbounded SDK or navigation inputs before even probing a device."""
        with patch.object(platform, 'adb') as adb:
            for api, mode in [('35', 'button'), ('37', 'gesture'), ('34', 'unknown'), ('34\n', 'button')]:
                with self.subTest(api=api, mode=mode), self.assertRaises(ValueError):
                    platform.configure(api, mode, self.ENV)
            adb.assert_not_called()

    def test_non_ci_identity_fails_before_device_commands(self):
        """The navigation writer has no authority outside a fully identified GitHub workflow."""
        with patch.object(platform, 'adb') as adb:
            for field in self.ENV:
                with self.subTest(field=field), self.assertRaises(ValueError):
                    platform.configure('34', 'button', {**self.ENV, field: ''})
            adb.assert_not_called()

    def test_physical_or_wrong_sdk_never_reaches_navigation_write(self):
        """A known serial alone cannot authorize an overlay change or certify a different SDK."""
        for values in [('0',), ('1', '36')]:
            with self.subTest(values=values), patch.object(platform, 'adb', side_effect=values) as adb:
                with self.assertRaises(ValueError):
                    platform.configure('34', 'button', self.ENV)
                self.assertTrue(all(call.args[0] == 'getprop' for call in adb.call_args_list))

    def test_observed_report_retains_source_os_and_navigation(self):
        """Successful setup binds the actual SDK, enabled overlay and fingerprint to its source/run."""
        with patch.object(platform, 'adb', side_effect=self.observed):
            record = platform.configure('34', 'button', self.ENV)
        identity = platform.qualify(record, 'a' * 40, '123', '1', '34', 'button')
        self.assertEqual(identity['image_fingerprint'], 'synthetic-image')
        self.assertEqual(identity['sdk'], 34)
        for field, replacement in [('sdk', 36), ('sdk', 34.0), ('schema', True), ('qemu', 1),
                                   ('navigation', 'gesture'), ('qemu', False),
                                   ('run_attempt', '2'), ('source_sha', 'b' * 40),
                                   ('navigation_overlay', 'other'), ('image_fingerprint', '')]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                platform.qualify({**record, field: replacement}, 'a' * 40, '123', '1', '34', 'button')

    def test_secure_setting_without_enabled_overlay_cannot_qualify(self):
        """A requested setting cannot hide a missing or disabled system navigation overlay."""
        def no_overlay(*arguments):
            return '' if arguments[0:3] == ('cmd', 'overlay', 'list') else self.observed(*arguments)
        with patch.object(platform, 'adb', side_effect=no_overlay), patch.object(platform.time, 'sleep'):
            with self.assertRaisesRegex(ValueError, 'did not settle'):
                platform.configure('34', 'button', self.ENV)

    def test_gesture_request_cannot_inherit_button_evidence(self):
        """An unchanged three-button image cannot certify gesture Back."""
        with patch.object(platform, 'adb', side_effect=self.observed), patch.object(platform.time, 'sleep'):
            with self.assertRaisesRegex(ValueError, 'did not settle'):
                platform.configure('34', 'gesture', self.ENV)

    def test_cli_receives_selected_mode_and_rejects_unknown_mode(self):
        """Reactive Back uses the qualified mode; an unknown mode cannot silently skip dismissal."""
        with tempfile.TemporaryDirectory() as temporary:
            with patch.dict(runtime.os.environ, {'MAESTRO_NAVIGATION_MODE': 'gesture'}), \
                    patch.object(runtime.subprocess, 'run') as run:
                runtime.run_ui('reactions-details-keyboard-back', Path(temporary))
                self.assertIn('MAESTRO_NAVIGATION_MODE=gesture', run.call_args.args[0])
            with patch.dict(runtime.os.environ, {'MAESTRO_NAVIGATION_MODE': 'unknown'}), \
                    patch.object(runtime.subprocess, 'run') as run:
                with self.assertRaises(ValueError):
                    runtime.run_ui('reactions-details-keyboard-back', Path(temporary))
                run.assert_not_called()

    def test_platform_dispatch_is_manual_only_and_reuses_android17_recovery(self):
        """Keep OS qualification out of ordinary CI and preserve the established Android-17 setup."""
        workflow = yaml.safe_load((runtime.ROOT / '.github/workflows/android-instrumented.yml').read_text())
        inputs = workflow.get('on', workflow.get(True))['workflow_dispatch']['inputs']
        self.assertEqual(inputs['maestro_android_api']['options'], list(platform.APIS))
        self.assertEqual(inputs['maestro_navigation_mode']['options'], list(platform.MODES))
        job = workflow['jobs']['maestro-runtime']
        self.assertEqual(job['strategy']['max-parallel'], 2)
        build = workflow['jobs']['maestro-runtime-build']
        self.assertIn("github.event_name == 'workflow_dispatch'", build['if'])
        validation = next(step for step in build['steps'] if step.get('id') == 'selection')
        self.assertIn('maestro_environment.py validate', validation['run'])
        emulator = next(step for step in job['steps'] if 'android-emulator-runner' in step.get('uses', ''))
        self.assertEqual(emulator['with']['api-level'], "${{ inputs.maestro_android_api || '34' }}")
        self.assertIn('configure', emulator['with']['script'])
        self.assertIn('stabilize-android17-emulator.sh', emulator['with']['pre-emulator-launch-script'])
        self.assertIn('4096M', emulator['with']['ram-size'])
        for name in ('Update SDK cmdline-tools for API 37', 'Retry Android 17 input-service readiness',
                     'Test Android 17 stabilization handshake'):
            step = next(step for step in job['steps'] if step.get('name') == name)
            self.assertEqual(step['if'], "inputs.maestro_android_api == '37.0'")

    def test_reactor_back_has_real_gesture_and_no_keyboard_compensation(self):
        """Both Back variants dismiss the same popup without concealing a lost keyboard or route."""
        helper = runtime.ROOT / '.maestro/fixtures/reactor-back.yaml'
        _, commands = list(yaml.safe_load_all(helper.read_text()))
        gesture, button = [command['runFlow'] for command in commands]
        self.assertEqual(gesture['when'][True], "${MAESTRO_NAVIGATION_MODE == 'gesture'}")
        self.assertEqual(gesture['commands'], [{'swipe': {'start': '0%, 50%', 'end': '50%, 50%', 'duration': 400}}])
        self.assertEqual(button['when'][True], "${MAESTRO_NAVIGATION_MODE == 'button'}")
        self.assertEqual(button['commands'], ['back'])
        flows = list((runtime.ROOT / '.maestro/runtime').glob('reactions-details-*.yaml'))
        self.assertEqual(len(flows), 6)
        for flow in flows:
            with self.subTest(flow=flow.name):
                _, steps = list(yaml.safe_load_all(flow.read_text()))
                self.assertIn({'runFlow': '../fixtures/reactor-back.yaml'}, steps)
                self.assertNotIn('back', steps)
                self.assertNotIn('hideKeyboard', steps)


if __name__ == '__main__':
    unittest.main()
