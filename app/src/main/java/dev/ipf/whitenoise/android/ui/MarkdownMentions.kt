package dev.ipf.whitenoise.android.ui

import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi

/** Collects distinct profile mentions from only the renderer-visible inline tree. */
internal fun markdownInlineMentionBech32s(inlines: List<MarkdownInlineFfi>): Set<String> =
    mutableSetOf<String>()
        .also { collectMentionBech32s(inlines, it, depth = 0) }

/** Walks bounded inline containers without constructing any styled text. */
private fun collectMentionBech32s(
    inlines: List<MarkdownInlineFfi>,
    out: MutableSet<String>,
    depth: Int,
) {
    if (markdownInlineDepthExceeded(depth)) return
    markdownVisibleSiblings(inlines).forEach { inline ->
        when (inline) {
            is MarkdownInlineFfi.NostrMention -> out += inline.entity.bech32
            is MarkdownInlineFfi.Emph -> collectMentionBech32s(inline.children, out, depth + 1)
            is MarkdownInlineFfi.Strong -> collectMentionBech32s(inline.children, out, depth + 1)
            is MarkdownInlineFfi.Strikethrough -> collectMentionBech32s(inline.children, out, depth + 1)
            is MarkdownInlineFfi.Link -> collectMentionBech32s(inline.children, out, depth + 1)
            is MarkdownInlineFfi.Image -> collectMentionBech32s(inline.alt, out, depth + 1)
            else -> Unit
        }
    }
}

/** Shares block, list, and table-area limits with the visible text projection. */
private fun collectBlockMentionBech32s(
    blocks: List<MarkdownBlockFfi>,
    out: MutableSet<String>,
    depth: Int,
) {
    if (markdownDepthExceeded(depth)) return
    markdownVisibleSiblings(blocks).forEach { block ->
        when (block) {
            is MarkdownBlockFfi.Paragraph -> collectMentionBech32s(block.inlines, out, depth = 0)
            is MarkdownBlockFfi.Heading -> collectMentionBech32s(block.inlines, out, depth = 0)
            is MarkdownBlockFfi.BlockQuote -> collectBlockMentionBech32s(block.blocks, out, depth + 1)
            is MarkdownBlockFfi.ListBlock ->
                markdownVisibleSiblings(block.items).forEach { collectBlockMentionBech32s(it.blocks, out, depth + 1) }
            is MarkdownBlockFfi.Table -> {
                val visibleTable = markdownVisibleTable(block.header, block.rows)
                visibleTable.header.cells.forEach { cell -> collectMentionBech32s(cell.inlines, out, depth = 0) }
                visibleTable.rows.forEach { row ->
                    row.cells.forEach { cell -> collectMentionBech32s(cell.inlines, out, depth = 0) }
                }
            }
            else -> Unit
        }
    }
}

/** Collects each visible mention once before local name resolution. */
internal fun markdownDocumentMentionBech32s(document: MarkdownDocumentFfi): Set<String> =
    mutableSetOf<String>()
        .also { collectBlockMentionBech32s(document.blocks, it, depth = 0) }
