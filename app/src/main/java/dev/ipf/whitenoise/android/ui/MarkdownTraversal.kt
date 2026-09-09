package dev.ipf.whitenoise.android.ui

import dev.ipf.whitenoise.android.core.ProfileSanitizer

/**
 * Maximum block-nesting depth the renderer will descend before it stops
 * recursing. Block quotes and lists render their children via
 * [MarkdownBlockView] again, so a peer-crafted message with thousands of
 * nested quotes/lists would otherwise overflow the stack and crash the app on
 * open (a DoS — the body renders as soon as the conversation is shown). No
 * legitimate chat message nests anywhere near this deep. See #156.
 */
internal const val MARKDOWN_MAX_BLOCK_DEPTH = 24

/** Stops all block walkers at the renderer's shared structural-depth boundary. */
internal fun markdownDepthExceeded(depth: Int): Boolean = depth >= MARKDOWN_MAX_BLOCK_DEPTH

/**
 * Maximum number of Markdown siblings rendered or walked at any one untrusted
 * container boundary. Depth caps stop recursive stack DoS, but breadth DoS can
 * also hide under one top-level quote/list/table whose children are all depth 1.
 * 256 leaves ample room for legitimate chat formatting while bounding render,
 * mention, and preview work. See #942.
 */
internal const val MARKDOWN_MAX_CONTAINER_SIBLINGS = 256

/** Wide tables are unreadable in a chat bubble and expensive to lay out. */
internal const val MARKDOWN_MAX_TABLE_COLUMNS = 12

/** One table shares a single cell budget across its header and all body rows. */
internal const val MARKDOWN_MAX_TABLE_CELLS = MARKDOWN_MAX_CONTAINER_SIBLINGS

/** Applies the same bounded sibling window to rendering, previews, and mention lookup. */
internal fun <T> markdownVisibleSiblings(items: List<T>): List<T> =
    if (items.size <= MARKDOWN_MAX_CONTAINER_SIBLINGS) items else items.take(MARKDOWN_MAX_CONTAINER_SIBLINGS)

/** Reports whether a container has content beyond the visible safety window. */
internal fun markdownSiblingsElided(items: List<*>): Boolean = items.size > MARKDOWN_MAX_CONTAINER_SIBLINGS

internal data class MarkdownTableRowWindow<T>(
    val cells: List<T>,
    val cellsElided: Boolean,
)

internal data class MarkdownTableWindow<T>(
    val header: MarkdownTableRowWindow<T>,
    val rows: List<MarkdownTableRowWindow<T>>,
    val rowsElided: Boolean,
)

/** Applies one area budget to a table instead of independently capping both dimensions. */
internal fun <T> markdownVisibleTable(
    header: List<T>,
    rows: List<List<T>>,
): MarkdownTableWindow<T> {
    var remainingCells = MARKDOWN_MAX_TABLE_CELLS

    fun visibleRow(cells: List<T>): MarkdownTableRowWindow<T> {
        val visibleCount = minOf(cells.size, MARKDOWN_MAX_TABLE_COLUMNS, remainingCells)
        remainingCells -= visibleCount
        return MarkdownTableRowWindow(
            cells = cells.take(visibleCount),
            cellsElided = visibleCount < cells.size,
        )
    }

    val visibleHeader = visibleRow(header)
    val visibleRows = ArrayList<MarkdownTableRowWindow<T>>()
    val rowLimit = minOf(rows.size, MARKDOWN_MAX_CONTAINER_SIBLINGS)
    for (index in 0 until rowLimit) {
        if (remainingCells <= 0) break
        visibleRows += visibleRow(rows[index])
    }
    return MarkdownTableWindow(
        header = visibleHeader,
        rows = visibleRows,
        rowsElided = visibleRows.size < rows.size,
    )
}

/**
 * Maximum inline-nesting depth. Inline nodes (emphasis, strong, strikethrough,
 * link, image alt) carry child inlines, so the inline walkers recurse too — a
 * peer-crafted tree of repeated nested emphasis/links would overflow the stack
 * or burn CPU just like deep block nesting. Real formatting nests a handful of
 * levels (bold-italic-link); 64 is generous headroom. See #156.
 */
internal const val MARKDOWN_MAX_INLINE_DEPTH = 64

/** Stops inline recursion even when deeply nested empty nodes consume no text budget. */
internal fun markdownInlineDepthExceeded(depth: Int): Boolean = depth >= MARKDOWN_MAX_INLINE_DEPTH

internal const val MARKDOWN_LINK_CONFIRM_DISPLAY_MAX_LENGTH = 500

/** Sanitizes untrusted visible text and truncates by whole Unicode code points. */
internal fun markdownSafeDisplayText(
    value: String,
    maxLength: Int = MARKDOWN_LINK_CONFIRM_DISPLAY_MAX_LENGTH,
): String {
    val sanitized = ProfileSanitizer.stripUnsafe(value)
    if (sanitized.codePointCount(0, sanitized.length) <= maxLength) return sanitized
    val end = sanitized.offsetByCodePoints(0, maxLength)
    return sanitized.substring(0, end)
}

/**
 * `npub1qqqq…qqqq` style truncation for bech32 entities: first 12 + ellipsis
 * + last 6, leaving short strings untouched. 12 leading characters keep the
 * HRP plus a recognizable run of the body even for `nprofile1`.
 */
internal fun shortenedBech32(bech32: String): String {
    val trimmed = bech32.trim()
    if (trimmed.length <= BECH32_PREVIEW_PREFIX_LENGTH + 1 + BECH32_PREVIEW_SUFFIX_LENGTH) return trimmed
    return trimmed.take(BECH32_PREVIEW_PREFIX_LENGTH) + "…" + trimmed.takeLast(BECH32_PREVIEW_SUFFIX_LENGTH)
}

private const val BECH32_PREVIEW_PREFIX_LENGTH = 12
private const val BECH32_PREVIEW_SUFFIX_LENGTH = 6
