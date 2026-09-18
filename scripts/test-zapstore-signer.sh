#!/usr/bin/env bash
# Contact the real bunker, but sign only an invalid.example APK fixture offline.
# Never print connection URLs, client secrets, or signed event payloads.
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=scripts/release-properties.sh
source "$repo_dir/scripts/release-properties.sh"
[[ "${SIGN_WITH:-}" == bunker://* ]] || { echo 'Missing bunker connection'; exit 1; }
[[ "${BUNKER_CLIENT_KEY:-}" =~ ^[0-9a-fA-F]{64}$ ]] || { echo 'Invalid client key'; exit 1; }
[[ "$(release_property ZSP_VERSION)" == 0.4.17 ]] || { echo 'Update signer test source pin'; exit 1; }
umask 077
temporary_dir="$(mktemp -d)"
trap 'rm -rf "$temporary_dir"' EXIT
bash "$repo_dir/scripts/install-zsp.sh" "$temporary_dir/zsp"
git -C "$temporary_dir" init -q source
git -C "$temporary_dir/source" fetch -q --depth 1 https://github.com/zapstore/zsp.git c50c1ccbf32d7fa6ae00f74990ce2c83f3bac6a1
git -C "$temporary_dir/source" checkout -q --detach FETCH_HEAD
sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
cat > "$temporary_dir/AndroidManifest.xml" <<'XML'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="invalid.example.zspfixture" android:versionCode="1" android:versionName="2026.9.9">
  <uses-sdk android:minSdkVersion="30" android:targetSdkVersion="36"/>
  <application android:label="Offline signer fixture"/>
</manifest>
XML
"$sdk_dir/build-tools/36.0.0/aapt2" link -I "$sdk_dir/platforms/android-36/android.jar" \
  --manifest "$temporary_dir/AndroidManifest.xml" -o "$temporary_dir/unsigned.apk"
keytool -genkeypair -alias fixture -keystore "$temporary_dir/fixture.jks" \
  -storepass disposable-fixture -keypass disposable-fixture -dname 'CN=Offline Test Fixture' \
  -keyalg RSA -validity 1 >/dev/null 2>&1
"$sdk_dir/build-tools/36.0.0/apksigner" sign --ks "$temporary_dir/fixture.jks" \
  --ks-pass pass:disposable-fixture --out "$temporary_dir/fixture.apk" "$temporary_dir/unsigned.apk"
# Compile before opening the connection so the invitation is consumed only by
# the runtime using the persistent CI client key.
(
  cd "$temporary_dir/source"
  GOWORK=off go build -o "$temporary_dir/test-signer" "$repo_dir/scripts/test-zapstore-signer.go"
)
export EXPECTED_PUBLISHER
EXPECTED_PUBLISHER="$(release_property ZAPSTORE_PUBLISHER_PUBKEY)"
"$temporary_dir/test-signer" "$temporary_dir/zsp" "$temporary_dir/fixture.apk"
if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  printf 'Verified pinned ZSP offline signatures for kinds 3063, 30063, 32267; verified a separate expired kind 24242 upload-auth signature. Persistent CI client reused. No files uploaded and no release events published.\n' >> "$GITHUB_STEP_SUMMARY"
fi
