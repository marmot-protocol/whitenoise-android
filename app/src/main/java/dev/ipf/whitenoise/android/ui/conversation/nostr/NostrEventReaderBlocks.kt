package dev.ipf.whitenoise.android.ui.conversation.nostr

import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.whitenoise.android.ui.MARKDOWN_MAX_BLOCK_DEPTH
import dev.ipf.whitenoise.android.ui.MARKDOWN_MAX_CONTAINER_SIBLINGS
import dev.ipf.whitenoise.android.ui.MARKDOWN_MAX_INLINE_DEPTH
import dev.ipf.whitenoise.android.ui.MARKDOWN_MAX_TABLE_CELLS
import dev.ipf.whitenoise.android.ui.MARKDOWN_MAX_TABLE_COLUMNS
import dev.ipf.whitenoise.android.ui.markdownDocumentToPreviewText

/** Retains the native parsed document while bounding the text measured by each lazy item. */
internal fun nostrReaderBlocks(document: MarkdownDocumentFfi): List<MarkdownBlockFfi> =
    document.blocks.flatMap { block ->
        when (block) {
            is MarkdownBlockFfi.Paragraph -> readerInlineGroups(block.inlines).map { block.copy(inlines = it) }
            is MarkdownBlockFfi.Heading -> readerInlineGroups(block.inlines).map { block.copy(inlines = it) }
            is MarkdownBlockFfi.CodeBlock -> nostrReaderTextChunks(block.content).map { block.copy(content = it) }
            is MarkdownBlockFfi.MathBlock -> nostrReaderTextChunks(block.content).map { block.copy(content = it) }
            else -> listOf(block)
        }
    }

/** Splits text at word/line boundaries where possible without losing whitespace or surrogate pairs. */
internal fun nostrReaderTextChunks(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        var end = minOf(start + READER_TEXT_BUDGET, text.length)
        if (end < text.length) {
            val boundary = maxOf(text.lastIndexOf('\n', end - 1), text.lastIndexOf(' ', end - 1))
            if (boundary >= start + READER_TEXT_BUDGET / 2) end = boundary + 1
            if (Character.isLowSurrogate(text[end]) && Character.isHighSurrogate(text[end - 1])) end--
        }
        chunks += text.substring(start, end)
        start = end
    }
    return chunks
}

private fun readerInlineGroups(inlines: List<MarkdownInlineFfi>): List<List<MarkdownInlineFfi>> {
    val groups = mutableListOf<List<MarkdownInlineFfi>>()
    val group = mutableListOf<MarkdownInlineFfi>()
    var size = 0
    inlines.flatMap { readerInlineFragments(it, 0) }.forEach { inline ->
        val nextSize = inlineTextSize(inline, 0)
        if (group.isNotEmpty() && (size + nextSize > READER_TEXT_BUDGET || group.size >= READER_INLINE_BUDGET)) {
            groups += group.toList()
            group.clear()
            size = 0
        }
        group += inline
        size += nextSize
    }
    if (group.isNotEmpty()) groups += group
    return groups
}

/** Wrapper copies retain native styling and the original link destination; Nostr entities remain atomic. */
private fun readerInlineFragments(
    inline: MarkdownInlineFfi,
    depth: Int,
): List<MarkdownInlineFfi> {
    if (depth >= READER_SPLIT_DEPTH_LIMIT) return listOf(inline)

    fun children(values: List<MarkdownInlineFfi>) = values.flatMap { readerInlineFragments(it, depth + 1) }
    return when (inline) {
        is MarkdownInlineFfi.Text -> nostrReaderTextChunks(inline.content).map { inline.copy(content = it) }
        is MarkdownInlineFfi.Code -> nostrReaderTextChunks(inline.content).map { inline.copy(content = it) }
        is MarkdownInlineFfi.Math -> nostrReaderTextChunks(inline.content).map { inline.copy(content = it) }
        is MarkdownInlineFfi.Emph -> children(inline.children).map { inline.copy(children = listOf(it)) }
        is MarkdownInlineFfi.Strong -> children(inline.children).map { inline.copy(children = listOf(it)) }
        is MarkdownInlineFfi.Strikethrough -> children(inline.children).map { inline.copy(children = listOf(it)) }
        is MarkdownInlineFfi.Link -> children(inline.children).map { inline.copy(children = listOf(it)) }
        else -> listOf(inline)
    }
}

private fun inlineTextSize(
    inline: MarkdownInlineFfi,
    depth: Int,
): Int {
    if (depth >= READER_SPLIT_DEPTH_LIMIT) return 1

    fun size(children: List<MarkdownInlineFfi>) = children.sumOf { inlineTextSize(it, depth + 1) }
    return when (inline) {
        is MarkdownInlineFfi.Text -> inline.content.length
        is MarkdownInlineFfi.Code -> inline.content.length
        is MarkdownInlineFfi.Math -> inline.content.length
        is MarkdownInlineFfi.Emph -> size(inline.children)
        is MarkdownInlineFfi.Strong -> size(inline.children)
        is MarkdownInlineFfi.Strikethrough -> size(inline.children)
        is MarkdownInlineFfi.Link -> size(inline.children)
        else -> 1
    }
}

private const val READER_TEXT_BUDGET = 4096
private const val READER_INLINE_BUDGET = 128
private const val READER_SPLIT_DEPTH_LIMIT = 64

/** Fall back to complete plain text when a complex tree would exceed the shared renderer's safety windows. */
internal fun nostrReaderCanFormat(document: MarkdownDocumentFfi): Boolean {
    val complete = !document.truncated
    return complete && document.blocks.all { readerBlockFits(it, 0) }
}

private fun readerBlockFits(
    block: MarkdownBlockFfi,
    depth: Int,
): Boolean {
    if (depth >= MARKDOWN_MAX_BLOCK_DEPTH) return false
    return readerStructureFits(block, depth) && readerBlockBudgetFits(block, depth)
}

private fun readerStructureFits(
    block: MarkdownBlockFfi,
    depth: Int,
): Boolean {
    fun children(blocks: List<MarkdownBlockFfi>): Boolean {
        val bounded = blocks.size <= MARKDOWN_MAX_CONTAINER_SIBLINGS
        return bounded && blocks.all { readerBlockFits(it, depth + 1) }
    }

    fun inlines(values: List<MarkdownInlineFfi>) = values.all { readerInlineFits(it, 0) }
    return when (block) {
        is MarkdownBlockFfi.Paragraph -> inlines(block.inlines)
        is MarkdownBlockFfi.Heading -> inlines(block.inlines)
        is MarkdownBlockFfi.BlockQuote -> children(block.blocks)
        is MarkdownBlockFfi.Details -> inlines(block.summary) && children(block.body)
        is MarkdownBlockFfi.ListBlock ->
            block.items.size <= MARKDOWN_MAX_CONTAINER_SIBLINGS && block.items.all { children(it.blocks) }
        is MarkdownBlockFfi.Table -> readerTableFits(block)
        else -> true
    }
}

private fun readerTableFits(block: MarkdownBlockFfi.Table): Boolean =
    block.header.size <= MARKDOWN_MAX_TABLE_COLUMNS &&
        block.rows.all { it.size <= MARKDOWN_MAX_TABLE_COLUMNS } &&
        block.header.size + block.rows.sumOf { it.size } <= MARKDOWN_MAX_TABLE_CELLS &&
        (block.header + block.rows.flatten()).all { cell -> cell.inlines.all { readerInlineFits(it, 0) } }

private fun readerBlockBudgetFits(
    block: MarkdownBlockFfi,
    depth: Int,
): Boolean {
    val complex =
        block is MarkdownBlockFfi.BlockQuote ||
            block is MarkdownBlockFfi.Details ||
            block is MarkdownBlockFfi.ListBlock ||
            block is MarkdownBlockFfi.Table
    if (!complex && depth == 0) return true
    val preview =
        markdownDocumentToPreviewText(
            MarkdownDocumentFfi(listOf(block), false, byteArrayOf()),
            READER_TEXT_BUDGET + 1,
        )
    return preview.length <= READER_TEXT_BUDGET
}

private fun readerInlineFits(
    inline: MarkdownInlineFfi,
    depth: Int,
): Boolean {
    if (depth >= MARKDOWN_MAX_INLINE_DEPTH) return false
    val children =
        when (inline) {
            is MarkdownInlineFfi.Emph -> inline.children
            is MarkdownInlineFfi.Strong -> inline.children
            is MarkdownInlineFfi.Strikethrough -> inline.children
            is MarkdownInlineFfi.Link -> inline.children
            is MarkdownInlineFfi.Image -> inline.alt
            else -> emptyList()
        }
    return children.size <= MARKDOWN_MAX_CONTAINER_SIBLINGS && children.all { readerInlineFits(it, depth + 1) }
}
