#!/usr/bin/env bash
set -euo pipefail

apk_path="${1:?usage: bash scripts/verify-baseline-profile.sh <apk-path> [merged-art-profile]}"
merged_profile="${2:-}"
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
  # apkanalyzer lists entries from the archive root ("/assets/..."), unlike the ZIP table.
  profile_entries="$(apkanalyzer files list "$apk_path" | sed 's#^/##')"
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

# The compiled assets exist even when only library rules were merged, so a
# variant that silently drops the app's own profile passes the check above.
# The merged text profile shows whether the app's rules actually went in.
if [[ -n "$merged_profile" ]]; then
  app_rule='Ldev/ipf/whitenoise/android/MainActivity;'
  if [[ ! -f "$merged_profile" ]]; then
    echo "Merged ART profile not found: $merged_profile" >&2
    exit 1
  fi
  if ! grep -Fq "$app_rule" "$merged_profile"; then
    echo "Merged ART profile lacks the app's own rules ($app_rule): $merged_profile" >&2
    exit 1
  fi
  echo "Verified the app's rules in $merged_profile"
fi

echo "Verified Baseline Profile assets in $apk_path"
