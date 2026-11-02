#!/usr/bin/env bash
set -euo pipefail

profile_dir="${1:-app/src/main/generated/baselineProfiles}"
baseline_profile="$profile_dir/baseline-prof.txt"
startup_profile="$profile_dir/startup-prof.txt"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

for profile in "$baseline_profile" "$startup_profile"; do
  if [[ ! -s "$profile" ]]; then
    echo "Generated profile is missing or empty: $profile" >&2
    exit 1
  fi
done

if cmp -s "$baseline_profile" "$startup_profile"; then
  echo "Startup Profile must not duplicate the broader Baseline Profile." >&2
  exit 1
fi

baseline_rules="$(awk 'END { print NR }' "$baseline_profile")"
startup_rules="$(awk 'END { print NR }' "$startup_profile")"

# H (hot), S (startup), and P (post-startup) are compilation flags rather
# than part of a class or method identity. Compare identities without those
# prefixes so a startup PL rule is correctly contained by a baseline SPL rule.
sed -E 's/^[HSP]+//' "$startup_profile" | LC_ALL=C sort -u >"$tmp_dir/startup-identities"
sed -E 's/^[HSP]+//' "$baseline_profile" | LC_ALL=C sort -u >"$tmp_dir/baseline-identities"
startup_identities="$(awk 'END { print NR }' "$tmp_dir/startup-identities")"
baseline_identities="$(awk 'END { print NR }' "$tmp_dir/baseline-identities")"
comm -23 "$tmp_dir/startup-identities" "$tmp_dir/baseline-identities" >"$tmp_dir/missing-identities"

if [[ -s "$tmp_dir/missing-identities" ]]; then
  missing_count="$(awk 'END { print NR }' "$tmp_dir/missing-identities")"
  echo "Startup Profile contains $missing_count rule identities absent from the Baseline Profile:" >&2
  sed -n '1,10p' "$tmp_dir/missing-identities" >&2
  if ((missing_count > 10)); then
    echo "... and $((missing_count - 10)) more" >&2
  fi
  exit 1
fi

if ((startup_identities >= baseline_identities)); then
  echo "Startup Profile must remain narrower than the Baseline Profile ($startup_identities >= $baseline_identities identities)." >&2
  exit 1
fi

echo "Verified generated profile separation: $startup_rules startup rules ($startup_identities identities), $baseline_rules baseline rules ($baseline_identities identities)"
