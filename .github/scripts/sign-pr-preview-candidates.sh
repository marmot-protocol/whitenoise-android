#!/usr/bin/env bash
set -euo pipefail

candidate_root=${1:?candidate root is required}
output_root=${2:?output root is required}
keystore=${3:?keystore path is required}
: "${PR_PREVIEW_KEYSTORE_PASSWORD:?PR_PREVIEW_KEYSTORE_PASSWORD is required}"
: "${PR_PREVIEW_KEY_ALIAS:?PR_PREVIEW_KEY_ALIAS is required}"
: "${PR_PREVIEW_KEY_PASSWORD:?PR_PREVIEW_KEY_PASSWORD is required}"
: "${PR_PREVIEW_CERT_SHA256:?PR_PREVIEW_CERT_SHA256 is required}"

normalize_sha256_fingerprint() {
  local value=${1//$'\r'/}
  value=$(printf '%s' "$value" | tr '[:upper:]' '[:lower:]')
  value=$(printf '%s' "$value" |
    sed -E 's/^[[:space:]]*sha-?256([[:space:]]+fingerprint)?[[:space:]]*[:=][[:space:]]*//')
  printf '%s' "$value" | tr -d '[:space:]:'
}

apksigner=$(find "${ANDROID_HOME:?ANDROID_HOME is required}/build-tools" -type f -name apksigner -print | sort -V | tail -1)
test -x "$apksigner"

expected_digest=$(normalize_sha256_fingerprint "$PR_PREVIEW_CERT_SHA256")
if [[ ! "$expected_digest" =~ ^[[:xdigit:]]{64}$ ]]; then
  printf 'PR_PREVIEW_CERT_SHA256 must be a 64-digit SHA-256 fingerprint.\n' >&2
  exit 1
fi

for channel in stable isolated; do
  input=$(find "$candidate_root/$channel" -maxdepth 1 -type f -name '*.apk' -print -quit)
  mkdir -p "$output_root/$channel"
  output="$output_root/$channel/whitenoise-pr-${PR_NUMBER}-${channel}.apk"
  "$apksigner" sign \
    --ks "$keystore" \
    --ks-key-alias "$PR_PREVIEW_KEY_ALIAS" \
    --ks-pass env:PR_PREVIEW_KEYSTORE_PASSWORD \
    --key-pass env:PR_PREVIEW_KEY_PASSWORD \
    --out "$output" \
    "$input"
  verification=$("$apksigner" verify --verbose --print-certs --min-sdk-version 34 "$output")
  # Build Tools through 36.x label this "Signer #1 certificate...".
  # Build Tools 37.x labels it "V3.0 Signer: certificate...". Keep the
  # security check independent of the scheme/version prefix.
  cert_digests=$(printf '%s\n' "$verification" |
    sed -nE 's/^.*Signer[^:]*:? certificate SHA-256 digest: //p')
  cert_count=$(printf '%s\n' "$cert_digests" |
    awk 'NF { count++ } END { print count + 0 }')
  if (( cert_count != 1 )); then
    printf 'Expected exactly one signing certificate for %s, found %d.\n' \
      "$channel" "$cert_count" >&2
    exit 1
  fi
  cert_digest=$(normalize_sha256_fingerprint "$cert_digests")
  if [[ "$cert_digest" != "$expected_digest" ]]; then
    printf 'The %s APK signing certificate does not match PR_PREVIEW_CERT_SHA256.\n' \
      "$channel" >&2
    exit 1
  fi
done
