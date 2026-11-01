#!/usr/bin/env bash
# Public publication is isolated from every build/internal workflow.
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
exec python3 "$repo_dir/scripts/zapstore_publish.py" "$@"
