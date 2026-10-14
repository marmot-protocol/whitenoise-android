#!/usr/bin/env bash
# Exercise real implicit sharing on the current and a fresh secondary emulator user.
set -euo pipefail
[[ "${GITHUB_SHA:-}" =~ ^[0-9a-f]{40}$ ]] || { echo "Missing exact source revision" >&2; exit 1; }
case "${OUTBOUND_SHARE_ANDROID_API:-}" in 30|34|37.0) ;; *) echo "Select a supported share-contract API" >&2; exit 1 ;; esac
report_dir=app/build/reports/outbound-share-contract
mkdir -p "$report_dir"
actual_sdk="$(adb -e shell getprop ro.build.version.sdk | tr -d '\r')"
[[ "$actual_sdk" == "${OUTBOUND_SHARE_ANDROID_API%.*}" ]] || { echo "Emulator SDK differs from selected API" >&2; exit 1; }
image_fingerprint="$(adb -e shell getprop ro.build.fingerprint | tr -d '\r')"
[[ -n "$image_fingerprint" ]] || { echo "Missing emulator image fingerprint" >&2; exit 1; }
printf '%s\n' "$image_fingerprint" > "$report_dir/image-fingerprint.txt"
printf '%s\n' "$actual_sdk" > "$report_dir/android-sdk.txt"
if [[ "$actual_sdk" == 37 ]]; then
  expected_token="$(cat "$report_dir/android17-emulator-stabilize.request")"
  [[ -n "$expected_token" ]] || { echo "Missing Android 17 stabilization request" >&2; exit 1; }
  for attempt in {1..60}; do
    state="$(cat "$report_dir/android17-emulator-stabilize.status" 2>/dev/null || true)"
    [[ "$state" != "failure $expected_token" ]] || { echo "Android 17 stabilization failed" >&2; exit 1; }
    [[ "$state" != "success $expected_token" ]] || break
    sleep 1
  done
  [[ "$state" == "success $expected_token" && "$(adb -e shell getprop debug.sf.luma_sampling | tr -d '\r')" == 0 ]] || {
    echo "Android 17 stabilization did not complete" >&2
    exit 1
  }
fi
./scripts/install-outbound-share-fixture.sh
./gradlew :app:assembleDevZapstoreDebug :app:assembleDevZapstoreDebugAndroidTest --no-daemon --stacktrace

sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
aapt="$(find "$sdk_root/build-tools" -mindepth 2 -maxdepth 2 -type f -name aapt | sort -V | tail -n 1)"
mapfile -t app_apks < <(find app/build/outputs/apk -type f -name '*universal*debug*.apk' | sort)
mapfile -t test_apks < <(find app/build/outputs/apk -type f -name '*androidTest*.apk' | sort)
[[ "${#app_apks[@]}" -eq 1 && "${#test_apks[@]}" -eq 1 ]] || { echo "Expected one app and one instrumentation APK" >&2; exit 1; }
# Read the APK identity before granting either app access to the disposable profile.
package_name() { "$aapt" dump badging "$1" | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -n 1; }
app_package="$(package_name "${app_apks[0]}")"
test_package="$(package_name "${test_apks[0]}")"
[[ "$app_package" == dev.ipf.whitenoise.android.dev && "$test_package" == "$app_package.test" ]] || {
  echo "Unexpected share-contract APK identity" >&2
  exit 1
}
adb -e install -r -t "${app_apks[0]}"
adb -e install -r -t "${test_apks[0]}"
printf '%s\n' "$GITHUB_SHA" > "$report_dir/source-revision.txt"
sha256sum "${app_apks[0]}" "${test_apks[0]}" scripts/outbound-share-fixture/build/{fixture,control}.apk > "$report_dir/apk-sha256.txt"
original_user="$(adb -e shell am get-current-user | tr -d '\r')"
[[ "$original_user" =~ ^[0-9]+$ ]] || { echo "Unknown emulator user" >&2; exit 1; }
collect_runtime_evidence() {
  local result=$?
  timeout 15s adb -e logcat -d -v threadtime > "$report_dir/logcat.txt" 2>&1 || true
  timeout 15s adb -e shell am switch-user "$original_user" >/dev/null || true
  exit "$result"
}
trap collect_runtime_evidence EXIT

# All declared cases must execute in this user; ignored/assumption-skipped tests cannot qualify it.
run_user() {
  local user_id="$1"
  local report="$report_dir/user-$user_id.txt"
  timeout --signal=INT --kill-after=30s 10m adb -e shell am instrument --user "$user_id" -w -r \
    -e class dev.ipf.whitenoise.android.share.OutboundShareDeviceContractTest \
    -e timeout_msec 120000 "$test_package/androidx.test.runner.AndroidJUnitRunner" > "$report" 2>&1
  if ! grep -q '^OK (5 tests)' "$report" || grep -q 'INSTRUMENTATION_STATUS_CODE: -[34]' "$report"; then
    tail -n 100 "$report" >&2
    exit 1
  fi
  for method in implicitTxtChooserTransfersExactBytesNameAndOnlySelectedUri implicitMultiFileChooserPreservesCaptionOrderAndMixedMime; do
    grep -q "^INSTRUMENTATION_STATUS: test=$method" "$report" || { echo "Required $method did not execute" >&2; exit 1; }
  done
}

run_user "$original_user"
secondary_user="$(adb -e shell pm create-user 'WN share contract' | sed -n 's/.*user id \([0-9]*\).*/\1/p' | tr -d '\r')"
[[ "$secondary_user" =~ ^[1-9][0-9]*$ ]] || { echo "Secondary emulator user was not created" >&2; exit 1; }
for package in "$app_package" "$test_package" dev.ipf.sharefixture dev.ipf.sharecontrol; do
  adb -e shell pm install-existing --user "$secondary_user" "$package"
done
adb -e shell am switch-user "$secondary_user"
for attempt in {1..30}; do
  [[ "$(adb -e shell am get-current-user | tr -d '\r')" != "$secondary_user" ]] || break
  sleep 1
done
[[ "$(adb -e shell am get-current-user | tr -d '\r')" == "$secondary_user" ]] || { echo "Secondary user did not become foreground" >&2; exit 1; }
adb -e shell wm dismiss-keyguard
run_user "$secondary_user"
printf 'Exact-head implicit share contract passed for both users at %s\n' "$GITHUB_SHA"
