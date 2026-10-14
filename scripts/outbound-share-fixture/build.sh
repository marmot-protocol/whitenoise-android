#!/usr/bin/env bash
# Build a separate share receiver with the hosted Android SDK, without Gradle.
set -euo pipefail

fixture_dir="$(cd "$(dirname "$0")" && pwd)"
sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -d "$sdk_root/build-tools" && -d "$sdk_root/platforms" ]] || { echo "Android SDK unavailable" >&2; exit 1; }
build_tools="$(find "$sdk_root/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
platform="$(find "$sdk_root/platforms" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
android_jar="$platform/android.jar"
mkdir -p "$fixture_dir/build/classes" "$fixture_dir/build/dex"
javac -source 8 -target 8 -classpath "$android_jar" -d "$fixture_dir/build/classes" "$fixture_dir"/src/dev/ipf/sharefixture/*.java
"$build_tools/d8" --min-api 30 --lib "$android_jar" --output "$fixture_dir/build/dex" "$fixture_dir"/build/classes/dev/ipf/sharefixture/*.class
for kind in fixture control; do
  manifest="$fixture_dir/AndroidManifest.xml"
  [[ "$kind" != control ]] || manifest="$fixture_dir/ControlManifest.xml"
  "$build_tools/aapt2" link -o "$fixture_dir/build/$kind-unsigned.apk" --manifest "$manifest" -I "$android_jar" --min-sdk-version 30 --target-sdk-version 37
  (cd "$fixture_dir/build/dex" && "$build_tools/aapt" add "$fixture_dir/build/$kind-unsigned.apk" classes.dex)
  "$build_tools/zipalign" -f 4 "$fixture_dir/build/$kind-unsigned.apk" "$fixture_dir/build/$kind-aligned.apk"
  if [[ ! -f "$fixture_dir/build/$kind-keystore.p12" ]]; then
    keytool -genkeypair -alias fixture -keyalg RSA -keysize 2048 -validity 2 -keystore "$fixture_dir/build/$kind-keystore.p12" -storetype PKCS12 -storepass changeit -keypass changeit -dname "CN=WN Share Visibility $kind Fixture" -noprompt >/dev/null
  fi
  "$build_tools/apksigner" sign --ks "$fixture_dir/build/$kind-keystore.p12" --ks-key-alias fixture --ks-pass pass:changeit --key-pass pass:changeit --out "$fixture_dir/build/$kind.apk" "$fixture_dir/build/$kind-aligned.apk"
  "$build_tools/apksigner" verify "$fixture_dir/build/$kind.apk"
  printf '%s\n' "$fixture_dir/build/$kind.apk"
done
