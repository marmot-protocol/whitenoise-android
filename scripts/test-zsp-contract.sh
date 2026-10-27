#!/usr/bin/env bash
# Real publisher contract test using a synthetic APK and loopback-only signer.
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=scripts/release-properties.sh
source "$repo_dir/scripts/release-properties.sh"

temporary_dir="$(mktemp -d)"
trap 'rm -rf "$temporary_dir"' EXIT
receipt="${1:-$repo_dir/build/reports/zsp-v2.json}"
mkdir -p "$(dirname "$receipt")"
receipt="$(cd "$(dirname "$receipt")" && pwd)/$(basename "$receipt")"
bash "$repo_dir/scripts/install-zsp.sh" "$temporary_dir/zsp"
# Dependency-only source pin: both CLI versions use go-nostr 0.52.3.
# Do not build the ZSP CLI here; install-zsp.sh supplies the checksummed v2 binary.
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
  -storetype PKCS12 -storepass disposable-fixture -keypass disposable-fixture -dname 'CN=Offline Test Fixture' \
  -keyalg RSA -validity 1 >/dev/null 2>&1
"$sdk_dir/build-tools/36.0.0/apksigner" sign --ks "$temporary_dir/fixture.jks" \
  --ks-pass pass:disposable-fixture --out "$temporary_dir/fixture.apk" "$temporary_dir/unsigned.apk"
# Export ONLY the disposable fixture key so the loopback server can supply C1.
# Production publication never receives an Android keystore.
openssl pkcs12 -in "$temporary_dir/fixture.jks" -passin pass:disposable-fixture \
  -nodes -nocerts -out "$temporary_dir/fixture-key.pem" 2>/dev/null
keytool -exportcert -alias fixture -keystore "$temporary_dir/fixture.jks" \
  -storepass disposable-fixture -file "$temporary_dir/fixture-cert.der" >/dev/null 2>&1
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
  GOWORK=off go test "$repo_dir/scripts/test-zapstore-signer.go" "$repo_dir/scripts/test-zapstore-signer_test.go" >&2
  GOWORK=off go test -race "$repo_dir/scripts/rehearse-zsp.go" "$repo_dir/scripts/rehearse-zsp_test.go" >&2
  GOWORK=off go test "$repo_dir/scripts/zapstore-readback.go" "$repo_dir/scripts/zapstore-readback_test.go" >&2
  GOWORK=off go build -tags debug -o /dev/null "$repo_dir/scripts/diagnose-nip46-relays.go" >&2
  GOWORK=off go run "$repo_dir/scripts/rehearse-zsp.go" "$temporary_dir/zsp" "$temporary_dir/fixture.apk" "$temporary_dir/fixture-key.pem" "$temporary_dir/fixture-cert.der"
) > "$receipt"
cat "$receipt"
