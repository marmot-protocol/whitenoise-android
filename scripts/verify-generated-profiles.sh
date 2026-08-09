#!/usr/bin/env bash
set -euo pipefail

profile_dir="${1:-app/src/main/generated/baselineProfiles}"
baseline_profile="$profile_dir/baseline-prof.txt"
startup_profile="$profile_dir/startup-prof.txt"

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

baseline_rules="$(wc -l <"$baseline_profile" | tr -d ' ')"
startup_rules="$(wc -l <"$startup_profile" | tr -d ' ')"
if ((startup_rules >= baseline_rules)); then
  echo "Startup Profile must remain narrower than the Baseline Profile ($startup_rules >= $baseline_rules rules)." >&2
  exit 1
fi

echo "Verified generated profile separation: $startup_rules startup rules, $baseline_rules baseline rules"
