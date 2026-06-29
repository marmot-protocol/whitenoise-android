package dev.ipf.whitenoise.android.media

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.ui.markdownDepthExceeded
import dev.ipf.whitenoise.android.ui.markdownInlineDepthExceeded

/**
 * Pure, framework-free helper that classifies a conversation's local timeline
 * into the typed buckets the Shared Media surface renders: images, videos,
 * voice notes, files, and URLs.
 *
 * Everything is derived from data already on the message record — the `imeta`
 * attachment tags ([MediaReferenceParser]) and the parsed markdown body
 * ([AppMessageRecordFfi.contentTokens]) — so there is no new FFI or transport.
 * The sweep is O(N) over the records and allocation-light; per-record parsing
 * is cached across rebuilds so a new message does not force old `imeta` tags
 * and markdown links to be re-walked every time.
 */
object MediaInventory {
    private const val RECORD_CACHE_MAX_ENTRIES = 512

    enum class Kind { IMAGE, VIDEO, VOICE, FILE }

    /** Where a media entry came from: an encrypted attachment, or a bare link in the body. */
    sealed interface Source {
        data class Attachment(
            val reference: MediaAttachmentReferenceFfi,
        ) : Source

        data class LinkedUrl(
            val url: String,
        ) : Source
    }

    data class MediaEntry(
        val messageIdHex: String,
        val sender: String,
        val recordedAt: ULong,
        val kind: Kind,
        val source: Source,
    )

    data class UrlEntry(
        val messageIdHex: String,
        val sender: String,
        val recordedAt: ULong,
        val url: String,
    )

    private data class RecordCacheKey(
        val messageIdHex: String,
        val recordedAt: ULong,
        val receivedAt: ULong,
    )

    private data class RecordInventory(
        val media: List<MediaEntry>,
        val urls: List<UrlEntry>,
    )

    private val recordCache =
        object : LinkedHashMap<RecordCacheKey, RecordInventory>(RECORD_CACHE_MAX_ENTRIES + 1, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<RecordCacheKey, RecordInventory>?): Boolean = size > RECORD_CACHE_MAX_ENTRIES
        }

    fun clear() {
        synchronized(recordCache) {
            recordCache.clear()
        }
    }

    /**
     * Typed buckets in timeline order (oldest first, as supplied). Videos,
     * voice, and files only ever come from attachments; images additionally
     * include bare image-URL links in the body; urls excludes those image
     * links so the same URL is never counted twice.
     */
    data class Inventory(
        val images: List<MediaEntry> = emptyList(),
        val videos: List<MediaEntry> = emptyList(),
        val voice: List<MediaEntry> = emptyList(),
        val files: List<MediaEntry> = emptyList(),
        val urls: List<UrlEntry> = emptyList(),
    ) {
        val isEmpty: Boolean
            get() = images.isEmpty() && videos.isEmpty() && voice.isEmpty() && files.isEmpty() && urls.isEmpty()
    }

    // A body URL whose path ends in a known image extension is treated as an
    // image (and kept out of the URLs bucket). Matched against the URL path
    // only, so `https://x.com/page?file=cat.jpg` is a link, not an image.
    private val IMAGE_URL_EXTENSION =
        Regex("""\.(jpe?g|png|gif|webp|bmp|heic|heif|avif)$""", RegexOption.IGNORE_CASE)

    // True when the URL's path component (not its query/fragment) ends in a
    // known image extension. Unparseable or path-less URLs are not images.
    private fun isLoadableImageUrl(url: String): Boolean {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val path = uri.path
        if (path.isNullOrEmpty()) return false
        return IMAGE_URL_EXTENSION.containsMatchIn(path)
    }

    fun build(records: List<AppMessageRecordFfi>): Inventory {
        val images = ArrayList<MediaEntry>()
        val videos = ArrayList<MediaEntry>()
        val voice = ArrayList<MediaEntry>()
        val files = ArrayList<MediaEntry>()
        val urls = ArrayList<UrlEntry>()
        for (record in records) {
            val recordInventory = cachedRecordInventory(record)
            for (entry in recordInventory.media) {
                val kind = entry.kind
                bucketFor(kind, images, videos, voice, files).add(entry)
            }
            urls.addAll(recordInventory.urls)
        }
        return Inventory(images, videos, voice, files, urls)
    }

    private fun cachedRecordInventory(record: AppMessageRecordFfi): RecordInventory {
        val key = RecordCacheKey(record.messageIdHex, record.recordedAt, record.receivedAt)
        synchronized(recordCache) {
            recordCache[key]?.let { return it }
        }
        val computed = buildRecordInventory(record)
        synchronized(recordCache) {
            recordCache[key] = computed
        }
        return computed
    }

    private fun buildRecordInventory(record: AppMessageRecordFfi): RecordInventory {
        val media = ArrayList<MediaEntry>()
        val urls = ArrayList<UrlEntry>()
        for (reference in MediaReferenceParser.parseAllImetaTags(record.tags)) {
            val kind = classify(reference)
            media.add(MediaEntry(record.messageIdHex, record.sender, record.recordedAt, kind, Source.Attachment(reference)))
        }
        for (url in collectHttpUrls(record.contentTokens)) {
            if (isLoadableImageUrl(url)) {
                media.add(MediaEntry(record.messageIdHex, record.sender, record.recordedAt, Kind.IMAGE, Source.LinkedUrl(url)))
            } else {
                urls.add(UrlEntry(record.messageIdHex, record.sender, record.recordedAt, url))
            }
        }
        return RecordInventory(media, urls)
    }

    private fun classify(reference: MediaAttachmentReferenceFfi): Kind =
        when {
            MediaReferenceParser.isImageMedia(reference) -> Kind.IMAGE
            MediaReferenceParser.isVideoMedia(reference) -> Kind.VIDEO
            MediaReferenceParser.isAudioMedia(reference) -> Kind.VOICE
            else -> Kind.FILE
        }

    private fun bucketFor(
        kind: Kind,
        images: MutableList<MediaEntry>,
        videos: MutableList<MediaEntry>,
        voice: MutableList<MediaEntry>,
        files: MutableList<MediaEntry>,
    ): MutableList<MediaEntry> =
        when (kind) {
            Kind.IMAGE -> images
            Kind.VIDEO -> videos
            Kind.VOICE -> voice
            Kind.FILE -> files
        }

    // Walk the parsed markdown for absolute http(s) links (inline Link dests and
    // Autolinks), recursing nested blocks/inlines. Order is preserved so the
    // URLs tab reads chronologically. SSRF/private-host guards are applied later
    // at metadata-fetch time, not here — listing a URL is harmless.
    private fun collectHttpUrls(document: MarkdownDocumentFfi): List<String> {
        val out = ArrayList<String>()
        // Backstop below the depth caps: untrusted nesting must never crash the
        // process, even if a walker arm is later missed.
        try {
            document.blocks.forEach { collectFromBlock(it, out, depth = 0) }
        } catch (_: StackOverflowError) {
            // Keep whatever resolved before the overflow.
        }
        return out
    }

    private fun collectFromBlock(
        block: MarkdownBlockFfi,
        out: MutableList<String>,
        depth: Int,
    ) {
        if (markdownDepthExceeded(depth)) return
        when (block) {
            is MarkdownBlockFfi.Paragraph -> block.inlines.forEach { collectFromInline(it, out, depth = 0) }
            is MarkdownBlockFfi.Heading -> block.inlines.forEach { collectFromInline(it, out, depth = 0) }
            is MarkdownBlockFfi.BlockQuote -> block.blocks.forEach { collectFromBlock(it, out, depth + 1) }
            is MarkdownBlockFfi.ListBlock -> block.items.forEach { item -> item.blocks.forEach { collectFromBlock(it, out, depth + 1) } }
            is MarkdownBlockFfi.Table -> {
                block.header.forEach { cell -> cell.inlines.forEach { collectFromInline(it, out, depth = 0) } }
                block.rows.forEach { row -> row.forEach { cell -> cell.inlines.forEach { collectFromInline(it, out, depth = 0) } } }
            }
            else -> Unit
        }
    }

    private fun collectFromInline(
        inline: MarkdownInlineFfi,
        out: MutableList<String>,
        depth: Int,
    ) {
        if (markdownInlineDepthExceeded(depth)) return
        when (inline) {
            is MarkdownInlineFfi.Link -> {
                addIfHttp(inline.dest, out)
                inline.children.forEach { collectFromInline(it, out, depth + 1) }
            }
            is MarkdownInlineFfi.Autolink -> addIfHttp(inline.url, out)
            is MarkdownInlineFfi.Emph -> inline.children.forEach { collectFromInline(it, out, depth + 1) }
            is MarkdownInlineFfi.Strong -> inline.children.forEach { collectFromInline(it, out, depth + 1) }
            is MarkdownInlineFfi.Strikethrough -> inline.children.forEach { collectFromInline(it, out, depth + 1) }
            else -> Unit
        }
    }

    private fun addIfHttp(
        candidate: String,
        out: MutableList<String>,
    ) {
        val trimmed = candidate.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            out.add(trimmed)
        }
    }
}
