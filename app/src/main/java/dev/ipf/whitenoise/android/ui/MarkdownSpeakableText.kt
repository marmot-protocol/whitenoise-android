@file:Suppress("TooManyFunctions")

package dev.ipf.whitenoise.android.ui

import dev.ipf.marmotkit.MarkdownAutolinkKindFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownLinkDestinationKindFfi
import dev.ipf.marmotkit.MarkdownNostrEntityFfi
import dev.ipf.marmotkit.MarkdownTableCellFfi
import java.security.MessageDigest

internal const val MARKDOWN_SPEAKABLE_MAX_LENGTH = 32_000
internal const val MARKDOWN_SPEAKABLE_MAX_NODES = 4_096

/** One reversible run in a speakable projection. Ranges are half-open UTF-16 offsets. */
internal data class SpeakableTextProjectionSpan(
    val spokenStart: Int,
    val spokenEnd: Int,
    val leafId: String,
    val visibleStart: Int,
    val visibleEnd: Int,
)

/** Visible speech text plus deterministic rendered-leaf coordinates. */
internal data class SpeakableTextProjection(
    val text: String,
    val spans: List<SpeakableTextProjectionSpan>,
    val projectionId: String = speakableProjectionId(text, spans),
)

private fun speakableProjectionId(
    text: String,
    spans: List<SpeakableTextProjectionSpan>,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.updateString("whitenoise-speakable-projection-v1")
    digest.updateString(text)
    digest.updateInt(spans.size)
    spans.forEach { span ->
        digest.updateInt(span.spokenStart)
        digest.updateInt(span.spokenEnd)
        digest.updateString(span.leafId)
        digest.updateInt(span.visibleStart)
        digest.updateInt(span.visibleEnd)
    }
    return buildString(SHA_256_HEX_LENGTH) {
        digest.digest().forEach { byte ->
            val value = byte.toInt() and UNSIGNED_BYTE_MASK
            append(HEX_DIGITS[value ushr NIBBLE_BITS])
            append(HEX_DIGITS[value and NIBBLE_MASK])
        }
    }
}

private fun MessageDigest.updateString(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    updateInt(bytes.size)
    update(bytes)
}

private fun MessageDigest.updateInt(value: Int) {
    update((value ushr INT_BYTE_THREE_SHIFT).toByte())
    update((value ushr INT_BYTE_TWO_SHIFT).toByte())
    update((value ushr INT_BYTE_ONE_SHIFT).toByte())
    update(value.toByte())
}

private const val HEX_DIGITS = "0123456789abcdef"
private const val SHA_256_HEX_LENGTH = 64
private const val UNSIGNED_BYTE_MASK = 0xff
private const val NIBBLE_BITS = 4
private const val NIBBLE_MASK = 0x0f
private const val INT_BYTE_THREE_SHIFT = 24
private const val INT_BYTE_TWO_SHIFT = 16
private const val INT_BYTE_ONE_SHIFT = 8

/**
 * Pure Markdown AST projection for speech. Formatting nodes contribute only
 * their visible text; block boundaries become sentence boundaries so the TTS
 * sentence chunker does not turn a structured document into one run-on line.
 */
internal fun markdownDocumentToSpeakableText(
    document: MarkdownDocumentFfi,
    mentionDisplayName: ((String) -> String?)? = null,
    isGroupMember: ((String) -> Boolean)? = null,
): String = markdownDocumentToSpeakableProjection(document, mentionDisplayName, isGroupMember).text

/** Same speech policy as [markdownDocumentToSpeakableText], with reversible leaf mappings. */
internal fun markdownDocumentToSpeakableProjection(
    document: MarkdownDocumentFfi,
    mentionDisplayName: ((String) -> String?)? = null,
    isGroupMember: ((String) -> Boolean)? = null,
): SpeakableTextProjection {
    val collector = SpeakableCollector()
    val blockLimit = minOf(document.blocks.size, MARKDOWN_MAX_CONTAINER_SIBLINGS)
    for (blockIndex in 0 until blockLimit) {
        if (collector.exhausted) break
        val block = document.blocks[blockIndex]
        collectSpeakableBlock(
            block = block,
            collector = collector,
            mentionDisplayName = mentionDisplayName,
            isGroupMember = isGroupMember,
            depth = 0,
            path = "b$blockIndex",
        )
    }
    return collector.build()
}

/** Last-resort plain projection uses one stable visible leaf. */
internal fun legacyTextToSpeakableProjection(text: String): SpeakableTextProjection {
    val collector = SpeakableCollector()
    val visible = markdownSafeDisplayText(text, MARKDOWN_SPEAKABLE_MAX_LENGTH)
    MappedText
        .direct(visible, leafId = "plain")
        .lines()
        .forEach { line ->
            if (!collector.exhausted) collector.addSegment(line.withoutSpeakableUrls())
        }
    return collector.build()
}

private fun collectSpeakableBlock(
    block: MarkdownBlockFfi,
    collector: SpeakableCollector,
    mentionDisplayName: ((String) -> String?)?,
    isGroupMember: ((String) -> Boolean)?,
    depth: Int,
    path: String,
) {
    if (markdownDepthExceeded(depth) || !collector.visitNode()) return
    when (block) {
        is MarkdownBlockFfi.Paragraph ->
            collectSpeakableInlineSegment(block.inlines, collector, mentionDisplayName, isGroupMember, path)
        is MarkdownBlockFfi.Heading ->
            collectSpeakableInlineSegment(block.inlines, collector, mentionDisplayName, isGroupMember, path)
        MarkdownBlockFfi.ThematicBreak -> Unit
        is MarkdownBlockFfi.CodeBlock -> collector.addLeafSegment(block.content, "$path/code")
        is MarkdownBlockFfi.BlockQuote ->
            collectSpeakableBlocks(block.blocks, collector, mentionDisplayName, isGroupMember, depth + 1, "$path/q")
        is MarkdownBlockFfi.ListBlock ->
            collectSpeakableList(block, collector, mentionDisplayName, isGroupMember, depth + 1, path)
        is MarkdownBlockFfi.Table ->
            collectSpeakableTable(block, collector, mentionDisplayName, isGroupMember, path)
        is MarkdownBlockFfi.MathBlock -> collector.addLeafSegment(block.content, "$path/math")
    }
}

private fun collectSpeakableBlocks(
    blocks: List<MarkdownBlockFfi>,
    collector: SpeakableCollector,
    mentionDisplayName: ((String) -> String?)?,
    isGroupMember: ((String) -> Boolean)?,
    depth: Int,
    path: String,
) {
    val blockLimit = minOf(blocks.size, MARKDOWN_MAX_CONTAINER_SIBLINGS)
    for (blockIndex in 0 until blockLimit) {
        if (collector.exhausted) break
        val block = blocks[blockIndex]
        collectSpeakableBlock(
            block,
            collector,
            mentionDisplayName,
            isGroupMember,
            depth,
            "$path/b$blockIndex",
        )
    }
}

private fun collectSpeakableList(
    list: MarkdownBlockFfi.ListBlock,
    collector: SpeakableCollector,
    mentionDisplayName: ((String) -> String?)?,
    isGroupMember: ((String) -> Boolean)?,
    depth: Int,
    path: String,
) {
    val itemLimit = minOf(list.items.size, MARKDOWN_MAX_CONTAINER_SIBLINGS)
    for (itemIndex in 0 until itemLimit) {
        if (!collector.visitNode()) break
        val item = list.items[itemIndex]
        collectSpeakableBlocks(
            item.blocks,
            collector,
            mentionDisplayName,
            isGroupMember,
            depth,
            "$path/i$itemIndex",
        )
    }
}

private fun collectSpeakableTable(
    table: MarkdownBlockFfi.Table,
    collector: SpeakableCollector,
    mentionDisplayName: ((String) -> String?)?,
    isGroupMember: ((String) -> Boolean)?,
    path: String,
) {
    var remainingCells = MARKDOWN_MAX_TABLE_CELLS

    fun collectRow(
        cells: List<MarkdownTableCellFfi>,
        rowPath: String,
    ) {
        val cellLimit = minOf(cells.size, MARKDOWN_MAX_TABLE_COLUMNS, remainingCells)
        for (cellIndex in 0 until cellLimit) {
            if (!collector.visitNode()) return
            remainingCells--
            val cell = cells[cellIndex]
            collectSpeakableInlineSegment(
                cell.inlines,
                collector,
                mentionDisplayName,
                isGroupMember,
                "$rowPath$cellIndex",
            )
        }
    }

    collectRow(table.header, "$path/h")
    val rowLimit = minOf(table.rows.size, MARKDOWN_MAX_CONTAINER_SIBLINGS)
    for (rowIndex in 0 until rowLimit) {
        if (collector.exhausted || remainingCells <= 0) break
        collectRow(table.rows[rowIndex], "$path/r$rowIndex/c")
    }
}

private fun collectSpeakableInlineSegment(
    inlines: List<MarkdownInlineFfi>,
    collector: SpeakableCollector,
    mentionDisplayName: ((String) -> String?)?,
    isGroupMember: ((String) -> Boolean)?,
    path: String,
) {
    val text =
        MappedTextBuilder()
            .apply {
                appendSpeakableInlines(
                    inlines = inlines,
                    collector = collector,
                    mentionDisplayName = mentionDisplayName,
                    isGroupMember = isGroupMember,
                    depth = 0,
                    maxChars = collector.remainingChars,
                    path = path,
                )
            }.build()
    for (line in text.lines()) {
        if (collector.exhausted) break
        collector.addSegment(line)
    }
}

// Exhaustive sealed-node dispatch is clearer than scattering one AST walk
// across type casts; the traversal itself remains depth/node/size bounded.
@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun MappedTextBuilder.appendSpeakableInlines(
    inlines: List<MarkdownInlineFfi>,
    collector: SpeakableCollector,
    mentionDisplayName: ((String) -> String?)?,
    isGroupMember: ((String) -> Boolean)?,
    depth: Int,
    maxChars: Int,
    path: String,
) {
    if (markdownInlineDepthExceeded(depth)) return
    val inlineLimit = minOf(inlines.size, MARKDOWN_MAX_CONTAINER_SIBLINGS)
    for (inlineIndex in 0 until inlineLimit) {
        if (length >= maxChars || !collector.visitNode()) break
        val inline = inlines[inlineIndex]
        val inlinePath = "$path/n$inlineIndex"
        when (inline) {
            is MarkdownInlineFfi.Text ->
                append(MappedText.visibleLeaf(inline.content, inlinePath, maxChars - length))
            MarkdownInlineFfi.SoftBreak, MarkdownInlineFfi.HardBreak -> appendSynthetic("\n")
            is MarkdownInlineFfi.Code ->
                append(MappedText.visibleLeaf(inline.content, inlinePath, maxChars - length))
            is MarkdownInlineFfi.Emph ->
                appendSpeakableInlines(
                    inline.children,
                    collector,
                    mentionDisplayName,
                    isGroupMember,
                    depth + 1,
                    maxChars,
                    inlinePath,
                )
            is MarkdownInlineFfi.Strong ->
                appendSpeakableInlines(
                    inline.children,
                    collector,
                    mentionDisplayName,
                    isGroupMember,
                    depth + 1,
                    maxChars,
                    inlinePath,
                )
            is MarkdownInlineFfi.Strikethrough ->
                appendSpeakableInlines(
                    inline.children,
                    collector,
                    mentionDisplayName,
                    isGroupMember,
                    depth + 1,
                    maxChars,
                    inlinePath,
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
                    path = inlinePath,
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
                    path = inlinePath,
                )
            is MarkdownInlineFfi.Autolink ->
                if (inline.kind == MarkdownAutolinkKindFfi.EMAIL) {
                    append(MappedText.visibleLeaf(inline.url, inlinePath, maxChars - length))
                }
            is MarkdownInlineFfi.Math ->
                append(MappedText.visibleLeaf(inline.content, inlinePath, maxChars - length))
            is MarkdownInlineFfi.NostrMention ->
                appendSpeakableNostrEntity(
                    entity = inline.entity,
                    isMention = true,
                    mentionDisplayName = mentionDisplayName,
                    isGroupMember = isGroupMember,
                    maxChars = maxChars - length,
                    leafId = inlinePath,
                )
            is MarkdownInlineFfi.NostrUri ->
                appendSpeakableNostrEntity(
                    entity = inline.entity,
                    isMention = false,
                    mentionDisplayName = mentionDisplayName,
                    isGroupMember = isGroupMember,
                    maxChars = maxChars - length,
                    leafId = inlinePath,
                )
        }
    }
}

private fun MappedTextBuilder.appendSpeakableLinkLabel(
    children: List<MarkdownInlineFfi>,
    destination: String,
    destinationIsWeb: Boolean,
    collector: SpeakableCollector,
    mentionDisplayName: ((String) -> String?)?,
    isGroupMember: ((String) -> Boolean)?,
    depth: Int,
    maxChars: Int,
    path: String,
) {
    val label =
        MappedTextBuilder()
            .apply {
                appendSpeakableInlines(
                    inlines = children,
                    collector = collector,
                    mentionDisplayName = mentionDisplayName,
                    isGroupMember = isGroupMember,
                    depth = depth,
                    maxChars = maxChars,
                    path = path,
                )
            }.build()
    if (!isSpeakableUrlLabel(label.text, destination, destinationIsWeb)) append(label)
}

private fun MappedTextBuilder.appendSpeakableNostrEntity(
    entity: MarkdownNostrEntityFfi,
    isMention: Boolean,
    mentionDisplayName: ((String) -> String?)?,
    isGroupMember: ((String) -> Boolean)?,
    maxChars: Int,
    leafId: String,
) {
    val display =
        markdownNostrEntityDisplay(
            entity = entity,
            mention = isMention,
            mentionDisplayName = mentionDisplayName,
            isGroupMember = isGroupMember,
        ).visibleText.safeUtf16Prefix(maxChars)
    append(MappedText.direct(display, leafId))
}

private class SpeakableCollector {
    private val output = MappedTextBuilder()
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

    fun addLeafSegment(
        content: String,
        leafId: String,
    ) {
        addSegment(MappedText.visibleLeaf(content, leafId, remainingChars))
    }

    fun addSegment(segment: MappedText) {
        val normalized = segment.asSpeakableSentence()
        if (normalized.text.isEmpty() || output.length >= MARKDOWN_SPEAKABLE_MAX_LENGTH) return
        if (output.length > 0) output.appendSynthetic(" ")
        output.append(normalized.safePrefix(remainingChars))
    }

    fun build(): SpeakableTextProjection = output.build().trimWhitespace(endOnly = true).toProjection()
}

private data class VisibleSource(
    val leafId: String,
    val offset: Int,
)

private data class MappedText(
    val text: String,
    val sources: List<VisibleSource?>,
) {
    init {
        require(text.length == sources.size)
    }

    fun safePrefix(maxChars: Int): MappedText {
        val end =
            when {
                text.length <= maxChars -> text.length
                maxChars <= 0 -> 0
                text[maxChars - 1].isHighSurrogate() && text[maxChars].isLowSurrogate() -> maxChars - 1
                else -> maxChars
            }
        return when (end) {
            0 -> EMPTY
            text.length -> this
            else -> slice(0, end)
        }
    }

    fun slice(
        start: Int,
        end: Int,
    ): MappedText = MappedText(text.substring(start, end), sources.subList(start, end))

    fun lines(): List<MappedText> {
        val lines = ArrayList<MappedText>()
        var start = 0
        var index = 0
        while (index < text.length) {
            if (text[index] == '\n' || text[index] == '\r') {
                lines += slice(start, index)
                if (text[index] == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                start = index + 1
            }
            index++
        }
        lines += slice(start, text.length)
        return lines
    }

    fun toProjection(): SpeakableTextProjection {
        val spans = ArrayList<SpeakableTextProjectionSpan>()
        var spokenStart = 0
        while (spokenStart < sources.size) {
            val source = sources[spokenStart]
            if (source == null) {
                spokenStart++
                continue
            }
            var spokenEnd = spokenStart + 1
            var visibleEnd = source.offset + 1
            while (
                spokenEnd < sources.size &&
                sources[spokenEnd]?.leafId == source.leafId &&
                sources[spokenEnd]?.offset == visibleEnd
            ) {
                spokenEnd++
                visibleEnd++
            }
            spans +=
                SpeakableTextProjectionSpan(
                    spokenStart = spokenStart,
                    spokenEnd = spokenEnd,
                    leafId = source.leafId,
                    visibleStart = source.offset,
                    visibleEnd = visibleEnd,
                )
            spokenStart = spokenEnd
        }
        return SpeakableTextProjection(text, spans)
    }

    companion object {
        val EMPTY = MappedText("", emptyList())

        fun direct(
            visible: String,
            leafId: String,
        ): MappedText =
            MappedText(
                text = visible,
                sources = visible.indices.map { offset -> VisibleSource(leafId, offset) },
            )

        fun synthetic(value: String): MappedText = MappedText(value, List(value.length) { null })

        /** Maps sanitized speech back to the same sanitized text the renderer shows. */
        fun visibleLeaf(
            content: String,
            leafId: String,
            maxChars: Int,
        ): MappedText {
            if (maxChars <= 0) return EMPTY
            val visible = markdownSafeDisplayText(content, maxChars)
            return direct(visible, leafId).withoutSpeakableUrls().safePrefix(maxChars)
        }
    }
}

/** True when a speakable segment already ends with authored punctuation or an emoji. */
@Suppress("MaxLineLength")
internal fun String.endsWithSpeakableSentenceTerminal(): Boolean = hasAuthoredSpeakableTerminalPunctuation() || endsWithSpeakableEmojiSequence()

private fun String.hasAuthoredSpeakableTerminalPunctuation(): Boolean = isEmpty() || last() in ".!?;:,"

private fun MappedText.asSpeakableSentence(): MappedText {
    var normalized = trimWhitespace()
    normalized = normalized.replaceMatches(emptySpeakableDelimiters) { MappedText.synthetic(" ") }
    normalized = normalized.replaceMatches(speakableWhitespace, MappedText::normalizedSpace)
    normalized =
        normalized.replaceMatches(spaceBeforeSpeakablePunctuation) { match ->
            match.slice(match.text.lastIndex, match.text.length)
        }
    normalized = normalized.trimWhitespace()
    if (normalized.text.isEmpty() || normalized.text.endsWithSpeakableSentenceTerminal()) return normalized
    return MappedTextBuilder()
        .apply {
            append(normalized)
            appendSynthetic(".")
        }.build()
}

private fun MappedText.normalizedSpace(): MappedText =
    MappedText(
        text = " ",
        sources = listOf(sources.firstOrNull { it != null }),
    )

private fun MappedText.withoutSpeakableUrls(): MappedText {
    val omissions = speakableUrlOmissions(text)
    if (omissions.isEmpty()) return this
    val builder = MappedTextBuilder()
    var sourceStart = 0
    omissions.forEach { omission ->
        builder.append(slice(sourceStart, omission.start))
        builder.appendSynthetic(" ")
        builder.append(slice(omission.preservedSuffixStart, omission.end))
        sourceStart = omission.end
    }
    builder.append(slice(sourceStart, text.length))
    return builder.build().trimWhitespace(endOnly = true).trimEnd(':', ';', ',')
}

private fun MappedText.replaceMatches(
    regex: Regex,
    replacement: (MappedText) -> MappedText,
): MappedText {
    val matches = regex.findAll(text).toList()
    if (matches.isEmpty()) return this
    val builder = MappedTextBuilder()
    var sourceStart = 0
    matches.forEach { match ->
        val matchEnd = match.range.last + 1
        builder.append(slice(sourceStart, match.range.first))
        builder.append(replacement(slice(match.range.first, matchEnd)))
        sourceStart = matchEnd
    }
    builder.append(slice(sourceStart, text.length))
    return builder.build()
}

private fun MappedText.trimWhitespace(endOnly: Boolean = false): MappedText {
    var start = 0
    if (!endOnly) {
        while (start < text.length && text[start].isWhitespace()) start++
    }
    var end = text.length
    while (end > start && text[end - 1].isWhitespace()) end--
    return when {
        start == 0 && end == text.length -> this
        start >= end -> MappedText.EMPTY
        else -> slice(start, end)
    }
}

private fun MappedText.trimEnd(vararg characters: Char): MappedText {
    var end = text.length
    while (end > 0 && text[end - 1] in characters) end--
    return if (end == text.length) this else slice(0, end)
}

private class MappedTextBuilder {
    private val text = StringBuilder()
    private val sources = ArrayList<VisibleSource?>()

    val length: Int
        get() = text.length

    fun append(mapped: MappedText) {
        text.append(mapped.text)
        sources.addAll(mapped.sources)
    }

    fun appendSynthetic(value: String) {
        text.append(value)
        repeat(value.length) { sources += null }
    }

    fun build(): MappedText = MappedText(text.toString(), sources.toList())
}
