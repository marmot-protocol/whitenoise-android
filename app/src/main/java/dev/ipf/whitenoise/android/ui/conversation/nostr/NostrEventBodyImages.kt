package dev.ipf.whitenoise.android.ui.conversation.nostr

import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.whitenoise.android.ui.MARKDOWN_MAX_BLOCK_DEPTH
import dev.ipf.whitenoise.android.ui.MARKDOWN_MAX_CONTAINER_SIBLINGS
import dev.ipf.whitenoise.android.ui.MARKDOWN_MAX_INLINE_DEPTH
import dev.ipf.whitenoise.android.ui.MARKDOWN_MAX_TABLE_COLUMNS
import java.net.URI
import java.util.Locale

/** UI destinations from native-parsed structure; discovery never qualifies or fetches a payload. */
internal fun nostrEventImageCandidates(
    metadata: List<String>,
    document: MarkdownDocumentFfi?,
): List<String> = NostrBodyImageCollector().collect(metadata, document)

private class NostrBodyImageCollector {
    private val urls = LinkedHashSet<String>()
    private var remainingNodes = IMAGE_SCAN_NODE_BUDGET

    fun collect(
        metadata: List<String>,
        document: MarkdownDocumentFfi?,
    ): List<String> {
        metadata.take(MAX_READER_IMAGES * 2).forEach { add(it, explicit = true) }
        document?.let { blocks(it.blocks, 0) }
        return urls.toList()
    }

    private fun add(
        raw: String,
        explicit: Boolean,
    ) {
        if (urls.size >= MAX_READER_IMAGES || raw.length > IMAGE_DESTINATION_MAX_CHARS) return
        val safe = safeNostrMediaUrl(raw) ?: return
        val hintedImage =
            runCatching { URI(safe).path.orEmpty().substringAfterLast('.').lowercase(Locale.ROOT) }
                .getOrNull() in IMAGE_PATH_HINTS
        if (explicit || hintedImage) urls += safe
    }

    private fun available(): Boolean = remainingNodes > 0 && urls.size < MAX_READER_IMAGES

    private fun visit(): Boolean {
        if (!available()) return false
        remainingNodes--
        return true
    }

    private fun blocks(
        values: List<MarkdownBlockFfi>,
        depth: Int,
    ) {
        if (depth >= MARKDOWN_MAX_BLOCK_DEPTH) return
        for (block in values.take(MARKDOWN_MAX_CONTAINER_SIBLINGS)) {
            if (!visit()) return
            block(block, depth)
        }
    }

    private fun block(
        value: MarkdownBlockFfi,
        depth: Int,
    ) {
        when (value) {
            is MarkdownBlockFfi.Paragraph -> inlines(value.inlines, 0)
            is MarkdownBlockFfi.Heading -> inlines(value.inlines, 0)
            is MarkdownBlockFfi.BlockQuote -> blocks(value.blocks, depth + 1)
            is MarkdownBlockFfi.Details -> {
                inlines(value.summary, 0)
                blocks(value.body, depth + 1)
            }
            is MarkdownBlockFfi.ListBlock -> listItems(value, depth)
            is MarkdownBlockFfi.Table -> table(value)
            else -> Unit
        }
    }

    private fun listItems(
        block: MarkdownBlockFfi.ListBlock,
        depth: Int,
    ) {
        for (item in block.items.take(MARKDOWN_MAX_CONTAINER_SIBLINGS)) {
            if (!visit()) return
            blocks(item.blocks, depth + 1)
        }
    }

    private fun table(block: MarkdownBlockFfi.Table) {
        for (cell in block.header.take(MARKDOWN_MAX_TABLE_COLUMNS)) {
            if (!visit()) return
            inlines(cell.inlines, 0)
        }
        for (row in block.rows.take(MARKDOWN_MAX_CONTAINER_SIBLINGS)) {
            for (cell in row.take(MARKDOWN_MAX_TABLE_COLUMNS)) {
                if (!visit()) return
                inlines(cell.inlines, 0)
            }
        }
    }

    private fun inlines(
        values: List<MarkdownInlineFfi>,
        depth: Int,
    ) {
        if (depth >= MARKDOWN_MAX_INLINE_DEPTH) return
        for (inline in values.take(MARKDOWN_MAX_CONTAINER_SIBLINGS)) {
            if (!visit()) return
            when (inline) {
                is MarkdownInlineFfi.Image -> add(inline.dest, explicit = true)
                is MarkdownInlineFfi.Link -> {
                    add(inline.dest, explicit = false)
                    inlines(inline.children, depth + 1)
                }
                is MarkdownInlineFfi.Autolink -> add(inline.url, explicit = false)
                is MarkdownInlineFfi.Emph -> inlines(inline.children, depth + 1)
                is MarkdownInlineFfi.Strong -> inlines(inline.children, depth + 1)
                is MarkdownInlineFfi.Strikethrough -> inlines(inline.children, depth + 1)
                else -> Unit
            }
        }
    }
}

private const val IMAGE_SCAN_NODE_BUDGET = 1024
private const val IMAGE_DESTINATION_MAX_CHARS = 2048
private val IMAGE_PATH_HINTS = setOf("jpg", "jpeg", "png", "webp")

/** A large body remains readable; extra card image discovery simply declines another parse. */
internal fun boundedNostrImageBody(body: String?): String? =
    body?.takeIf {
        it.length <= IMAGE_BODY_PARSE_BYTES && it.toByteArray(Charsets.UTF_8).size <= IMAGE_BODY_PARSE_BYTES
    }

private const val IMAGE_BODY_PARSE_BYTES = 128 * 1024
