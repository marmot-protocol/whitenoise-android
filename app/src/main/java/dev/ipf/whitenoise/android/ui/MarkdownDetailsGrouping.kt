package dev.ipf.whitenoise.android.ui

import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import java.util.Locale

/** One recognized `<details>` disclosure: optional summary label + the collapsed inline content. */
internal data class MarkdownDetailsSection(
    val summary: String?,
    val content: List<MarkdownInlineFfi>,
)

// Opening line: bare `<details>`, optionally carrying the `<summary>…</summary>` on the same line.
private val DETAILS_OPEN_LINE = Regex("(?i)^<details>\\s*(?:<summary>(.*)</summary>)?$")

// A `<summary>…</summary>` line of its own directly after the opening tag.
private val DETAILS_SUMMARY_LINE = Regex("(?i)^<summary>(.*)</summary>$")

private const val DETAILS_CLOSE_TAG = "</details>"

private fun isMarkdownLineBreak(inline: MarkdownInlineFfi): Boolean =
    when (inline) {
        MarkdownInlineFfi.SoftBreak, MarkdownInlineFfi.HardBreak -> true
        else -> false
    }

/**
 * Detects a GitHub-style `<details>`/`<summary>` disclosure written as one
 * paragraph. The engine's markdown parser does not recognize HTML, so the
 * markup arrives as literal per-line [MarkdownInlineFfi.Text] runs separated
 * by soft breaks; this matches that shape: an opening `<details>` line
 * (optionally carrying the summary), an optional `<summary>…</summary>` line
 * of its own, the hidden inline content, and a closing `</details>` line.
 * Anything else — no closing tag, tags sharing a line with content — is not a
 * disclosure and renders as the literal text it is.
 *
 * Known limitation: a blank line, or a construct that interrupts a paragraph
 * (list, heading, fence), inside the markup splits it across blocks; cross-block
 * reassembly is not attempted, so those documents render literally too.
 */
internal fun markdownDetailsSection(inlines: List<MarkdownInlineFfi>): MarkdownDetailsSection? {
    val nodes = markdownVisibleSiblings(inlines)
    val openMatch =
        (nodes.firstOrNull() as? MarkdownInlineFfi.Text)
            ?.content
            ?.trim()
            ?.let { DETAILS_OPEN_LINE.matchEntire(it) }
    val closed =
        nodes.size > 1 &&
            (nodes.last() as? MarkdownInlineFfi.Text)?.content?.trim().equals(DETAILS_CLOSE_TAG, ignoreCase = true)
    if (openMatch == null || !closed) return null
    var summary = openMatch.groupValues[1].trim().takeIf { it.isNotEmpty() }
    var content = nodes.subList(1, nodes.size - 1).dropWhile(::isMarkdownLineBreak).dropLastWhile(::isMarkdownLineBreak)
    if (summary == null && openMatch.groups[1] == null) {
        val summaryLine =
            (content.firstOrNull() as? MarkdownInlineFfi.Text)
                ?.content
                ?.trim()
                ?.let { DETAILS_SUMMARY_LINE.matchEntire(it) }
        if (summaryLine != null) {
            summary = summaryLine.groupValues[1].trim().takeIf { it.isNotEmpty() }
            content = content.drop(1).dropWhile(::isMarkdownLineBreak)
        }
    }
    return MarkdownDetailsSection(summary, content)
}

/**
 * A message's blocks grouped so a `<details>` disclosure that spans several
 * blocks (the canonical GitHub form: a blank line after `</summary>`, then
 * lists/code/quotes, then `</details>`) collapses as one unit. The engine emits
 * no HTML block, so the tags arrive as literal-text paragraphs around ordinary
 * content blocks; this reassembles them.
 */
internal sealed interface MarkdownRenderGroup {
    data class Plain(
        val block: MarkdownBlockFfi,
    ) : MarkdownRenderGroup

    data class Details(
        val summary: String?,
        val content: List<MarkdownBlockFfi>,
    ) : MarkdownRenderGroup
}

private data class MarkdownDetailsOpen(
    val summary: String?,
)

/**
 * Render groups plus, for each one, the index of the source block it starts at.
 * A `<details>` group swallows a variable number of blocks, so group position
 * alone can't index per-block source metadata like `blankLinesBefore` (#1719).
 */
internal data class MarkdownGroupedBlocks(
    val groups: List<MarkdownRenderGroup>,
    val sourceIndices: List<Int>,
)

internal fun groupMarkdownDetailsBlocksWithSource(blocks: List<MarkdownBlockFfi>): MarkdownGroupedBlocks {
    val out = ArrayList<MarkdownRenderGroup>()
    val sources = ArrayList<Int>()
    var index = 0
    while (index < blocks.size) {
        val opener = markdownDetailsOpener(blocks[index])
        val closeIndex = if (opener == null) -1 else markdownDetailsCloseIndex(blocks, index + 1)
        sources += index
        if (opener == null || closeIndex < 0) {
            out += MarkdownRenderGroup.Plain(blocks[index])
            index += 1
        } else {
            val content = ArrayList<MarkdownBlockFfi>(blocks.subList(index + 1, closeIndex))
            stripTrailingDetailsClose(blocks[closeIndex])?.let { content += it }
            out += MarkdownRenderGroup.Details(opener.summary, content)
            index = closeIndex + 1
        }
    }
    return MarkdownGroupedBlocks(out, sources)
}

/**
 * Reassemble multi-block `<details>` groups. A self-contained single-paragraph
 * disclosure is left as a Plain block — [MarkdownBlockView] renders that case
 * inline via [markdownDetailsSection]. An opener with no matching close renders
 * literally (stays Plain), so unterminated markup never swallows the rest.
 */
@Suppress("MaxLineLength")
internal fun groupMarkdownDetailsBlocks(blocks: List<MarkdownBlockFfi>): List<MarkdownRenderGroup> = groupMarkdownDetailsBlocksWithSource(blocks).groups

// A Paragraph whose only lines are the <details> tag (optional same-line
// summary) and optionally an own-line <summary>. Residual content on the
// opening paragraph is not the canonical multi-block form and renders literally.
private fun markdownDetailsOpener(block: MarkdownBlockFfi): MarkdownDetailsOpen? {
    val paragraph =
        (block as? MarkdownBlockFfi.Paragraph)?.takeIf { markdownDetailsSection(it.inlines) == null } ?: return null
    return detailsOpenFromLines(markdownVisibleSiblings(paragraph.inlines).filterNot(::isMarkdownLineBreak))
}

private fun detailsOpenFromLines(lines: List<MarkdownInlineFfi>): MarkdownDetailsOpen? {
    val openMatch =
        (lines.firstOrNull() as? MarkdownInlineFfi.Text)?.content?.trim()?.let { DETAILS_OPEN_LINE.matchEntire(it) }
            ?: return null
    val sameLineSummary = openMatch.groupValues[1].trim().takeIf { it.isNotEmpty() }
    val ownLineSummary =
        (lines.getOrNull(1) as? MarkdownInlineFfi.Text)?.content?.trim()?.let { DETAILS_SUMMARY_LINE.matchEntire(it) }
    return when {
        sameLineSummary != null -> if (lines.size == 1) MarkdownDetailsOpen(sameLineSummary) else null
        lines.size == 1 -> MarkdownDetailsOpen(null)
        lines.size == 2 && ownLineSummary != null ->
            MarkdownDetailsOpen(ownLineSummary.groupValues[1].trim().takeIf { it.isNotEmpty() })
        else -> null
    }
}

private fun markdownDetailsCloseIndex(
    blocks: List<MarkdownBlockFfi>,
    from: Int,
): Int {
    var nestedDepth = 0
    for (index in from until blocks.size) {
        when {
            markdownDetailsOpener(blocks[index]) != null -> nestedDepth += 1
            isSelfContainedMarkdownDetails(blocks[index]) -> Unit
            blockEndsWithDetailsClose(blocks[index]) -> {
                if (nestedDepth == 0) return index
                nestedDepth -= 1
            }
        }
    }
    return -1
}

// A complete one-paragraph disclosure owns its closing tag. It is rendered
// inline by MarkdownBlockView and must not close a surrounding multi-block
// disclosure while the outer matcher scans for its corresponding close.
private fun isSelfContainedMarkdownDetails(block: MarkdownBlockFfi): Boolean =
    (block as? MarkdownBlockFfi.Paragraph)?.let { markdownDetailsSection(it.inlines) } != null

private fun blockEndsWithDetailsClose(block: MarkdownBlockFfi): Boolean {
    val lastLine =
        (block as? MarkdownBlockFfi.Paragraph)
            ?.let { markdownVisibleSiblings(it.inlines).filterNot(::isMarkdownLineBreak) }
            ?.lastOrNull() as? MarkdownInlineFfi.Text
    return lastLine?.content?.trim()?.endsWith(DETAILS_CLOSE_TAG, ignoreCase = true) == true
}

// The closing paragraph minus its trailing </details>. Null when the paragraph
// is only the close tag (contributes no content). The close may sit alone or
// trail a content paragraph's last line.
@Suppress("ReturnCount")
private fun stripTrailingDetailsClose(block: MarkdownBlockFfi): MarkdownBlockFfi? {
    val inlines = (block as? MarkdownBlockFfi.Paragraph)?.inlines ?: return null
    val lastTextIndex = inlines.indexOfLast { it is MarkdownInlineFfi.Text }
    val lastText = inlines.getOrNull(lastTextIndex) as? MarkdownInlineFfi.Text ?: return null
    val stripped = stripClosingDetailsTag(lastText.content)
    val rebuilt =
        if (stripped.isBlank()) {
            inlines.subList(0, lastTextIndex).dropLastWhile(::isMarkdownLineBreak)
        } else {
            inlines.toMutableList().also { it[lastTextIndex] = MarkdownInlineFfi.Text(stripped) }
        }
    return rebuilt.takeIf { it.isNotEmpty() }?.let { MarkdownBlockFfi.Paragraph(it) }
}

private fun stripClosingDetailsTag(content: String): String {
    val trimmedEnd = content.trimEnd()
    val idx = trimmedEnd.lowercase(Locale.ROOT).lastIndexOf(DETAILS_CLOSE_TAG)
    return if (idx >= 0 && idx + DETAILS_CLOSE_TAG.length == trimmedEnd.length) {
        trimmedEnd.substring(0, idx).trimEnd()
    } else {
        content
    }
}
