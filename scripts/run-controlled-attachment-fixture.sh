#!/usr/bin/env bash
set -euo pipefail

# Only a disposable emulator may receive this generated-fixture measurement package.
serial="${1:?Pass the exact emulator serial}"
distribution="${2:-Play}"
budget_profile="${3:-reference-api30-arm64}"
sender_mode="${4:-native}"
case "$sender_mode" in native|controller|controller-cancel|controller-restart|controller-resume|controller-resume-changed|controller-unknown-length|controller-background|controller-lock|controller-automatic-resume|controller-phases|controller-media|controller-apk) ;; *) echo 'Invalid fixture sender mode' >&2; exit 2 ;; esac
runner_args=()
if [[ "$sender_mode" != native ]]; then runner_args+=(--android-send-controller); fi
if [[ "$sender_mode" == controller-cancel ]]; then runner_args+=(--held-cancellation); fi
if [[ "$sender_mode" == controller-restart ]]; then runner_args+=(--process-restart); fi
if [[ "$sender_mode" == controller-resume ]]; then runner_args+=(--transport-resume compatible); fi
if [[ "$sender_mode" == controller-resume-changed ]]; then runner_args+=(--transport-resume changed-validator); fi
if [[ "$sender_mode" == controller-unknown-length ]]; then runner_args+=(--unknown-length); fi
if [[ "$sender_mode" == controller-background ]]; then runner_args+=(--platform-background); fi
if [[ "$sender_mode" == controller-lock ]]; then runner_args+=(--platform-background --platform-lock); fi
if [[ "$sender_mode" == controller-automatic-resume ]]; then runner_args+=(--automatic-resume); fi
if [[ "$sender_mode" == controller-phases ]]; then runner_args+=(--native-phases); fi
case "$serial" in emulator-*) ;; *) echo 'Disposable emulator required' >&2; exit 2 ;; esac
case "$distribution" in Play|Zapstore) ;; *) echo 'Invalid distribution' >&2; exit 2 ;; esac
[[ "$(adb -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r\n')" == 1 ]] || exit 2

./gradlew --init-script scripts/media-latency.init.gradle \
  ":app:assembleDev${distribution}Debug" ":app:assembleDev${distribution}DebugAndroidTest" --no-daemon
app_apks=(app/build/outputs/apk/dev"$distribution"/debug/*-universal-debug.apk)
test_apks=(app/build/outputs/apk/androidTest/dev"$distribution"/debug/*.apk)
[[ ${#app_apks[@]} == 1 && ${#test_apks[@]} == 1 ]] || exit 2
[[ -f "${app_apks[0]}" && -f "${test_apks[0]}" ]] || exit 2
adb -s "$serial" install -r "${app_apks[0]}"
adb -s "$serial" install -r "${test_apks[0]}"

report_root="$(mktemp -d "${TMPDIR:-/tmp}/wn-attachment-fixture.XXXXXX")"
mkdir -p app/build/reports/attachment-fixture
report_name="received-${distribution}"
if [[ "$sender_mode" != native ]]; then report_name+="-${sender_mode}"; fi
if [[ "$sender_mode" == controller-apk ]]; then
  python3 tools/attachment-fixture/apk_installer_runner.py --serial "$serial" --root "$report_root" \
    --output "app/build/reports/attachment-fixture/${report_name}.json" --distribution "$distribution" \
    --budget-profile "$budget_profile"
  exit
fi
if [[ "$sender_mode" == controller-media ]]; then
  python3 tools/attachment-fixture/media_lifecycle_runner.py --serial "$serial" --root "$report_root" \
    --output "app/build/reports/attachment-fixture/${report_name}.json" --budget-profile "$budget_profile"
  exit
fi
python3 tools/attachment-fixture/device_runner.py --serial "$serial" --root "$report_root" \
  --output "app/build/reports/attachment-fixture/${report_name}.json" --budget-profile "$budget_profile" "${runner_args[@]}"
