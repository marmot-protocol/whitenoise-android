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
): List<String> = nostrEventMediaCandidates(metadata, emptyList(), document).imageUrls

internal enum class NostrLinkedMediaKind { Image, Video }

internal data class NostrEventMediaCandidates(
    val imageUrls: List<String>,
    val videoUrls: List<String>,
)

internal fun nostrEventMediaCandidates(
    images: List<String>,
    videos: List<String>,
    document: MarkdownDocumentFfi?,
): NostrEventMediaCandidates = NostrBodyImageCollector().collect(images, videos, document)

/** A display hint for an explicit action, never payload qualification or acquisition. */
internal fun nostrLinkedMediaKind(raw: String): NostrLinkedMediaKind? {
    if (raw.length > IMAGE_DESTINATION_MAX_CHARS) return null
    val safe = safeNostrMediaUrl(raw)
    val extension =
        runCatching {
            URI(safe)
                .path
                .orEmpty()
                .substringAfterLast('.')
                .lowercase(Locale.ROOT)
        }.getOrNull()
    return when (extension) {
        in IMAGE_PATH_HINTS -> NostrLinkedMediaKind.Image
        in VIDEO_PATH_HINTS -> NostrLinkedMediaKind.Video
        else -> null
    }
}

private class NostrBodyImageCollector {
    private val urls = LinkedHashMap<String, NostrLinkedMediaKind>()
    private var remainingNodes = IMAGE_SCAN_NODE_BUDGET

    fun collect(
        metadata: List<String>,
        videos: List<String>,
        document: MarkdownDocumentFfi?,
    ): NostrEventMediaCandidates {
        videos.take(MAX_READER_IMAGES).forEach { add(it, kind = NostrLinkedMediaKind.Video, metadata = true) }
        metadata.take(MAX_READER_IMAGES * 2).forEach { add(it, kind = NostrLinkedMediaKind.Image, metadata = true) }
        document?.let { blocks(it.blocks, 0) }
        return NostrEventMediaCandidates(
            urls.filterValues { it == NostrLinkedMediaKind.Image }.keys.toList(),
            urls.filterValues { it == NostrLinkedMediaKind.Video }.keys.toList(),
        )
    }

    private fun add(
        raw: String,
        kind: NostrLinkedMediaKind? = null,
        metadata: Boolean = false,
    ) {
        // Preserve the existing native metadata locator contract, including signed
        // CDN URLs; the short locator bound applies to additional body discovery.
        if (urls.size >= MAX_READER_IMAGES || (!metadata && raw.length > IMAGE_DESTINATION_MAX_CHARS)) return
        val safe = safeNostrMediaUrl(raw) ?: return
        val resolved = kind ?: nostrLinkedMediaKind(safe)
        if (resolved != null) urls.putIfAbsent(safe, resolved)
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
                is MarkdownInlineFfi.Image -> add(inline.dest, kind = NostrLinkedMediaKind.Image)
                is MarkdownInlineFfi.Link -> {
                    add(inline.dest)
                    inlines(inline.children, depth + 1)
                }
                is MarkdownInlineFfi.Autolink -> add(inline.url)
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
private val IMAGE_PATH_HINTS = setOf("jpg", "jpeg", "png", "webp", "gif")
private val VIDEO_PATH_HINTS = setOf("mp4", "m4v", "mov", "webm", "m3u8")

/** A large body remains readable; extra card image discovery simply declines another parse. */
internal fun boundedNostrImageBody(body: String?): String? =
    body?.takeIf {
        it.length <= IMAGE_BODY_PARSE_BYTES && it.toByteArray(Charsets.UTF_8).size <= IMAGE_BODY_PARSE_BYTES
    }

private const val IMAGE_BODY_PARSE_BYTES = 128 * 1024
