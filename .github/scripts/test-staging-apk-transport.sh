#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
verifier=${STAGING_APK_VERIFIER:-$script_dir/verify-staging-apk-transport.sh}
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/bin"

cat > "$tmp/bin/apkanalyzer" <<'FAKE_ANALYZER'
#!/usr/bin/env bash
set -euo pipefail
case "${2:?}" in
  application-id) printf '%s\n' "${FAKE_PACKAGE:-dev.ipf.whitenoise.android.staging}" ;;
  print) printf '<application android:extractNativeLibs="%s" />\n' "${FAKE_EXTRACT_NATIVE_LIBS:-true}" ;;
  *) exit 64 ;;
esac
FAKE_ANALYZER

chmod +x "$tmp/bin/apkanalyzer"

make_apk() {
  local path=$1 abi=${2:-arm64-v8a} compression=${3:-deflated}
  python3 - "$path" "$abi" "$compression" <<'PY'
import sys
import zipfile

path, abi, compression = sys.argv[1:]
method = zipfile.ZIP_STORED if compression == "stored" else zipfile.ZIP_DEFLATED
with zipfile.ZipFile(path, "w") as archive:
    archive.writestr("AndroidManifest.xml", b"manifest", compress_type=zipfile.ZIP_DEFLATED)
    archive.writestr(f"lib/{abi}/libmarmot.so", b"native-library", compress_type=method)
PY
}

apk="$tmp/staging.apk"
make_apk "$apk"
PATH="$tmp/bin:$PATH" "$verifier" "$apk" >/dev/null

expect_rejection() {
  local label=$1
  shift
  if "$@" >/dev/null 2>&1; then
    printf 'Expected rejection: %s\n' "$label" >&2
    exit 1
  fi
}

expect_rejection 'wrong package' env PATH="$tmp/bin:$PATH" FAKE_PACKAGE=invalid.package "$verifier" "$apk"
expect_rejection 'native extraction disabled' env PATH="$tmp/bin:$PATH" FAKE_EXTRACT_NATIVE_LIBS=false "$verifier" "$apk"
make_apk "$apk" x86_64
expect_rejection 'wrong ABI' env PATH="$tmp/bin:$PATH" "$verifier" "$apk"
make_apk "$apk" arm64-v8a stored
expect_rejection 'uncompressed native library' env PATH="$tmp/bin:$PATH" "$verifier" "$apk"
printf 'not a ZIP\n' > "$apk"
expect_rejection 'invalid ZIP' env PATH="$tmp/bin:$PATH" "$verifier" "$apk"
make_apk "$apk"

ln -s "$apk" "$tmp/staging-link.apk"
expect_rejection 'symlink APK' env PATH="$tmp/bin:$PATH" "$verifier" "$tmp/staging-link.apk"
expect_rejection 'missing APK' env PATH="$tmp/bin:$PATH" "$verifier" "$tmp/missing.apk"

truncate -s 52428801 "$apk"
expect_rejection 'transport-unsafe size' env PATH="$tmp/bin:$PATH" "$verifier" "$apk"

printf 'staging APK transport validation fixtures: passed\n'
