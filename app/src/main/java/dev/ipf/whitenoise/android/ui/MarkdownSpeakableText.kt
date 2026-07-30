package dev.ipf.whitenoise.android.ui

import dev.ipf.marmotkit.MarkdownAutolinkKindFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownLinkDestinationKindFfi
import dev.ipf.marmotkit.MarkdownNostrEntityFfi

internal const val MARKDOWN_SPEAKABLE_MAX_LENGTH = 32_000
internal const val MARKDOWN_SPEAKABLE_MAX_NODES = 4_096

/**
 * Pure Markdown AST projection for speech. Formatting nodes contribute only
 * their visible text; block boundaries become sentence boundaries so the TTS
 * sentence chunker does not turn a structured document into one run-on line.
 */
internal fun markdownDocumentToSpeakableText(
    document: MarkdownDocumentFfi,
    mentionDisplayName: ((String) -> String?)? = null,
    isGroupMember: ((String) -> Boolean)? = null,
): String {
    val collector = SpeakableCollector()
    for (block in markdownVisibleSiblings(document.blocks)) {
        if (collector.exhausted) break
        collectSpeakableBlock(block, collector, mentionDisplayName, isGroupMember, depth = 0)
    }
    return collector.build()
}

private fun collectSpeakableBlock(
    block: MarkdownBlockFfi,
    collector: SpeakableCollector,
    mentionDisplayName: ((String) -> String?)?,
    isGroupMember: ((String) -> Boolean)?,
    depth: Int,
) {
    if (markdownDepthExceeded(depth) || !collector.visitNode()) return
    when (block) {
        is MarkdownBlockFfi.Paragraph ->
            collectSpeakableInlineSegment(block.inlines, collector, mentionDisplayName, isGroupMember)
        is MarkdownBlockFfi.Heading ->
            collectSpeakableInlineSegment(block.inlines, collector, mentionDisplayName, isGroupMember)
        MarkdownBlockFfi.ThematicBreak -> Unit
        is MarkdownBlockFfi.CodeBlock -> collector.addLeafSegment(block.content)
        is MarkdownBlockFfi.BlockQuote ->
            collectSpeakableBlocks(block.blocks, collector, mentionDisplayName, isGroupMember, depth + 1)
        is MarkdownBlockFfi.ListBlock ->
            collectSpeakableList(block, collector, mentionDisplayName, isGroupMember, depth + 1)
        is MarkdownBlockFfi.Table ->
            collectSpeakableTable(block, collector, mentionDisplayName, isGroupMember)
        is MarkdownBlockFfi.MathBlock -> collector.addLeafSegment(block.content)
    }
}

private fun collectSpeakableBlocks(
    blocks: List<MarkdownBlockFfi>,
    collector: SpeakableCollector,
    mentionDisplayName: ((String) -> String?)?,
    isGroupMember: ((String) -> Boolean)?,
    depth: Int,
) {
    for (block in markdownVisibleSiblings(blocks)) {
        if (collector.exhausted) break
        collectSpeakableBlock(block, collector, mentionDisplayName, isGroupMember, depth)
    }
}

private fun collectSpeakableList(
    list: MarkdownBlockFfi.ListBlock,
    collector: SpeakableCollector,
    mentionDisplayName: ((String) -> String?)?,
    isGroupMember: ((String) -> Boolean)?,
    depth: Int,
) {
    for (item in markdownVisibleSiblings(list.items)) {
        if (!collector.visitNode()) break
        collectSpeakableBlocks(item.blocks, collector, mentionDisplayName, isGroupMember, depth)
    }
}

private fun collectSpeakableTable(
    table: MarkdownBlockFfi.Table,
    collector: SpeakableCollector,
    mentionDisplayName: ((String) -> String?)?,
    isGroupMember: ((String) -> Boolean)?,
) {
    val visibleTable = markdownVisibleTable(table.header, table.rows)
    for (cell in visibleTable.header.cells) {
        if (!collector.visitNode()) break
        collectSpeakableInlineSegment(cell.inlines, collector, mentionDisplayName, isGroupMember)
    }
    for (row in visibleTable.rows) {
        if (!collector.visitNode()) break
        for (cell in row.cells) {
            if (!collector.visitNode()) break
            collectSpeakableInlineSegment(cell.inlines, collector, mentionDisplayName, isGroupMember)
        }
    }
}

private fun collectSpeakableInlineSegment(
    inlines: List<MarkdownInlineFfi>,
    collector: SpeakableCollector,
    mentionDisplayName: ((String) -> String?)?,
    isGroupMember: ((String) -> Boolean)?,
) {
    val text =
        buildString {
            appendSpeakableInlines(
                inlines = inlines,
                collector = collector,
                mentionDisplayName = mentionDisplayName,
                isGroupMember = isGroupMember,
                depth = 0,
                maxChars = collector.remainingChars,
            )
        }
    for (line in text.lineSequence()) {
        if (collector.exhausted) break
        collector.addSegment(line)
    }
}

// Exhaustive sealed-node dispatch is clearer than scattering one AST walk
// across type casts; the traversal itself remains depth/node/size bounded.
@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun StringBuilder.appendSpeakableInlines(
    inlines: List<MarkdownInlineFfi>,
    collector: SpeakableCollector,
    mentionDisplayName: ((String) -> String?)?,
    isGroupMember: ((String) -> Boolean)?,
    depth: Int,
    maxChars: Int,
) {
    if (markdownInlineDepthExceeded(depth)) return
    for (inline in markdownVisibleSiblings(inlines)) {
        if (length >= maxChars || !collector.visitNode()) break
        when (inline) {
            is MarkdownInlineFfi.Text ->
                append(
                    markdownSpeakableLeafText(
                        inline.content,
                        maxChars - length,
                    ),
                )
            MarkdownInlineFfi.SoftBreak, MarkdownInlineFfi.HardBreak -> append('\n')
            is MarkdownInlineFfi.Code ->
                append(
                    markdownSpeakableLeafText(
                        inline.content,
                        maxChars - length,
                    ),
                )
            is MarkdownInlineFfi.Emph ->
                appendSpeakableInlines(
                    inline.children,
                    collector,
                    mentionDisplayName,
                    isGroupMember,
                    depth + 1,
                    maxChars,
                )
            is MarkdownInlineFfi.Strong ->
                appendSpeakableInlines(
                    inline.children,
                    collector,
                    mentionDisplayName,
                    isGroupMember,
                    depth + 1,
                    maxChars,
                )
            is MarkdownInlineFfi.Strikethrough ->
                appendSpeakableInlines(
                    inline.children,
                    collector,
                    mentionDisplayName,
                    isGroupMember,
                    depth + 1,
                    maxChars,
                )
            is MarkdownInlineFfi.Link ->
                appendSpeakableLinkLabel(
                    children = inline.children,
                    destination = inline.dest,
                    destinationIsWeb = inline.classification == MarkdownLinkDestinationKindFfi.WEB,
                    collector = collector,
                    mentionDisplayName = mentionDisplayName,
                    isGroupMember = isGroupMember,
                    depth = depth + 1,
                    maxChars = maxChars - length,
                )
            is MarkdownInlineFfi.Image ->
                appendSpeakableLinkLabel(
                    children = inline.alt,
                    destination = inline.dest,
                    destinationIsWeb = inline.classification == MarkdownLinkDestinationKindFfi.WEB,
                    collector = collector,
                    mentionDisplayName = mentionDisplayName,
                    isGroupMember = isGroupMember,
                    depth = depth + 1,
                    maxChars = maxChars - length,
                )
            is MarkdownInlineFfi.Autolink ->
                if (inline.kind == MarkdownAutolinkKindFfi.EMAIL) {
                    append(
                        markdownSpeakableLeafText(
                            inline.url,
                            maxChars - length,
                        ),
                    )
                }
            is MarkdownInlineFfi.Math ->
                append(
                    markdownSpeakableLeafText(
                        inline.content,
                        maxChars - length,
                    ),
                )
            is MarkdownInlineFfi.NostrMention ->
                appendSpeakableNostrEntity(
                    entity = inline.entity,
                    isMention = true,
                    mentionDisplayName = mentionDisplayName,
                    isGroupMember = isGroupMember,
                    maxChars = maxChars,
                )
            is MarkdownInlineFfi.NostrUri ->
                appendSpeakableNostrEntity(
                    entity = inline.entity,
                    isMention = false,
                    mentionDisplayName = mentionDisplayName,
                    isGroupMember = isGroupMember,
                    maxChars = maxChars,
                )
        }
    }
}

private fun StringBuilder.appendSpeakableLinkLabel(
    children: List<MarkdownInlineFfi>,
    destination: String,
    destinationIsWeb: Boolean,
    collector: SpeakableCollector,
    mentionDisplayName: ((String) -> String?)?,
    isGroupMember: ((String) -> Boolean)?,
    depth: Int,
    maxChars: Int,
) {
    val label =
        buildString {
            appendSpeakableInlines(
                inlines = children,
                collector = collector,
                mentionDisplayName = mentionDisplayName,
                isGroupMember = isGroupMember,
                depth = depth,
                maxChars = maxChars,
            )
        }
    if (!isSpeakableUrlLabel(label, destination, destinationIsWeb)) append(label)
}

private fun StringBuilder.appendSpeakableNostrEntity(
    entity: MarkdownNostrEntityFfi,
    isMention: Boolean,
    mentionDisplayName: ((String) -> String?)?,
    isGroupMember: ((String) -> Boolean)?,
    maxChars: Int,
) {
    val display =
        markdownNostrEntityDisplay(
            entity = entity,
            mention = isMention,
            mentionDisplayName = mentionDisplayName,
            isGroupMember = isGroupMember,
        )
    append(display.visibleText.safeUtf16Prefix(maxChars - length))
}

private class SpeakableCollector {
    private val output = StringBuilder(minOf(MARKDOWN_SPEAKABLE_MAX_LENGTH, 256))
    private var visitedNodes = 0

    val exhausted: Boolean
        get() = output.length >= MARKDOWN_SPEAKABLE_MAX_LENGTH || visitedNodes >= MARKDOWN_SPEAKABLE_MAX_NODES

    val remainingChars: Int
        get() = (MARKDOWN_SPEAKABLE_MAX_LENGTH - output.length).coerceAtLeast(0)

    fun visitNode(): Boolean {
        if (exhausted) return false
        visitedNodes++
        return true
    }

    fun addLeafSegment(content: String) {
        addSegment(markdownSpeakableLeafText(content, remainingChars))
    }

    fun addSegment(segment: String) {
        output.appendSpeakableSegment(segment)
    }

    fun build(): String = output.toString().trimEnd()
}
