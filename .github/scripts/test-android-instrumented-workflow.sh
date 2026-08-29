#!/usr/bin/env bash
set -euo pipefail

workflow=.github/workflows/android-instrumented.yml
compatible_runner=e89f39f1abbbd05b1113a29cf4db69e7540cae5a

# This immutable upstream revision installs Android SDK Build Tools 36.0.0.
# Later v2 revisions install 37.0.0; keep this emulator gate on the reviewed
# SDK toolchain instead of silently changing it with the action release.
grep -Fq "uses: reactivecircus/android-emulator-runner@$compatible_runner # v2.37.0, Build Tools 36.0.0" "$workflow"

# A workflow-only pin update must exercise the emulator before it can merge.
grep -Fq -- "- '.github/workflows/android-instrumented.yml'" "$workflow"

# Keep the runner immutable; floating refs silently change its SDK installer.
if grep -Eq 'uses: reactivecircus/android-emulator-runner@(v[0-9]+|main|master)$' "$workflow"; then
  printf 'Android emulator runner must use the reviewed immutable SDK pin.\n' >&2
  exit 1
fi
