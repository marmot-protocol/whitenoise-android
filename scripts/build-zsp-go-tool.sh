#!/usr/bin/env bash
# Build one of our Go release tools against the pinned ZSP source module, so it
# uses the same checksummed go-nostr version as the ZSP binary we run.
# Usage: build-zsp-go-tool.sh <tool.go> <output> [go build tags]
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=scripts/release-properties.sh
source "$repo_dir/scripts/release-properties.sh"
tool="$(cd "$(dirname "${1:?Pass the Go tool source}")" && pwd)/$(basename "$1")"
output="${2:?Pass the output path}"
tags="${3:-}"
[[ "$(release_property ZSP_VERSION)" == 0.4.17 ]] || { echo 'Update the Go tool source pin with the ZSP binary pin' >&2; exit 1; }
[[ -z "$tags" || "$tags" =~ ^[a-z,]+$ ]] || { echo 'Invalid build tags' >&2; exit 1; }
mkdir -p "$(dirname "$output")"
output="$(cd "$(dirname "$output")" && pwd)/$(basename "$output")"
temporary_dir="$(mktemp -d)"
trap 'rm -rf "$temporary_dir"' EXIT
# The exact commit behind ZSP v0.4.17.
git -C "$temporary_dir" init -q source
git -C "$temporary_dir/source" fetch -q --depth 1 https://github.com/zapstore/zsp.git c50c1ccbf32d7fa6ae00f74990ce2c83f3bac6a1
git -C "$temporary_dir/source" checkout -q --detach FETCH_HEAD
(
  cd "$temporary_dir/source"
  GOWORK=off go build ${tags:+-tags "$tags"} -o "$output" "$tool"
)
