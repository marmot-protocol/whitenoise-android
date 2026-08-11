#!/usr/bin/env bash
set -euo pipefail

candidate_root=${1:?candidate root is required}
output_root=${2:?output root is required}
keystore=${3:?keystore path is required}
: "${PR_PREVIEW_KEYSTORE_PASSWORD:?PR_PREVIEW_KEYSTORE_PASSWORD is required}"
: "${PR_PREVIEW_KEY_ALIAS:?PR_PREVIEW_KEY_ALIAS is required}"
: "${PR_PREVIEW_KEY_PASSWORD:?PR_PREVIEW_KEY_PASSWORD is required}"
: "${PR_PREVIEW_CERT_SHA256:?PR_PREVIEW_CERT_SHA256 is required}"

apksigner=$(find "${ANDROID_HOME:?ANDROID_HOME is required}/build-tools" -type f -name apksigner -print | sort -V | tail -1)
test -x "$apksigner"

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
  mapfile -t cert_digests < <(
    printf '%s\n' "$verification" |
      sed -nE 's/^Signer #[0-9]+ certificate SHA-256 digest: //p'
  )
  (( ${#cert_digests[@]} == 1 ))
  cert_digest=${cert_digests[0]}
  expected_digest=$(printf '%s' "$PR_PREVIEW_CERT_SHA256" | tr -d ':' | tr '[:upper:]' '[:lower:]')
  [[ "${cert_digest,,}" == "$expected_digest" ]]
done
