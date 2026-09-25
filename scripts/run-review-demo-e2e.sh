#!/usr/bin/env bash
# Run the real-relay demo only on a fresh, disposable isolated-preview emulator.
set -euo pipefail

[[ "${GITHUB_REF:-}" == "refs/heads/datawav/issue-2823-demo-conversation" ]] || {
  echo "Review demo E2E is scoped to the PR 2838 branch" >&2
  exit 1
}
[[ "${GITHUB_SHA:-}" =~ ^[0-9a-f]{40}$ ]] || {
  echo "Missing exact source revision" >&2
  exit 1
}

export PR_NUMBER=2838
export PR_PREVIEW_CHANNEL=isolated
export PREVIEW_HEAD_SHA="$GITHUB_SHA"
./gradlew :app:assemblePreviewPlayDebug :app:assemblePreviewPlayDebugAndroidTest \
  -Pwhitenoise.previewE2eTestBuild=true --build-cache --no-daemon --stacktrace

sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -d "$sdk_root/build-tools" ]] || { echo "Android build tools unavailable" >&2; exit 1; }
aapt="$(find "$sdk_root/build-tools" -mindepth 2 -maxdepth 2 -type f -name aapt | sort -V | tail -n 1)"
[[ -x "$aapt" ]] || { echo "aapt unavailable" >&2; exit 1; }

mapfile -t app_apks < <(find app/build/outputs/apk/preview/play/debug -type f -name '*universal*.apk' | sort)
mapfile -t test_apks < <(find app/build/outputs/apk/androidTest -type f -name '*.apk' | sort)
[[ "${#app_apks[@]}" -eq 1 && "${#test_apks[@]}" -eq 1 ]] || {
  echo "Expected one universal isolated preview APK and one test APK" >&2
  exit 1
}
app_apk="${app_apks[0]}"
test_apk="${test_apks[0]}"
package_name() { "$aapt" dump badging "$1" | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -n 1; }
app_package=dev.ipf.whitenoise.android.preview.pr2838
test_package="$app_package.test"
[[ "$(package_name "$app_apk")" == "$app_package" ]] || { echo "Wrong preview package" >&2; exit 1; }
[[ "$(package_name "$test_apk")" == "$test_package" ]] || { echo "Wrong test package" >&2; exit 1; }
if adb shell pm path "$app_package" | grep -q '^package:'; then
  echo "Isolated preview already exists; the test requires a fresh emulator" >&2
  exit 1
fi

adb install -r -t "$app_apk"
adb install -r -t "$test_apk"

# This key and every published profile/message in the test are disposable.
# Keep the key out of logs and discard it after the onboarding instrument.
nsec="$(python3 - <<'PY'
import secrets

alphabet = 'qpzry9x8gf2tvdw0s3jn54khce6mua7l'
generator = [0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3]
order = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141

while True:
    value = int.from_bytes(secrets.token_bytes(32), 'big')
    if 0 < value < order:
        break
bits = ''.join(f'{byte:08b}' for byte in value.to_bytes(32, 'big'))
data = [int((bits + '0' * 4)[index:index + 5], 2) for index in range(0, 260, 5)]
hrp = 'nsec'
expanded = [ord(char) >> 5 for char in hrp] + [0] + [ord(char) & 31 for char in hrp]
check = 1
for number in expanded + data + [0] * 6:
    top = check >> 25
    check = ((check & 0x1ffffff) << 5) ^ number
    for bit, polynomial in enumerate(generator):
        if (top >> bit) & 1:
            check ^= polynomial
check ^= 1
checksum = [(check >> (5 * (5 - index))) & 31 for index in range(6)]
print('nsec1' + ''.join(alphabet[number] for number in data + checksum))
PY
)"
[[ "${#nsec}" -eq 63 ]] || { echo "Disposable key generation failed" >&2; exit 1; }
printf '::add-mask::%s\n' "$nsec"

report_dir=app/build/reports/review-demo-e2e
mkdir -p "$report_dir"
setup_log="$report_dir/onboarding.txt"
demo_log="$report_dir/demo.txt"
show_failure() {
  sed -E 's/nsec1[a-z0-9]{58}/<redacted-nsec>/g' "$1" | tail -n 80 >&2
}
if ! timeout 900 adb shell am instrument -w \
  -e class dev.ipf.whitenoise.android.ui.onboarding.AccountSetupEndToEndTest#disposableNsecCompletesSetupThroughVisibleButtons \
  -e setupE2e true -e setupE2ePackageSuffix .preview.pr2838 -e setupTestNsec "$nsec" \
  "$test_package/androidx.test.runner.AndroidJUnitRunner" >"$setup_log" 2>&1; then
  show_failure "$setup_log"
  exit 1
fi
unset nsec
grep -q '^OK (1 test)' "$setup_log" || { show_failure "$setup_log"; exit 1; }

if ! timeout 900 adb shell am instrument -w \
  -e class dev.ipf.whitenoise.android.state.AppReviewDemoEndToEndTest#realInviteAcceptAndBidirectionalDelivery \
  -e reviewDemoE2e true -e reviewDemoE2ePackageSuffix .preview.pr2838 \
  "$test_package/androidx.test.runner.AndroidJUnitRunner" >"$demo_log" 2>&1; then
  show_failure "$demo_log"
  exit 1
fi
grep -q '^OK (1 test)' "$demo_log" || { show_failure "$demo_log"; exit 1; }
printf 'Onboarding and real-relay demo each passed one Android instrumentation test at %s\n' "$GITHUB_SHA"
