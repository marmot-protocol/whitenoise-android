#!/usr/bin/env bash
# UniFFI emits MarkdownBlockFfi.List, which shadows kotlin.collections.List and
# breaks compilation. Rename the nested variant to ListBlock in the generated file.
set -euo pipefail

TARGET="${1:-app/src/main/java/dev/ipf/marmotkit/marmot_uniffi.kt}"
if [[ ! -f "$TARGET" ]]; then
  echo "error: bindings file not found: $TARGET" >&2
  exit 1
fi

perl -0pi -e '
  s/data class List\(/data class ListBlock(/g;
  s/MarkdownBlockFfi\.List(?![A-Za-z])/MarkdownBlockFfi.ListBlock/g;
  s/is MarkdownBlockFfi\.List(?![A-Za-z])/is MarkdownBlockFfi.ListBlock/g;
' "$TARGET"

echo "==> Patched MarkdownBlockFfi.List -> ListBlock in $TARGET"
