#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
verifier="$script_dir/verify-pr-preview-candidates.sh"
signer="$script_dir/sign-pr-preview-candidates.sh"
stager="$script_dir/stage-signed-pr-preview-candidates.sh"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

fake_bin="$tmp/bin"
mkdir -p "$fake_bin"

cat > "$fake_bin/apkanalyzer" <<'FAKE_ANALYZER'
#!/usr/bin/env bash
set -euo pipefail
command=${2:?manifest command is required}
apk=${3:?apk is required}
channel=$(basename "$(dirname "$apk")")
case "$command" in
  application-id)
    if [[ "${FAKE_BAD_PACKAGE:-}" == "$channel" ]]; then
      printf 'invalid.preview.package\n'
    elif [[ "$channel" == stable ]]; then
      printf 'dev.ipf.whitenoise.android.preview\n'
    else
      printf 'dev.ipf.whitenoise.android.preview.pr%s\n' "$PR_NUMBER"
    fi
    ;;
  version-code) printf '%s\n' "${FAKE_VERSION_CODE:-2000000000}" ;;
  version-name) printf '%s\n' "${FAKE_VERSION_NAME:-2026.8.6-preview-pr${PR_NUMBER}-${HEAD_SHA:0:7}}" ;;
  print) printf '<application android:extractNativeLibs="%s" />\n' "${FAKE_EXTRACT_NATIVE_LIBS:-true}" ;;
  *) exit 64 ;;
esac
FAKE_ANALYZER

cat > "$fake_bin/zipinfo" <<'FAKE_ZIPINFO'
#!/usr/bin/env bash
set -euo pipefail
case "${1:-}" in
  -t) [[ "${FAKE_INVALID_ZIP:-false}" != true ]] ;;
  -1)
    printf 'AndroidManifest.xml\nlib/%s/libmarmot.so\n' "${FAKE_ABI:-arm64-v8a}"
    ;;
  -l)
    method=${FAKE_NATIVE_COMPRESSION:-defN}
    printf '%s\n' \
      "-rw-r--r--  2.0 unx ${FAKE_EXPANDED_BYTES:-1024} b- 512 defN 80-Jan-01 00:00 AndroidManifest.xml" \
      "-rw-r--r--  2.0 unx 1024 b- 512 $method 80-Jan-01 00:00 lib/arm64-v8a/libmarmot.so"
    ;;
  *) exit 64 ;;
esac
FAKE_ZIPINFO
chmod +x "$fake_bin/apkanalyzer" "$fake_bin/zipinfo"

export PR_NUMBER=2468
export HEAD_SHA=abcdef0123456789abcdef0123456789abcdef01
export BUILD_RUN_NUMBER=73

make_candidates() {
  local root=$1
  rm -rf "$root"
  for channel in stable isolated; do
    mkdir -p "$root/$channel"
    printf 'valid candidate %s\n' "$channel" > "$root/$channel/preview.apk"
    (cd "$root/$channel" && sha256sum preview.apk > SHA256SUMS)
    printf '%s\n' \
      "pr_number=$PR_NUMBER" \
      "channel=$channel" \
      "head_sha=$HEAD_SHA" \
      "run_number=$BUILD_RUN_NUMBER" > "$root/$channel/provenance.env"
  done
}

expect_rejection() {
  local label=$1
  shift
  if "$@" >/dev/null 2>&1; then
    printf 'Expected rejection: %s\n' "$label" >&2
    exit 1
  fi
}

candidates="$tmp/candidates"
make_candidates "$candidates"
PATH="$fake_bin:$PATH" "$verifier" "$candidates" >/dev/null

expect_rejection 'wrong package' env PATH="$fake_bin:$PATH" FAKE_BAD_PACKAGE=stable "$verifier" "$candidates"
expect_rejection 'wrong version code' env PATH="$fake_bin:$PATH" FAKE_VERSION_CODE=7 "$verifier" "$candidates"
expect_rejection 'wrong PR/SHA version name' env PATH="$fake_bin:$PATH" FAKE_VERSION_NAME=wrong "$verifier" "$candidates"
expect_rejection 'wrong ABI' env PATH="$fake_bin:$PATH" FAKE_ABI=x86_64 "$verifier" "$candidates"
expect_rejection 'native extraction disabled' env PATH="$fake_bin:$PATH" FAKE_EXTRACT_NATIVE_LIBS=false "$verifier" "$candidates"
expect_rejection 'uncompressed native library' env PATH="$fake_bin:$PATH" FAKE_NATIVE_COMPRESSION=stor "$verifier" "$candidates"
expect_rejection 'invalid ZIP' env PATH="$fake_bin:$PATH" FAKE_INVALID_ZIP=true "$verifier" "$candidates"

printf 'head_sha=wrong\n' >> "$candidates/stable/provenance.env"
expect_rejection 'tampered provenance' env PATH="$fake_bin:$PATH" "$verifier" "$candidates"
make_candidates "$candidates"
cp "$candidates/stable/preview.apk" "$candidates/stable/extra.apk"
expect_rejection 'multiple APKs' env PATH="$fake_bin:$PATH" "$verifier" "$candidates"
make_candidates "$candidates"
truncate -s 67108849 "$candidates/stable/preview.apk"
(cd "$candidates/stable" && sha256sum preview.apk > SHA256SUMS)
expect_rejection 'oversized APK' env PATH="$fake_bin:$PATH" "$verifier" "$candidates"
make_candidates "$candidates"
expect_rejection 'oversized expanded APK' env PATH="$fake_bin:$PATH" FAKE_EXPANDED_BYTES=536870913 "$verifier" "$candidates"

android_home="$tmp/android"
mkdir -p "$android_home/build-tools/1"
cat > "$android_home/build-tools/1/apksigner" <<'FAKE_APKSIGNER'
#!/usr/bin/env bash
set -euo pipefail
case "$1" in
  sign)
    output=
    input=${!#}
    while (( $# )); do
      if [[ "$1" == --out ]]; then output=$2; break; fi
      shift
    done
    cp "$input" "$output"
    ;;
  verify)
    cert_digest=${FAKE_CERT_DIGEST:-aabbccddaabbccddaabbccddaabbccddaabbccddaabbccddaabbccddaabbccdd}
    if [[ "${FAKE_LEGACY_CERT_OUTPUT:-false}" == true ]]; then
      printf 'Signer #1 certificate SHA-256 digest: %s\n' "$cert_digest"
    else
      printf 'V3.0 Signer: certificate SHA-256 digest: %s\n' "$cert_digest"
    fi
    if [[ "${FAKE_SECOND_SIGNER:-false}" == true ]]; then
      printf 'Signer #2 certificate SHA-256 digest: aabbccddaabbccddaabbccddaabbccddaabbccddaabbccddaabbccddaabbccdd\n'
    fi
    ;;
  *) exit 64 ;;
esac
FAKE_APKSIGNER
chmod +x "$android_home/build-tools/1/apksigner"

make_candidates "$candidates"
export ANDROID_HOME="$android_home"
export PR_PREVIEW_KEYSTORE_PASSWORD=test
export PR_PREVIEW_KEY_ALIAS=test
export PR_PREVIEW_KEY_PASSWORD=test
expected_cert_digest=aabbccddaabbccddaabbccddaabbccddaabbccddaabbccddaabbccddaabbccdd
export PR_PREVIEW_CERT_SHA256=$expected_cert_digest
"$signer" "$candidates" "$tmp/signed" "$tmp/test.p12"
"$stager" "$tmp/signed" "$candidates" "$tmp/signed-check"
for channel in stable isolated; do
  checksum_path=$(awk '{print $2}' "$tmp/signed-check/$channel/SHA256SUMS")
  [[ "$checksum_path" == "whitenoise-pr-${PR_NUMBER}-${channel}.apk" ]]
done
PATH="$fake_bin:$PATH" "$verifier" "$tmp/signed-check" >/dev/null
rm -rf "$tmp/signed-check"
rm -rf "$tmp/signed"

FAKE_LEGACY_CERT_OUTPUT=true "$signer" "$candidates" "$tmp/signed" "$tmp/test.p12"
rm -rf "$tmp/signed"

keytool_fingerprint=$(printf '%s' "$expected_cert_digest" |
  sed -E 's/(..)/\1:/g; s/:$//; y/abcdef/ABCDEF/')
export PR_PREVIEW_CERT_SHA256="SHA256: $keytool_fingerprint"
"$signer" "$candidates" "$tmp/signed" "$tmp/test.p12"
rm -rf "$tmp/signed"

export PR_PREVIEW_CERT_SHA256=$expected_cert_digest
wrong_cert_digest=deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef
expect_rejection 'wrong signing certificate' env FAKE_CERT_DIGEST="$wrong_cert_digest" "$signer" "$candidates" "$tmp/signed" "$tmp/test.p12"
rm -rf "$tmp/signed"
expect_rejection 'multiple signing certificates' env FAKE_SECOND_SIGNER=true "$signer" "$candidates" "$tmp/signed" "$tmp/test.p12"
rm -rf "$tmp/signed"
expect_rejection 'malformed expected fingerprint' env PR_PREVIEW_CERT_SHA256=not-a-fingerprint "$signer" "$candidates" "$tmp/signed" "$tmp/test.p12"

printf 'preview validation fixtures: passed\n'
