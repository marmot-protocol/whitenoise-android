#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
verify_script="$repo_root/scripts/verify-generated-profiles.sh"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

write_profile() {
  local fixture="$1"
  local name="$2"
  local contents="$3"
  mkdir -p "$tmp_dir/$fixture"
  printf '%s\n' "$contents" >"$tmp_dir/$fixture/$name-prof.txt"
}

expect_success() {
  local fixture="$1"
  if ! bash "$verify_script" "$tmp_dir/$fixture" >/dev/null; then
    echo "Expected profile fixture to pass: $fixture" >&2
    exit 1
  fi
}

expect_failure() {
  local fixture="$1"
  if bash "$verify_script" "$tmp_dir/$fixture" >/dev/null 2>&1; then
    echo "Expected profile fixture to fail: $fixture" >&2
    exit 1
  fi
}

# Compilation flags may differ while the underlying rule identity is shared.
write_profile valid baseline $'Lexample/App;\nSPLexample/App;->launch()V\nHLexample/App;->group()V'
write_profile valid startup $'Lexample/App;\nPLexample/App;->launch()V'
expect_success valid

# A smaller but unrelated Startup Profile must not pass merely on line count.
write_profile unrelated baseline $'Lexample/App;\nSPLexample/App;->launch()V\nHLexample/App;->group()V'
write_profile unrelated startup 'Lunrelated/Corrupt;'
expect_failure unrelated

write_profile identical baseline $'Lexample/App;\nSPLexample/App;->launch()V'
write_profile identical startup $'Lexample/App;\nSPLexample/App;->launch()V'
expect_failure identical

write_profile not-narrower baseline $'Lexample/App;\nSPLexample/App;->launch()V'
write_profile not-narrower startup $'Lexample/App;\nPLexample/App;->launch()V'
expect_failure not-narrower

# Extra flag variants must not make an identity-equivalent Baseline Profile
# appear broader merely because it contains more physical rules.
write_profile duplicate-flags-only baseline $'Lexample/App;\nHLexample/App;->launch()V\nSPLexample/App;->launch()V'
write_profile duplicate-flags-only startup $'Lexample/App;\nPLexample/App;->launch()V'
expect_failure duplicate-flags-only

echo "Generated profile verifier tests passed"
