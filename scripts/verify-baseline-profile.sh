#!/usr/bin/env bash
set -euo pipefail

apk_path="${1:?usage: bash scripts/verify-baseline-profile.sh <apk-path> [merged-art-profile]}"
merged_profile="${2:-}"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
generated_dir="${GENERATED_PROFILE_DIR:-$script_dir/../app/src/main/generated/baselineProfiles}"

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

bash "$script_dir/verify-generated-profiles.sh" "$generated_dir"

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
# The merged text profile shows whether the app's rules actually went in: every
# rule identity of the committed Baseline Profile must be present, whatever its
# flags, so a stale, truncated or stub profile fails as well as a missing one.
if [[ -n "$merged_profile" ]]; then
  if [[ ! -f "$merged_profile" ]]; then
    echo "Merged ART profile not found: $merged_profile" >&2
    exit 1
  fi
  missing="$(awk '
    { sub(/^[HSP]+/, "") }
    NR == FNR { if (length($0)) merged[$0] = 1; next }
    length($0) && !($0 in merged) { count++; if (!first) first = $0 }
    END { if (count) print count " rules missing, first: " first }
  ' "$merged_profile" "$generated_dir/baseline-prof.txt")"
  if [[ -n "$missing" ]]; then
    echo "Merged ART profile lacks the committed Baseline Profile ($missing): $merged_profile" >&2
    exit 1
  fi
  echo "Verified every committed Baseline Profile rule in $merged_profile"
fi

echo "Verified Baseline Profile assets in $apk_path"
