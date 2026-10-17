"""SDK-download faults cannot become UI PASS, unbounded retries or silent incomplete images."""

from pathlib import Path
from types import SimpleNamespace
import os
import subprocess
import sys
import tempfile
import unittest
import yaml

from scripts.maestro_sdk_image import image_package, installed, prepare


class SdkImageTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.package = image_package('34')
        self.calls = []

    def install(self, package=None):
        package = package or self.package
        folder = self.root.joinpath(*package.split(';'))
        folder.mkdir(parents=True, exist_ok=True)
        (folder / 'package.xml').write_text(f'<sdk><localPackage path="{package}"/></sdk>')
        (folder / 'system.img').write_bytes(b'fixture system image')

    def sdk_run(self, command, **kwargs):
        self.calls.append((command, kwargs))
        self.assertEqual(kwargs['timeout'], 180)
        self.assertEqual(command[2:], ['--install', self.package, '--channel=0'])
        if len(self.calls) == 1:
            kwargs['stdout'].write('Warning: Error on ZipFile unknown archive.\n')
            return SimpleNamespace(returncode=1)
        self.install()
        return SimpleNamespace(returncode=0)

    def test_known_archive_fault_retries_once_then_requires_real_package_files(self):
        self.assertEqual(prepare('34', self.root, self.root / 'reports', 'sdkmanager', self.sdk_run), 2)
        self.assertEqual(len(self.calls), 2)
        self.assertTrue((self.root / 'reports/install-1.log').is_file())

    def test_installed_verified_image_does_not_download_again(self):
        self.install()
        self.assertEqual(prepare('34', self.root, self.root / 'reports', 'sdkmanager', self.sdk_run), 0)
        self.assertEqual(self.calls, [])

    def test_success_exit_without_image_is_not_success_or_retry(self):
        def run(command, **kwargs):
            self.calls.append(command)
            return SimpleNamespace(returncode=0)
        with self.assertRaises(RuntimeError):
            prepare('34', self.root, self.root / 'reports', 'sdkmanager', run)
        self.assertEqual(len(self.calls), 1)

    def test_other_error_and_timeout_are_not_retried(self):
        for exception in (False, True):
            self.calls.clear()
            def run(command, **kwargs):
                self.calls.append(command)
                if exception:
                    raise subprocess.TimeoutExpired(command, 180)
                kwargs['stdout'].write('License or permission failure')
                return SimpleNamespace(returncode=1)
            with self.assertRaises(subprocess.TimeoutExpired if exception else RuntimeError):
                prepare('34', self.root, self.root / 'reports', 'sdkmanager', run)
            self.assertEqual(len(self.calls), 1)

    def test_second_archive_fault_cannot_trigger_a_third_attempt(self):
        def run(command, **kwargs):
            self.calls.append(command)
            kwargs['stdout'].write('Error on ZipFile unknown archive')
            return SimpleNamespace(returncode=1)
        with self.assertRaises(RuntimeError):
            prepare('34', self.root, self.root / 'reports', 'sdkmanager', run)
        self.assertEqual(len(self.calls), 2)

    def test_metadata_mismatch_empty_image_and_symlink_are_rejected(self):
        self.install()
        folder = self.root.joinpath(*self.package.split(';'))
        (folder / 'package.xml').write_text('<sdk><localPackage path="other"/></sdk>')
        self.assertFalse(installed(self.root, self.package))
        self.install()
        (folder / 'system.img').write_bytes(b'')
        self.assertFalse(installed(self.root, self.package))
        (folder / 'system.img').unlink()
        (folder / 'system.img').symlink_to(folder / 'package.xml')
        self.assertFalse(installed(self.root, self.package))

    def test_api_package_is_allowlisted_and_android17_keeps_its_target(self):
        self.assertEqual(image_package('37.0'), 'system-images;android-37.0;google_apis_ps16k;x86_64')
        with self.assertRaises(ValueError):
            image_package('../34')

    def test_workflow_preflights_the_selected_image_before_any_ui_execution(self):
        root = Path(__file__).resolve().parents[1]
        workflow = yaml.safe_load((root / '.github/workflows/android-instrumented.yml').read_text())
        steps = workflow['jobs']['maestro-runtime']['steps']
        index = next(i for i, step in enumerate(steps) if 'maestro_sdk_image.py' in step.get('run', ''))
        emulator = next(i for i, step in enumerate(steps) if 'android-emulator-runner' in step.get('uses', ''))
        self.assertLess(index, emulator)
        self.assertIn('--api "$MAESTRO_ANDROID_API"', steps[index]['run'])
        self.assertIn('build/maestro-sdk-image', steps[index]['run'])
        self.assertNotIn('maestro_runtime.py', steps[index]['run'])

    def manager_fixture(self, path):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('#!' + sys.executable + '\n' + '''
from pathlib import Path
import sys
root = Path(sys.argv[1].removeprefix('--sdk_root='))
assert sys.argv[2] == '--install' and sys.argv[4] == '--channel=0'
package = sys.argv[3]
folder = root.joinpath(*package.split(';'))
folder.mkdir(parents=True, exist_ok=True)
(folder / 'package.xml').write_text(f'<sdk><localPackage path="{package}"/></sdk>')
(folder / 'system.img').write_bytes(b'controlled image fixture')
(root / 'manager-used.txt').write_text(str(Path(__file__)))
''')
        path.chmod(0o755)
        return path

    def sdk_cli(self, path='/usr/bin:/bin'):
        script = Path(__file__).resolve().with_name('maestro_sdk_image.py')
        return subprocess.run([sys.executable, str(script), '--api', '34', '--reports', str(self.root / 'reports')],
                              env={'PATH': path, 'ANDROID_HOME': str(self.root)},
                              capture_output=True, text=True, timeout=10)

    def test_cli_uses_configured_sdk_tool_when_it_is_not_on_path(self):
        manager = self.manager_fixture(self.root / 'cmdline-tools/latest/bin/sdkmanager')
        result = self.sdk_cli()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('installation attempts: 1', result.stdout)
        self.assertEqual((self.root / 'manager-used.txt').read_text(), str(manager))
        self.assertTrue(installed(self.root, self.package))

    def test_cli_preserves_explicit_path_tool_precedence_for_updated_sdk(self):
        self.manager_fixture(self.root / 'cmdline-tools/latest/bin/sdkmanager')
        updated = self.manager_fixture(self.root / 'updated-tools/bin/sdkmanager')
        result = self.sdk_cli(str(updated.parent) + os.pathsep + '/usr/bin:/bin')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.root / 'manager-used.txt').read_text(), str(updated))

    def test_cli_still_rejects_missing_or_nonexecutable_sdk_tool(self):
        for present in (False, True):
            if present:
                self.manager_fixture(self.root / 'cmdline-tools/latest/bin/sdkmanager').chmod(0o644)
            result = self.sdk_cli()
            self.assertEqual(result.returncode, 2, result.stderr)
            self.assertIn('Configured Android SDK and sdkmanager are required', result.stderr)
            self.assertFalse((self.root / 'manager-used.txt').exists())


if __name__ == '__main__':
    unittest.main()
