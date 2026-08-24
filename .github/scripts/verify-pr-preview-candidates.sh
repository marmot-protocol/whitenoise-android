#!/usr/bin/env bash
set -euo pipefail

root=${1:?candidate root is required}
: "${PR_NUMBER:?PR_NUMBER is required}"
: "${HEAD_SHA:?HEAD_SHA is required}"
: "${BUILD_RUN_NUMBER:?BUILD_RUN_NUMBER is required}"
: "${EXPECTED_VERSION_CODE:?EXPECTED_VERSION_CODE is required}"
[[ "$EXPECTED_VERSION_CODE" =~ ^[1-9][0-9]*$ ]]

expected_sha=${HEAD_SHA:0:7}
max_apk_bytes=67108847
max_expanded_bytes=536870912

for channel in regular; do
  dir="$root/$channel"
  test -d "$dir"
  (cd "$dir" && sha256sum --check SHA256SUMS)

  # provenance.env is data produced by untrusted code: compare exact expected
  # lines instead of sourcing it.
  grep -Fx "pr_number=$PR_NUMBER" "$dir/provenance.env"
  grep -Fx "channel=$channel" "$dir/provenance.env"
  grep -Fx "head_sha=$HEAD_SHA" "$dir/provenance.env"
  grep -Fx "run_number=$BUILD_RUN_NUMBER" "$dir/provenance.env"
  [[ $(wc -l < "$dir/provenance.env") -eq 4 ]]

  mapfile -t apks < <(find "$dir" -maxdepth 1 -type f -name '*.apk' -print)
  if (( ${#apks[@]} != 1 )); then
    printf 'Expected exactly one %s candidate, found %d\n' "$channel" "${#apks[@]}" >&2
    exit 1
  fi
  [[ $(stat -c '%s' "${apks[0]}") -le "$max_apk_bytes" ]]
  zipinfo -t "${apks[0]}" >/dev/null
  [[ $(zipinfo -1 "${apks[0]}" | wc -l) -le 50000 ]]
  expanded_bytes=$(zipinfo -l "${apks[0]}" | awk '$1 ~ /^[-dl]/ { total += $4 } END { print total + 0 }')
  [[ "$expanded_bytes" -le "$max_expanded_bytes" ]]

  expected_package=dev.ipf.whitenoise.android.staging

  actual_package=$(apkanalyzer manifest application-id "${apks[0]}")
  actual_version=$(apkanalyzer manifest version-code "${apks[0]}")
  actual_version_name=$(apkanalyzer manifest version-name "${apks[0]}")
  [[ "$actual_package" == "$expected_package" ]]
  [[ "$actual_version" == "$EXPECTED_VERSION_CODE" ]]
  [[ "$actual_version_name" == *"preview-pr${PR_NUMBER}-${expected_sha}"* ]]
  mapfile -t native_abis < <(zipinfo -1 "${apks[0]}" | sed -nE 's#^lib/([^/]+)/.+#\1#p' | sort -u)
  (( ${#native_abis[@]} == 1 ))
  [[ "${native_abis[0]}" == "arm64-v8a" ]]
done
