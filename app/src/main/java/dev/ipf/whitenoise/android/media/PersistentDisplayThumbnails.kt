@file:Suppress("TooManyFunctions") // One encrypted derivative store and its lifecycle/codec operations.

package dev.ipf.whitenoise.android.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Source identity authenticated inside each derivative, independently of its recyclable storage slot. */
internal data class DisplayThumbnailSource(
    val attachmentKey: String,
    val sourceEpoch: ULong,
    val ciphertextTag: String,
)

/**
 * Bounded encrypted derivatives of verified static pixels, never a source of original attachment availability.
 * Slots are account/chat/message/attachment/size scoped through [DisplayThumbnailSource.attachmentKey]. Their
 * authenticated headers also bind the source revision and ciphertext hash. Reusing a slot for a changed source
 * cannot return its previous pixels. Only this cache's own encoder supplies bytes to its in-process decoder.
 */
internal class PersistentDisplayThumbnails(
    private val disk: DiskByteCache,
) {
    private val generation = AtomicLong()
    private val invalidations = AtomicInteger()
    private val publicationLock = Any()

    /** Capture before reading an original, so removal during decoding also fences the eventual write. */
    fun captureGeneration(): Long = generation.get()

    /** Immediately fences producers when the main-thread owner retires presentation state. */
    fun retirePending() {
        generation.incrementAndGet()
    }

    /** Late reads and writes are expendable after any explicit cache invalidation. */
    fun isCurrent(token: Long): Boolean = invalidations.get() == 0 && token == generation.get()

    /** Hydrates the authenticated disk index off main. */
    fun prepare() = disk.prepare()

    /** Includes the encrypted envelopes in Manage Storage's temporary-copy accounting. */
    fun residentBytes(): Long = disk.residentBytes()

    /** Reads only a matching source and sufficient decode ceiling; corruption is an ordinary cache miss. */
    @Suppress("ReturnCount") // Fail closed at each provenance or invalidation boundary.
    fun read(
        source: DisplayThumbnailSource,
        requestedEdge: Int,
        token: Long,
    ): MediaThumbnail? {
        if (!isCurrent(token) || source.ciphertextTag.isBlank()) return null
        for (edge in edges.filter { it >= requestedEdge }) {
            val entry =
                disk.get(slot(source, edge))?.let { bytes ->
                    runCatching { decode(bytes, source, edge) }.getOrNull()
                } ?: continue
            if (isCurrent(token)) return entry
            entry.bitmap.recycle()
            return null
        }
        return null
    }

    /**
     * Encodes off main before taking the publication lock. The caller limits outstanding encodes to one, without
     * queuing retained bitmaps. Invalidation and publication share the lock, so even an absent slot cannot be
     * resurrected by a decode that started before removal. Displayed/shared bitmaps are never recycled here.
     */
    @Suppress("ReturnCount") // Reject ineligible pixels before encoding or publication.
    fun write(
        source: DisplayThumbnailSource,
        entry: MediaThumbnail,
        token: Long,
    ) {
        if (!isCurrent(token) || source.ciphertextTag.isBlank() || entry.maxEdgePx !in edges) return
        val bitmap = entry.bitmap
        if (bitmap.width !in 1..entry.maxEdgePx || bitmap.height !in 1..entry.maxEdgePx) return
        val bytes = encode(source, entry) ?: return
        synchronized(publicationLock) {
            if (isCurrent(token)) {
                val key = slot(source, entry.maxEdgePx)
                disk.put(key, bytes, disk.capturePublicationToken(), source.ciphertextTag)
                if (!isCurrent(token)) disk.remove(key)
            }
        }
    }

    /** Retires every size, including slots hydrated after a process restart and not currently in memory. */
    fun remove(attachmentKey: String) =
        invalidate {
            edges.forEach { disk.remove(slot(attachmentKey, it)) }
        }

    /** Uses the persisted ciphertext tags to retire derivatives of unloaded expired messages. */
    fun removeByCiphertextTags(tags: Set<String>) {
        if (tags.isNotEmpty()) invalidate { disk.removeByCiphertextTags(tags) }
    }

    /** Account teardown and Clear Cache retire both persisted data and all pending producers. */
    fun clear() = invalidate { disk.clear() }

    /** Serializes invalidation with publication, while reads authenticate without holding this lock. */
    private fun invalidate(block: () -> Unit) {
        invalidations.incrementAndGet()
        retirePending()
        try {
            synchronized(publicationLock) { block() }
        } finally {
            // Tokens captured while cleanup waited for a writer must also be obsolete after cleanup finishes.
            retirePending()
            invalidations.decrementAndGet()
        }
    }

    /** A schema-specific slot prevents ordinary original-cache envelopes from entering this decoder. */
    private fun slot(
        source: DisplayThumbnailSource,
        edge: Int,
    ): String = slot(source.attachmentKey, edge)

    /** Keeps all physical size slots enumerable without persisting a parallel identity index. */
    private fun slot(
        key: String,
        edge: Int,
    ): String = "$key:display-thumbnail-v1:$edge"

    /** Encodes only already-verified pixels; alpha is lossless and opaque display pixels use JPEG quality 85. */
    private fun encode(
        source: DisplayThumbnailSource,
        entry: MediaThumbnail,
    ): ByteArray? {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).apply {
            writeInt(MAGIC)
            writeLong(source.sourceEpoch.toLong())
            writeUTF(source.ciphertextTag)
            writeInt(entry.maxEdgePx)
            writeInt(entry.bitmap.width)
            writeInt(entry.bitmap.height)
        }
        val format = displayThumbnailEncodingFormat(entry.bitmap)
        if (!entry.bitmap.compress(format, QUALITY, output)) return null
        return output.toByteArray().takeIf { it.size <= MAX_ENTRY_BYTES }
    }

    /** Validates authenticated provenance and dimensions before allocating a bounded output bitmap. */
    @Suppress("ReturnCount") // Validate each authenticated field before allocating pixels.
    private fun decode(bytes: ByteArray, source: DisplayThumbnailSource, edge: Int): MediaThumbnail? {
        val input = ByteArrayInputStream(bytes)
        val header = DataInputStream(input)
        if (header.readInt() != MAGIC || header.readLong().toULong() != source.sourceEpoch) return null
        if (header.readUTF() != source.ciphertextTag || header.readInt() != edge) return null
        val width = header.readInt()
        val height = header.readInt()
        if (width !in 1..edge || height !in 1..edge) return null
        val offset = bytes.size - input.available()
        val length = input.available()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, offset, length, bounds)
        if (bounds.outWidth != width || bounds.outHeight != height) return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, offset, length) ?: return null
        return MediaThumbnail(bitmap, edge)
    }

    companion object {
        const val MAX_BYTES = 32L * 1024 * 1024
        const val MAX_ENTRY_BYTES = 8 * 1024 * 1024
        private const val MAGIC = 0x574e5401
        private const val QUALITY = 85
        private val edges = intArrayOf(128, 256, 384, 512, 768, 1024, MediaPipeline.THUMBNAIL_MAX_EDGE_PX)
    }
}

/**
 * The isolated worker returns ARGB_8888 pixels with alpha capability even for JPEGs. Inspect actual alpha off main
 * using one bounded row, rather than treating that storage capability as evidence of transparent content.
 */
@Suppress("ReturnCount") // Opaque metadata fast path, transparent pixel early exit, then fully opaque result.
internal fun displayThumbnailEncodingFormat(bitmap: Bitmap): Bitmap.CompressFormat {
    if (!bitmap.hasAlpha()) return Bitmap.CompressFormat.JPEG
    val row = IntArray(bitmap.width)
    for (y in 0 until bitmap.height) {
        bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
        if (row.any { it ushr DISPLAY_ALPHA_SHIFT != DISPLAY_OPAQUE_ALPHA }) return Bitmap.CompressFormat.WEBP_LOSSLESS
    }
    return Bitmap.CompressFormat.JPEG
}

private const val DISPLAY_ALPHA_SHIFT = 24
private const val DISPLAY_OPAQUE_ALPHA = 255
