package dev.ipf.whitenoise.android.ui

import dev.ipf.marmotkit.MarkdownListKindFfi

internal fun markdownListMarker(
    kind: MarkdownListKindFfi,
    index: Int,
): String =
    when (kind) {
        is MarkdownListKindFfi.Bullet -> "•"
        // Compute in Long: UInt addition wraps silently, and `start` is an
        // FFI-supplied value we don't control.
        is MarkdownListKindFfi.Ordered -> "${kind.start.toLong() + index}${kind.delimiter}"
    }
