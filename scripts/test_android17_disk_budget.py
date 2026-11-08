"""Exercise the workflow's actual disk guard with disposable SDK paths."""
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest

WORKFLOW = Path(__file__).resolve().parents[1] / '.github/workflows/android-release-runtime.yml'
STEP = '      - name: Reserve Android 17 emulator disk space\n'


def workflow_script():
    source = WORKFLOW.read_text()
    block = source.split(STEP, 1)[1].split('\n      - name:', 1)[0]
    return textwrap.dedent(block.split('        run: |\n', 1)[1])


class Android17DiskBudgetTest(unittest.TestCase):
    def run_guard(self, available, sdk_failure=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            sdk, avd, bins = [root / name for name in ('sdk', 'avd', 'bin')]
            for path in (sdk / 'ndk/fixture', sdk / 'platform-tools', sdk / 'emulator', avd, bins):
                path.mkdir(parents=True, exist_ok=True)
            retained = [root / name for name in ('runtime.apk', 'mapping.txt', 'notices.txt', 'report.json')]
            retained += [sdk / 'platform-tools/adb', sdk / 'emulator/emulator']
            for path in retained:
                path.write_text('retained fixture\n')
            log = root / 'commands.log'
            expected_avd = str(Path(os.environ['HOME']) / '.android/avd')
            sdkmanager = bins / 'sdkmanager'
            sdkmanager.write_text('''#!/bin/bash
set -eu
printf 'sdkmanager %s\n' "$*" >> "$TASK_DISK_LOG"
if [[ "$1" == --install ]]; then
  [[ ! -e "$ANDROID_SDK_ROOT/ndk" ]]
  [[ "$2" == 'system-images;android-37.0;google_apis_ps16k;x86_64' ]]
  [[ "$TASK_SDK_FAILURE" == 0 ]] || exit 19
  touch "$ANDROID_SDK_ROOT/image-installed"
fi
''')
            mkdir = bins / 'mkdir'
            mkdir.write_text('''#!/bin/bash
set -eu
[[ "$1" == -p && "$2" == "$TASK_EXPECTED_AVD_PARENT" ]]
printf 'mkdir %s\n' "$2" >> "$TASK_DISK_LOG"
''')
            df = bins / 'df'
            df.write_text('''#!/bin/bash
set -eu
[[ "$2" == "$TASK_EXPECTED_AVD_PARENT" ]]
if [[ "$1" == -Pk ]]; then
  [[ -f "$ANDROID_SDK_ROOT/image-installed" ]]
  printf 'budget %s\n' "$2" >> "$TASK_DISK_LOG"
fi
printf 'Filesystem 1024-blocks Used Available Capacity Mounted\n'
printf 'fixture 20000000 1 %s 1%% fixture\n' "$TASK_FREE_KIB"
''')
            for path in (sdkmanager, df, mkdir):
                path.chmod(0o700)
            env = dict(os.environ, ANDROID_SDK_ROOT=str(sdk), ANDROID_AVD_HOME=str(avd),
                TASK_DISK_LOG=str(log), TASK_FREE_KIB=str(available), TASK_SDK_FAILURE=str(int(sdk_failure)),
                TASK_EXPECTED_AVD_PARENT=expected_avd)
            env['PATH'] = str(bins) + os.pathsep + env['PATH']
            result = subprocess.run(['bash', '-e', '-c', workflow_script()], env=env,
                capture_output=True, text=True, timeout=10)
            self.assertFalse((sdk / 'ndk').exists())
            for path in retained:
                self.assertEqual(path.read_text(), 'retained fixture\n')
            commands = log.read_text()
            return result, commands, expected_avd

    def test_exact_budget_preserves_built_artifacts_and_runtime_sdk(self):
        result, commands, avd = self.run_guard(12 * 1024 * 1024)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('sdkmanager --licenses', commands)
        self.assertIn('sdkmanager --install system-images;android-37.0;google_apis_ps16k;x86_64', commands)
        self.assertIn('budget ' + avd, commands)

    def test_missing_or_insufficient_headroom_fails_closed(self):
        for value in (12 * 1024 * 1024 - 1, 'unavailable'):
            result, _, _ = self.run_guard(value)
            self.assertNotEqual(result.returncode, 0)

    def test_image_install_failure_cannot_reach_budget_success(self):
        result, commands, _ = self.run_guard(20 * 1024 * 1024, sdk_failure=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('budget ', commands)


if __name__ == '__main__':
    unittest.main()
