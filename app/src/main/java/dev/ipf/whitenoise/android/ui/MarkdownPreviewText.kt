package dev.ipf.whitenoise.android.ui

import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi

/**
 * Chat-list previews cap the flattened string here: the row is one ellipsized
 * line, so anything past a couple hundred characters can never paint and
 * building it would only burn allocation on every list recomposition.
 */
internal const val MARKDOWN_PREVIEW_MAX_LENGTH = 200

/** Plain preview text shares the styled row's AST semantics without initializing Compose. */
internal fun markdownDocumentToPreviewText(
    document: MarkdownDocumentFfi,
    maxLength: Int = MARKDOWN_PREVIEW_MAX_LENGTH,
    mentionDisplayName: ((String) -> String?)? = null,
): String = markdownDocumentToPreviewProjection(document, maxLength, mentionDisplayName, captureStyles = false).text

/** Projects bounded visible text and, only when requested, lightweight style ranges. */
internal fun markdownDocumentToPreviewProjection(
    document: MarkdownDocumentFfi,
    maxLength: Int,
    mentionDisplayName: ((String) -> String?)?,
    captureStyles: Boolean,
): MarkdownPreviewProjection {
    val flattened =
        buildMarkdownPreviewText(captureStyles) {
            for (block in markdownVisibleSiblings(document.blocks)) {
                if (length >= maxLength) break
                appendPreviewBlock(block, MarkdownPreviewStyle.Code, maxLength, mentionDisplayName, depth = 0)
            }
        }
    return if (flattened.length > maxLength) flattened.previewSubSequence(maxLength) else flattened
}

/** Flattens visible block children under both the shared depth cap and the document text budget. */
private fun MarkdownPreviewBuilder.appendPreviewBlock(
    block: MarkdownBlockFfi,
    codeStyle: MarkdownPreviewStyle,
    maxLength: Int,
    mentionDisplayName: ((String) -> String?)?,
    depth: Int,
) {
    // Budget check inside the recursion too: the top-level loop only guards
    // between siblings, so a deep quote/list subtree would otherwise keep
    // flattening long after the row's budget is spent.
    // Structural depth cap: a deeply-nested subtree with NO text content never
    // spends the length budget, so the budget alone can't bound the recursion
    // — a peer could overflow the stack while building a one-line preview. See #156.
    if (length >= maxLength || markdownDepthExceeded(depth)) return
    when (block) {
        is MarkdownBlockFfi.Paragraph ->
            appendPreviewInlineSegment(block.inlines, codeStyle, maxLength, mentionDisplayName)
        is MarkdownBlockFfi.Heading ->
            appendPreviewInlineSegment(block.inlines, codeStyle, maxLength, mentionDisplayName)
        MarkdownBlockFfi.ThematicBreak -> Unit
        is MarkdownBlockFfi.CodeBlock -> appendPreviewCodeContent(block.content, codeStyle, maxLength)
        is MarkdownBlockFfi.MathBlock -> appendPreviewCodeContent(block.content, codeStyle, maxLength)
        is MarkdownBlockFfi.BlockQuote ->
            markdownVisibleSiblings(block.blocks).forEach {
                appendPreviewBlock(it, codeStyle, maxLength, mentionDisplayName, depth + 1)
            }
        is MarkdownBlockFfi.ListBlock ->
            markdownVisibleSiblings(block.items).forEach { item ->
                markdownVisibleSiblings(item.blocks).forEach {
                    appendPreviewBlock(it, codeStyle, maxLength, mentionDisplayName, depth + 1)
                }
            }
        is MarkdownBlockFfi.Table -> appendPreviewTable(block, codeStyle, maxLength, mentionDisplayName)
    }
}

private val previewWhitespaceRun = Regex("\\s+")

/** Takes a UTF-16 prefix without leaving a high surrogate at the truncation boundary. */
internal fun String.previewTake(maxLength: Int): String {
    val end = previewSafeEnd(maxLength)
    return if (end == length) this else substring(0, end)
}

/** Clips text and its style ranges together at a surrogate-safe prefix boundary. */
private fun MarkdownPreviewProjection.previewSubSequence(maxLength: Int): MarkdownPreviewProjection {
    val end = text.previewSafeEnd(maxLength)
    return subSequence(0, end)
}

/** Clamps a requested UTF-16 offset and backs off when truncation would split a surrogate pair. */
private fun String.previewSafeEnd(maxLength: Int): Int {
    val end = maxLength.coerceIn(0, length)
    return if (end > 0 && end < length && Character.isHighSurrogate(this[end - 1])) {
        end - 1
    } else {
        end
    }
}

/** Bounds raw code before sanitizing and collapsing whitespace into a single styled preview segment. */
private fun MarkdownPreviewBuilder.appendPreviewCodeContent(
    content: String,
    codeStyle: MarkdownPreviewStyle,
    maxLength: Int,
) {
    // Bound the work BEFORE the whitespace collapse: a megabyte code block
    // must not be regex-processed for a one-line row. The window is generous
    // because collapsing only shrinks text; a pathological mostly-whitespace
    // prefix just yields a shorter preview, which the row can afford.
    // Bound the RAW content BEFORE sanitizing, so stripUnsafe never scans a
    // peer-crafted megabyte block in full for a one-line row. Sanitizing only
    // shrinks, so the pre-clip window stays a safe upper bound (#1031 review).
    val bounded = markdownSafeDisplayText(content.previewTake(maxLength * 8), Int.MAX_VALUE)
    // A code block is a multi-line region; the preview is one line. Collapse
    // every whitespace run (incl. newlines and indentation) to a single space
    // so `fun main() {\n  hi()\n}` reads as `fun main() { hi() }`.
    val singleLine = bounded.trim().replace(previewWhitespaceRun, " ")
    appendPreviewSegment(
        buildMarkdownPreviewText(captureStyles) { withStyle(codeStyle) { append(singleLine) } },
        maxLength,
    )
}

/** Materializes bounded inline content separately so empty leaves cannot introduce block separators. */
private fun MarkdownPreviewBuilder.appendPreviewInlineSegment(
    inlines: List<MarkdownInlineFfi>,
    codeStyle: MarkdownPreviewStyle,
    maxLength: Int,
    mentionDisplayName: ((String) -> String?)?,
) {
    if (length >= maxLength) return
    appendPreviewSegment(
        buildMarkdownPreviewText(captureStyles) {
            appendPreviewInlines(inlines, codeStyle, maxLength, mentionDisplayName, depth = 0)
        },
        maxLength,
    )
}

/**
 * Joins a leaf segment to the builder with the single-space block separator,
 * spending at most the remaining [maxLength] budget. The segment is
 * materialized first so an empty contribution (blank paragraph, empty table
 * cell) commits neither text nor a stray separator; a segment that overflows
 * the budget is cut at the boundary instead of being appended whole.
 */
private fun MarkdownPreviewBuilder.appendPreviewSegment(
    segment: MarkdownPreviewProjection,
    maxLength: Int,
) {
    val separator = if (length > 0) 1 else 0
    val remaining = maxLength - length - separator
    if (segment.isEmpty() || remaining <= 0) return
    val chunk = if (segment.length > remaining) segment.previewSubSequence(remaining) else segment
    if (chunk.isEmpty()) return
    if (separator == 1) append(' ')
    append(chunk)
}

/** Visits the shared table window in row order under the remaining preview length. */
private fun MarkdownPreviewBuilder.appendPreviewTable(
    block: MarkdownBlockFfi.Table,
    codeStyle: MarkdownPreviewStyle,
    maxLength: Int,
    mentionDisplayName: ((String) -> String?)?,
) {
    val table = markdownVisibleTable(block.header, block.rows)
    for (row in listOf(table.header) + table.rows) {
        for (cell in row.cells) {
            if (length >= maxLength) return
            appendPreviewInlineSegment(cell.inlines, codeStyle, maxLength, mentionDisplayName)
        }
    }
}
