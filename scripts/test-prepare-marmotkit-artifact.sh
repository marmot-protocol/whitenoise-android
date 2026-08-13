#!/usr/bin/env bash

set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_dir"
python3 -m unittest scripts/test_prepare_marmotkit_artifact.py
