#!/usr/bin/env bash

set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
release_config="$repo_dir/config/android-release.properties"
install_target="${1:-${GOBIN:-$HOME/go/bin}/zsp}"

release_property() {
  local property_name="$1"
  awk -F= -v requested="$property_name" '$1 == requested { print substr($0, index($0, "=") + 1); exit }' "$release_config"
}

version="$(release_property ZSP_VERSION)"
os_name="$(uname -s)"
architecture="$(uname -m)"

case "$os_name/$architecture" in
  Darwin/arm64)
    asset="zsp-$version-darwin-arm64"
    expected_sha="$(release_property ZSP_DARWIN_ARM64_SHA256)"
    ;;
  Linux/x86_64|Linux/amd64)
    asset="zsp-$version-linux-amd64"
    expected_sha="$(release_property ZSP_LINUX_AMD64_SHA256)"
    ;;
  Linux/aarch64|Linux/arm64)
    asset="zsp-$version-linux-arm64"
    expected_sha="$(release_property ZSP_LINUX_ARM64_SHA256)"
    ;;
  *)
    echo "error: no pinned ZSP binary for $os_name/$architecture" >&2
    exit 1
    ;;
esac

temporary_dir="$(mktemp -d)"
trap 'rm -rf "$temporary_dir"' EXIT
downloaded_binary="$temporary_dir/zsp"
download_url="https://github.com/zapstore/zsp/releases/download/v$version/$asset"

curl --fail --location --silent --show-error "$download_url" --output "$downloaded_binary"
actual_sha="$(shasum -a 256 "$downloaded_binary" | awk '{print $1}')"
if [[ "$actual_sha" != "$expected_sha" ]]; then
  echo "error: ZSP checksum mismatch for $asset" >&2
  echo "expected: $expected_sha" >&2
  echo "actual:   $actual_sha" >&2
  exit 1
fi

mkdir -p "$(dirname "$install_target")"
install -m 0755 "$downloaded_binary" "$install_target"
installed_version="$(
  "$install_target" --version \
    | sed -n 's/^v\([0-9][0-9.]*\)$/\1/p' \
    | tail -1
)"
if [[ "$installed_version" != "$version" ]]; then
  echo "error: installed ZSP reported $installed_version, expected $version" >&2
  exit 1
fi

echo "Installed zsp v$version at $install_target"
