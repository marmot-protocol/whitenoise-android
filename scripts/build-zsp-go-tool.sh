#!/usr/bin/env bash
# Build one of our Go release tools with checksummed go-nostr 0.52.3 dependencies.
# Both ZSP 0.4.17 and 2.0.0 use this protocol version; the CLI pin is independent.
# Usage: build-zsp-go-tool.sh <tool.go> <output> [go build tags]
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=scripts/release-properties.sh
source "$repo_dir/scripts/release-properties.sh"
tool="$(cd "$(dirname "${1:?Pass the Go tool source}")" && pwd)/$(basename "$1")"
output="${2:?Pass the output path}"
tags="${3:-}"

[[ -z "$tags" || "$tags" =~ ^[a-z,]+$ ]] || { echo 'Invalid build tags' >&2; exit 1; }
mkdir -p "$(dirname "$output")"
output="$(cd "$(dirname "$output")" && pwd)/$(basename "$output")"
temporary_dir="$(mktemp -d)"
trap 'rm -rf "$temporary_dir"' EXIT
# Dependency-only source pin (ZSP v0.4.17); NOT the publishing binary.
# Reused by v2: go-nostr 0.52.3 and coder/websocket 1.8.12 are unchanged.
git -C "$temporary_dir" init -q source
git -C "$temporary_dir/source" fetch -q --depth 1 https://github.com/zapstore/zsp.git c50c1ccbf32d7fa6ae00f74990ce2c83f3bac6a1
git -C "$temporary_dir/source" checkout -q --detach FETCH_HEAD
(
  cd "$temporary_dir/source"
  GOWORK=off go build ${tags:+-tags "$tags"} -o "$output" "$tool"
)
