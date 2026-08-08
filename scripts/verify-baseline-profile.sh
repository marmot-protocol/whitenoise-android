#!/usr/bin/env bash
set -euo pipefail

apk_path="${1:?usage: bash scripts/verify-baseline-profile.sh <apk-path>}"

if [[ ! -f "$apk_path" ]]; then
  echo "APK not found: $apk_path" >&2
  exit 1
fi

if command -v apkanalyzer >/dev/null 2>&1; then
  profile_entries="$(apkanalyzer files list "$apk_path")"
else
  profile_entries="$(unzip -Z1 "$apk_path")"
fi

for required_entry in assets/dexopt/baseline.prof assets/dexopt/baseline.profm; do
  if ! rg -q "^${required_entry}$" <<<"$profile_entries"; then
    echo "Missing compiled Baseline Profile asset: $required_entry" >&2
    exit 1
  fi
done

echo "Verified Baseline Profile assets in $apk_path"
