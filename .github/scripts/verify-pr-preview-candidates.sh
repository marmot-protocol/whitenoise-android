#!/usr/bin/env bash
set -euo pipefail

root=${1:?candidate root is required}
: "${PR_NUMBER:?PR_NUMBER is required}"
: "${HEAD_SHA:?HEAD_SHA is required}"
: "${BUILD_RUN_NUMBER:?BUILD_RUN_NUMBER is required}"

expected_version=2000000000

for channel in stable isolated; do
  dir="$root/$channel"
  test -d "$dir"
  (cd "$dir" && sha256sum --check SHA256SUMS)

  # provenance.env is data produced by untrusted code: compare exact expected
  # lines instead of sourcing it.
  grep -Fx "pr_number=$PR_NUMBER" "$dir/provenance.env"
  grep -Fx "channel=$channel" "$dir/provenance.env"
  grep -Fx "head_sha=$HEAD_SHA" "$dir/provenance.env"
  grep -Fx "run_number=$BUILD_RUN_NUMBER" "$dir/provenance.env"

  mapfile -t apks < <(find "$dir" -maxdepth 1 -type f -name '*.apk' -print)
  if (( ${#apks[@]} != 1 )); then
    printf 'Expected exactly one %s candidate, found %d\n' "$channel" "${#apks[@]}" >&2
    exit 1
  fi

  if [[ "$channel" == stable ]]; then
    expected_package=dev.ipf.whitenoise.android.preview
  else
    expected_package="dev.ipf.whitenoise.android.preview.pr${PR_NUMBER}"
  fi

  actual_package=$(apkanalyzer manifest application-id "${apks[0]}")
  actual_version=$(apkanalyzer manifest version-code "${apks[0]}")
  [[ "$actual_package" == "$expected_package" ]]
  [[ "$actual_version" == "$expected_version" ]]
done
