#!/usr/bin/env bash
# Record the actual kernel page size before any release-verifier installation.
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo 'usage: verify-runtime-page-size.sh <required-bytes-or-empty> <receipt>' >&2
  exit 1
fi
expected="$1"
receipt="$2"
actual="$(adb shell getconf PAGE_SIZE | tr -d '\r')"
if [[ "$actual" != 4096 && "$actual" != 16384 ]]; then
  echo "error: unreadable or unsupported runtime page size: $actual" >&2
  exit 1
fi
if [[ -n "$expected" && "$expected" != "$actual" ]]; then
  echo "error: runtime page size is $actual bytes, required $expected" >&2
  exit 1
fi
mkdir -p "$(dirname "$receipt")"
printf '%s\n' "$actual" > "$receipt"
printf 'Runtime page size verified: %s bytes\n' "$actual"
