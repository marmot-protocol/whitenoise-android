#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

preview_number=42
common_args=(--no-daemon --stacktrace)
mapfile -t manifest_task_names < <(
  PR_NUMBER="$preview_number" PR_PREVIEW_CHANNEL=stable \
    ./gradlew :app:tasks --all --console=plain "${common_args[@]}" |
    python3 -c '
import re
import sys

for line in sys.stdin:
    task = line.strip()
    if re.fullmatch(r"process[A-Z]\w+ManifestForPackage", task):
        if "AndroidTest" not in task and "UnitTest" not in task:
            print(f":app:{task}")
'
)
if ((${#manifest_task_names[@]} == 0)); then
  echo "No packaged-manifest tasks found" >&2
  exit 1
fi

PR_NUMBER="$preview_number" PR_PREVIEW_CHANNEL=stable \
  ./gradlew "${manifest_task_names[@]}" "${common_args[@]}"
python3 scripts/verify-system-labels.py \
  --preview-channel stable \
  --preview-number "$preview_number"

PR_NUMBER="$preview_number" PR_PREVIEW_CHANNEL=isolated \
  ./gradlew \
    :app:processPreviewPlayReleaseManifestForPackage \
    :app:processPreviewZapstoreReleaseManifestForPackage \
    "${common_args[@]}"
python3 scripts/verify-system-labels.py \
  --preview-channel isolated \
  --preview-number "$preview_number"
