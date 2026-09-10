#!/usr/bin/env bash
# Real publisher contract test using a synthetic APK and loopback-only signer.
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=scripts/release-properties.sh
source "$repo_dir/scripts/release-properties.sh"
[[ "$(release_property ZSP_VERSION)" == 0.4.17 ]] || {
  echo 'Update the rehearsal source pin with the ZSP binary pin' >&2; exit 1;
}
temporary_dir="$(mktemp -d)"
trap 'rm -rf "$temporary_dir"' EXIT
receipt="${1:-$repo_dir/build/reports/zsp-offline.json}"
mkdir -p "$(dirname "$receipt")"
receipt="$(cd "$(dirname "$receipt")" && pwd)/$(basename "$receipt")"
bash "$repo_dir/scripts/install-zsp.sh" "$temporary_dir/zsp"
# Fetch the exact commit behind ZSP v0.4.17 for its checksummed Go dependencies.
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
fixture_fingerprint="$(keytool -exportcert -alias fixture -keystore "$temporary_dir/fixture.jks" \
  -storepass disposable-fixture | python3 -c 'import hashlib, sys; print(hashlib.sha256(sys.stdin.buffer.read()).hexdigest())')"
# Match production verification: prefer PATH, otherwise the newest installed
# build-tools path. Using only 36.0.0 here missed newer scheme-prefixed labels.
apksigner_bin="$(command -v apksigner || find "$sdk_dir/build-tools" -mindepth 2 -maxdepth 2 \
  -type f -name apksigner | sort | tail -1)"
printf 'Verifying fixture with %s\n' "$apksigner_bin"
python3 "$repo_dir/scripts/verify_apk_signature.py" "$apksigner_bin" \
  "$temporary_dir/fixture.apk" "$fixture_fingerprint" >/dev/null
(
  cd "$temporary_dir/source"
  GOWORK=off go run "$repo_dir/scripts/rehearse-zsp.go" "$temporary_dir/zsp" "$temporary_dir/fixture.apk"
) > "$receipt"
cat "$receipt"
