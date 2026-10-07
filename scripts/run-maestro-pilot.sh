#!/usr/bin/env bash
# Runs only on a disposable GitHub-hosted emulator; never on the shared phone.
set -euo pipefail

[[ "${GITHUB_ACTIONS:-}" == true ]] || { echo 'This runner requires a disposable GitHub Actions emulator' >&2; exit 2; }
[[ "${MAESTRO_REPETITIONS:-}" =~ ^([1-9]|1[0-9]|20)$ ]] || exit 2
[[ "${MAESTRO_NEGATIVE_CONTROL:-false}" =~ ^(true|false)$ ]] || exit 2
reports=build/maestro-pilot
mkdir -p "$reports/suite"

finish() {
  result=$?
  printf 'finished_at=%s\nexit_code=%s\n' "$(date +%s)" "$result" >> "$reports/timings.env"
  adb -s emulator-5554 logcat -d -t 1000 > "$reports/logcat.txt" 2>&1 || true
  exit "$result"
}
trap finish EXIT
printf 'emulator_ready_at=%s\n' "$(date +%s)" >> "$reports/timings.env"
[[ "$(adb -s emulator-5554 shell getprop ro.build.version.sdk | tr -d '\r')" == 34 ]]
[[ "$(adb -s emulator-5554 shell getprop ro.product.cpu.abi | tr -d '\r')" == x86_64 ]]
# The pilot intentionally uses a dev benchmark APK; -t is confined to this emulator.
adb -s emulator-5554 install -r -t "$reports/apk/app.apk"
adb -s emulator-5554 shell settings put global airplane_mode_on 1
adb -s emulator-5554 shell svc wifi disable
adb -s emulator-5554 shell svc data disable

python3 - <<'PY'
import os
from pathlib import Path
flow = Path('.maestro/onboarding.yaml').read_text()
suite = Path('build/maestro-pilot/suite')
for index in range(1, int(os.environ['MAESTRO_REPETITIONS']) + 1):
    case = flow.replace('name: Welcome, sign in, and return', f'name: Onboarding journey {index:02}')
    case = case.replace('welcome-returned', f'welcome-returned-{index:02}')
    (suite / f'onboarding-{index:02}.yaml').write_text(case)
if os.environ.get('MAESTRO_NEGATIVE_CONTROL') == 'true':
    # The same real journey must pass before the deliberately impossible assertion.
    case = flow.replace('name: Welcome, sign in, and return', 'name: Intentional assertion failure')
    (suite / 'negative-control.yaml').write_text(case + '- assertVisible: "MAESTRO_NEGATIVE_CONTROL_IMPOSSIBLE_3141"\n')
PY
printf 'test_started_at=%s\n' "$(date +%s)" >> "$reports/timings.env"
maestro --version > "$reports/maestro-version.txt"
maestro --device emulator-5554 test --format JUNIT --output "$reports/junit.xml" \
  --test-suite-name 'White Noise onboarding pilot' --debug-output "$reports/debug" \
  --test-output-dir "$reports/screenshots" "$reports/suite"
