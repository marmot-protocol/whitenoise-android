#!/usr/bin/env bash
set -euo pipefail

apk_path="${1:?usage: bash scripts/verify-baseline-profile.sh <apk-path>}"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required host command: $1" >&2
    exit 1
  fi
}

if [[ ! -f "$apk_path" ]]; then
  echo "APK not found: $apk_path" >&2
  exit 1
fi

bash "$script_dir/verify-generated-profiles.sh" "$script_dir/../app/src/main/generated/baselineProfiles"

if command -v apkanalyzer >/dev/null 2>&1; then
  profile_entries="$(apkanalyzer files list "$apk_path")"
else
  require_command unzip
  profile_entries="$(unzip -Z1 "$apk_path")"
fi

for required_entry in assets/dexopt/baseline.prof assets/dexopt/baseline.profm; do
  if ! grep -Fxq "$required_entry" <<<"$profile_entries"; then
    echo "Missing compiled Baseline Profile asset: $required_entry" >&2
    exit 1
  fi
done

echo "Verified Baseline Profile assets in $apk_path"
