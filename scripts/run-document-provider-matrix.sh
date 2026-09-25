#!/usr/bin/env bash
# Fresh-emulator matrix for real SAF grants, relay delivery, viewers, and cleanup.
set -euo pipefail

[[ "${GITHUB_REF:-}" == "refs/heads/datawav/arbitrary-safe-files" ]] || {
  echo "Document matrix is scoped to the PR 2830 branch" >&2
  exit 1
}
[[ "${GITHUB_SHA:-}" =~ ^[0-9a-f]{40}$ ]] || { echo "Missing exact source revision" >&2; exit 1; }

fixture_apk="$(./scripts/document-provider-fixture/build.sh | tail -n 1)"
[[ -f "$fixture_apk" ]] || { echo "Provider fixture build missing" >&2; exit 1; }
export PR_NUMBER=2830
export PR_PREVIEW_CHANNEL=isolated
export PREVIEW_HEAD_SHA="$GITHUB_SHA"
./gradlew :app:assemblePreviewPlayDebug :app:assemblePreviewPlayDebugAndroidTest \
  -Pwhitenoise.previewProviderMatrixBuild=true --build-cache --no-daemon --stacktrace

sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
aapt="$(find "$sdk_root/build-tools" -mindepth 2 -maxdepth 2 -type f -name aapt | sort -V | tail -n 1)"
[[ -x "$aapt" ]] || { echo "aapt unavailable" >&2; exit 1; }
apk_root=app/build/outputs/apk
mapfile -t app_apks < <(find "$apk_root" -type f -name '*universal*debug*.apk' | sort)
mapfile -t test_apks < <(find "$apk_root" -type f -name '*androidTest*.apk' | sort)
[[ "${#app_apks[@]}" -eq 1 && "${#test_apks[@]}" -eq 1 ]] || {
  echo "Expected one isolated preview universal APK and one test APK" >&2
  exit 1
}
package_name() { "$aapt" dump badging "$1" | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -n 1; }
app_package=dev.ipf.whitenoise.android.preview.pr2830
test_package="$app_package.test"
[[ "$(package_name "${app_apks[0]}")" == "$app_package" ]] || { echo "Wrong preview package" >&2; exit 1; }
[[ "$(package_name "${test_apks[0]}")" == "$test_package" ]] || { echo "Wrong test package" >&2; exit 1; }
if adb shell pm path "$app_package" | grep -q '^package:'; then
  echo "Isolated preview already exists; matrix requires a fresh emulator" >&2
  exit 1
fi

adb install -r -t "$fixture_apk"
adb install -r -t "${app_apks[0]}"
adb install -r -t "${test_apks[0]}"

report_dir=app/build/reports/document-provider-matrix
mkdir -p "$report_dir"
if ! timeout 1500 adb shell am instrument -w \
  -e class dev.ipf.whitenoise.android.ui.conversation.media.DocumentProviderRelayMatrixTest#providerGrantsSurviveReadAndRelayDeliveryButRevokeAndPlaintextCleanupWork \
  -e documentProviderMatrix true \
  "$test_package/androidx.test.runner.AndroidJUnitRunner" >"$report_dir/instrumentation.txt" 2>&1; then
  tail -n 100 "$report_dir/instrumentation.txt" >&2
  exit 1
fi
grep -q '^OK (1 test)' "$report_dir/instrumentation.txt" || {
  tail -n 100 "$report_dir/instrumentation.txt" >&2
  exit 1
}
printf 'Exact-head document provider and relay matrix passed at %s\n' "$GITHUB_SHA"
