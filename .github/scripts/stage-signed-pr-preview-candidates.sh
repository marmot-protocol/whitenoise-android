#!/usr/bin/env bash
set -euo pipefail

signed_root=${1:?signed root is required}
candidate_root=${2:?candidate root is required}
output_root=${3:?output root is required}
: "${PR_NUMBER:?PR_NUMBER is required}"

for channel in stable isolated; do
  output_dir="$output_root/$channel"
  mkdir -p "$output_dir"
  cp "$signed_root/$channel/whitenoise-pr-${PR_NUMBER}-${channel}.apk" \
    "$output_dir/whitenoise-pr-${PR_NUMBER}-${channel}.apk"
  # The verifier changes into each channel directory before checking this file,
  # so checksum entries must be relative to that directory.
  (cd "$output_dir" && sha256sum *.apk > SHA256SUMS)
  cp "$candidate_root/$channel/provenance.env" "$output_dir/provenance.env"
done
