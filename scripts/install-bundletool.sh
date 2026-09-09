#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=scripts/release-properties.sh
source "$repo_dir/scripts/release-properties.sh"
version="$(release_property BUNDLETOOL_VERSION)"
expected_sha="$(release_property BUNDLETOOL_SHA256)"
target="${1:-$repo_dir/build/tools/bundletool.jar}"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ && "$expected_sha" =~ ^[0-9a-f]{64}$ ]]
if [[ -f "$target" && "$(shasum -a 256 "$target" | awk '{print $1}')" == "$expected_sha" ]]; then
  exit 0
fi
temporary_dir="$(mktemp -d)"
trap 'rm -rf "$temporary_dir"' EXIT
curl --fail --location --silent --show-error \
  "https://github.com/google/bundletool/releases/download/$version/bundletool-all-$version.jar" \
  --output "$temporary_dir/bundletool.jar"
[[ "$(shasum -a 256 "$temporary_dir/bundletool.jar" | awk '{print $1}')" == "$expected_sha" ]] || {
  echo 'error: bundletool checksum mismatch' >&2
  exit 1
}
mkdir -p "$(dirname "$target")"
install -m 0644 "$temporary_dir/bundletool.jar" "$target"
