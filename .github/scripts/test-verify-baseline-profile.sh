#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
verify_script="$repo_root/scripts/verify-baseline-profile.sh"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

# A stand-in APK only needs the two compiled profile entries in its ZIP table.
python3 - "$tmp_dir/app.apk" <<'EOF'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "w") as apk:
    apk.writestr("assets/dexopt/baseline.prof", b"prof")
    apk.writestr("assets/dexopt/baseline.profm", b"profm")
EOF
printf '%s\n' 'Lexample/Library;' 'HSPLdev/ipf/whitenoise/android/MainActivity;->onCreate(Landroid/os/Bundle;)V' \
  'Ldev/ipf/whitenoise/android/MainActivity;' >"$tmp_dir/with-app.txt"
printf '%s\n' 'Lexample/Library;' 'HSPLexample/Library;->load()V' >"$tmp_dir/library-only.txt"

expect_success() {
  if ! bash "$verify_script" "$@" >/dev/null; then
    echo "Expected verifier to pass: $*" >&2
    exit 1
  fi
}

expect_failure() {
  if bash "$verify_script" "$@" >/dev/null 2>&1; then
    echo "Expected verifier to fail: $*" >&2
    exit 1
  fi
}

expect_success "$tmp_dir/app.apk"
expect_success "$tmp_dir/app.apk" "$tmp_dir/with-app.txt"
# Library rules alone still produce the compiled assets, so only the merged
# text profile shows that the app's own profile was left out.
expect_failure "$tmp_dir/app.apk" "$tmp_dir/library-only.txt"
expect_failure "$tmp_dir/app.apk" "$tmp_dir/missing.txt"

echo "Baseline Profile packaging verifier tests passed"
