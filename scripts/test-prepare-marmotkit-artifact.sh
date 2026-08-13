#!/usr/bin/env bash

set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
python3 -m unittest "$repo_dir/scripts/test_prepare_marmotkit_artifact.py"
