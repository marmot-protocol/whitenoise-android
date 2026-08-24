#!/usr/bin/env bash
set -euo pipefail

candidate_root=${1:?candidate root is required}
output_root=${2:?output root is required}
keystore=${3:?keystore path is required}
: "${WHITENOISE_STAGING_KEYSTORE_PASSWORD:?WHITENOISE_STAGING_KEYSTORE_PASSWORD is required}"
: "${WHITENOISE_STAGING_KEY_ALIAS:?WHITENOISE_STAGING_KEY_ALIAS is required}"
: "${WHITENOISE_STAGING_KEY_PASSWORD:?WHITENOISE_STAGING_KEY_PASSWORD is required}"
expected_digest=6bd133d7b8f9feb99e06daec0f22aae597bbcf1bb6af5dee73ec21df94634dd2

normalize_sha256_fingerprint() {
  local value=${1//$'\r'/}
  value=$(printf '%s' "$value" | tr '[:upper:]' '[:lower:]')
  value=$(printf '%s' "$value" |
    sed -E 's/^[[:space:]]*sha-?256([[:space:]]+fingerprint)?[[:space:]]*[:=][[:space:]]*//')
  printf '%s' "$value" | tr -d '[:space:]:'
}

apksigner=$(find "${ANDROID_HOME:?ANDROID_HOME is required}/build-tools" -type f -name apksigner -print | sort -V | tail -1)
test -x "$apksigner"

for channel in regular; do
  input=$(find "$candidate_root/$channel" -maxdepth 1 -type f -name '*.apk' -print -quit)
  mkdir -p "$output_root/$channel"
  output="$output_root/$channel/whitenoise-pr-${PR_NUMBER}-${channel}.apk"
  "$apksigner" sign \
    --ks "$keystore" \
    --ks-key-alias "$WHITENOISE_STAGING_KEY_ALIAS" \
    --ks-pass env:WHITENOISE_STAGING_KEYSTORE_PASSWORD \
    --key-pass env:WHITENOISE_STAGING_KEY_PASSWORD \
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
    printf 'The %s APK signing certificate does not match the regular staging app.\n' \
      "$channel" >&2
    exit 1
  fi
done
