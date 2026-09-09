#!/usr/bin/env bash
# All release entry points use the same Python parser, including whitespace and
# duplicate-key validation.
release_property() {
  local property_repo_dir
  property_repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
  python3 "$property_repo_dir/scripts/release_bundle.py" property "$1"
}
