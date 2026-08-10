#!/usr/bin/env bash

# Runtime-verifies a minified APK on the CI emulator. ART verification is the
# protection that a successful R8/D8 build cannot provide: malformed release
# bytecode can compile and package successfully, then fail only when a class is
# loaded on a device.

set -euo pipefail

if [[ "${CI:-}" != "true" ]]; then
  echo "error: release runtime verification may install only on an ephemeral CI emulator" >&2
  exit 1
fi

if [[ $# -ne 2 ]]; then
  echo "usage: scripts/verify-release-runtime.sh <apk> <application-id>" >&2
  exit 1
fi

apk_path="$1"
application_id="$2"
report_dir="${RELEASE_VERIFY_REPORT_DIR:-app/build/reports/release-runtime-verify}"
log_path="$report_dir/logcat.txt"

if [[ ! -f "$apk_path" ]]; then
  echo "error: APK not found: $apk_path" >&2
  exit 1
fi
if [[ -z "$application_id" ]]; then
  echo "error: application id must not be empty" >&2
  exit 1
fi

mkdir -p "$report_dir"
adb wait-for-device
if [[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != "1" ]]; then
  echo "error: refusing to install the release verifier APK on a physical device" >&2
  exit 1
fi
adb install -r "$apk_path"

compile_output="$(adb shell cmd package compile -f -m verify "$application_id")"
printf '%s\n' "$compile_output" | tee "$report_dir/package-compile.txt"
if [[ "$compile_output" != *Success* ]]; then
  echo "error: ART rejected the minified APK during package verification" >&2
  exit 1
fi

adb logcat -c
adb shell am force-stop "$application_id"
launcher_component="$(
  adb shell cmd package resolve-activity \
    --brief \
    -a android.intent.action.MAIN \
    -c android.intent.category.LAUNCHER \
    "$application_id" | tr -d '\r' | tail -n 1
)"
if [[ "$launcher_component" != "$application_id/"* ]]; then
  echo "error: could not resolve launcher activity for $application_id" >&2
  exit 1
fi
if ! adb shell am start -W -n "$launcher_component" | tee "$report_dir/launch.txt"; then
  adb logcat -d -v threadtime > "$log_path"
  echo "error: release app launcher activity failed" >&2
  exit 1
fi

app_pid=""
for _ in {1..20}; do
  app_pid="$(adb shell pidof "$application_id" | tr -d '\r')"
  [[ -n "$app_pid" ]] && break
  sleep 1
done

if [[ -z "$app_pid" ]]; then
  adb logcat -d -v threadtime > "$log_path"
  echo "error: release app did not remain alive after launch" >&2
  exit 1
fi

sleep 5
stable_pid="$(adb shell pidof "$application_id" | tr -d '\r')"
if [[ "$stable_pid" != "$app_pid" ]]; then
  adb logcat -d -v threadtime > "$log_path"
  echo "error: release app process did not remain stable after launch" >&2
  exit 1
fi

adb logcat -d -v threadtime --pid="$app_pid" > "$log_path"

if grep -En "VerifyError|Verifier rejected class|FATAL EXCEPTION" "$log_path"; then
  echo "error: release runtime verification found an app fatal runtime or class-verifier failure" >&2
  exit 1
fi

printf 'Release runtime verification passed for %s (pid %s).\n' "$application_id" "$app_pid"
