#!/usr/bin/env bash
# Build the disposable SAF source/viewer APK with the CI Android SDK.
set -euo pipefail

fixture_dir="$(cd "$(dirname "$0")" && pwd)"
sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -d "$sdk_root/build-tools" && -d "$sdk_root/platforms" ]] || { echo "Android SDK unavailable" >&2; exit 1; }
build_tools="$(find "$sdk_root/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
platform="$(find "$sdk_root/platforms" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
android_jar="$platform/android.jar"
[[ -f "$android_jar" ]] || { echo "Android platform jar unavailable" >&2; exit 1; }

mkdir -p "$fixture_dir/build/classes" "$fixture_dir/build/dex"
python3 "$fixture_dir/make_assets.py"
javac -source 8 -target 8 -classpath "$android_jar" -d "$fixture_dir/build/classes" "$fixture_dir"/src/dev/ipf/fixture/*.java
"$build_tools/d8" --min-api 26 --lib "$android_jar" --output "$fixture_dir/build/dex" "$fixture_dir"/build/classes/dev/ipf/fixture/*.class
"$build_tools/aapt2" link -o "$fixture_dir/build/unsigned.apk" --manifest "$fixture_dir/AndroidManifest.xml" -I "$android_jar" -A "$fixture_dir/assets" --min-sdk-version 26 --target-sdk-version 35
(cd "$fixture_dir/build/dex" && "$build_tools/aapt" add "$fixture_dir/build/unsigned.apk" classes.dex)
"$build_tools/zipalign" -f 4 "$fixture_dir/build/unsigned.apk" "$fixture_dir/build/aligned.apk"
if [[ ! -f "$fixture_dir/build/fixture-keystore.p12" ]]; then
  keytool -genkeypair -alias fixture -keyalg RSA -keysize 2048 -validity 2 -keystore "$fixture_dir/build/fixture-keystore.p12" -storetype PKCS12 -storepass changeit -keypass changeit -dname 'CN=WN PR 2830 Test Fixture' -noprompt >/dev/null
fi
"$build_tools/apksigner" sign --ks "$fixture_dir/build/fixture-keystore.p12" --ks-key-alias fixture --ks-pass pass:changeit --key-pass pass:changeit --out "$fixture_dir/build/fixture.apk" "$fixture_dir/build/aligned.apk"
"$build_tools/apksigner" verify "$fixture_dir/build/fixture.apk"
printf '%s\n' "$fixture_dir/build/fixture.apk"
