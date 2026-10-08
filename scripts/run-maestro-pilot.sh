#!/usr/bin/env bash
# Runs only on a disposable GitHub-hosted emulator; never on the shared phone.
set -euo pipefail

[[ "${GITHUB_ACTIONS:-}" == true ]] || { echo 'This runner requires a disposable GitHub Actions emulator' >&2; exit 2; }
[[ "${MAESTRO_REPETITIONS:-}" =~ ^([1-9]|1[0-9]|20)$ ]] || exit 2
[[ "${MAESTRO_NEGATIVE_CONTROL:-false}" =~ ^(true|false)$ ]] || exit 2
reports=build/maestro-pilot
python3 scripts/maestro_suite.py prepare --suite "${MAESTRO_SUITE:-onboarding}" \
  --repetitions "$MAESTRO_REPETITIONS" --negative "${MAESTRO_NEGATIVE_CONTROL:-false}" --destination "$reports"
# Negative assertion remains MAESTRO_NEGATIVE_CONTROL_IMPOSSIBLE_3141.


finish() {
  # Preserve the original result, end timestamp and bounded emulator logs.
  result=$?
  if [[ "${maestro_started:-false}" == true ]]; then
    python3 scripts/maestro_suite.py report --destination "$reports" --maestro-exit "$result" || result=2
  fi
  printf 'finished_at=%s\nexit_code=%s\n' "$(date +%s)" "$result" >> "$reports/timings.env"
  timeout --kill-after=5s 15s adb -s emulator-5554 logcat -d -t 1000 > "$reports/logcat.txt" 2>&1 || true
  exit "$result"
}
trap finish EXIT
printf 'emulator_ready_at=%s\n' "$(date +%s)" >> "$reports/timings.env"
[[ "$(adb -s emulator-5554 shell getprop ro.build.version.sdk | tr -d '\r')" == 34 ]]
[[ "$(adb -s emulator-5554 shell getprop ro.product.cpu.abi | tr -d '\r')" == x86_64 ]]
# Check Android's locale properties, including a fresh image's product default.
python3 scripts/maestro_apk.py locale > "$reports/locale.txt"
python3 scripts/maestro_apk.py offline > "$reports/network-state.txt"
# The pilot intentionally uses a dev benchmark APK; -t is confined to this emulator.
adb -s emulator-5554 install -r -t "$reports/apk/app.apk"

printf 'test_started_at=%s\n' "$(date +%s)" >> "$reports/timings.env"
maestro --version > "$reports/maestro-version.txt"
maestro_started=true
# Leave time inside the 15-minute job for diagnostics and artifact upload.
timeout --signal=TERM --kill-after=30s 10m maestro --device emulator-5554 test --format JUNIT --output "$reports/junit.xml" \
  --test-suite-name 'White Noise onboarding pilot' --debug-output "$reports/debug" \
  --test-output-dir "$reports/screenshots" "$reports/suite"
