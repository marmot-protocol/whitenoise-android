@file:Suppress("TooManyFunctions") // One encrypted derivative store and its lifecycle/codec operations.

package dev.ipf.whitenoise.android.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Source identity authenticated inside each derivative, independently of its recyclable storage slot. */
internal data class DisplayThumbnailSource(
    val attachmentKey: String,
    val sourceEpoch: ULong,
    val ciphertextTag: String,
)

/** A source-scoped publication fence retained only while a reader or encoder owns it. */
internal class DisplayThumbnailToken internal constructor(
    internal val source: DisplayThumbnailSource,
    internal val lifetime: Long,
    private val revision: AtomicLong,
    private val capturedRevision: Long,
    internal val admitted: Boolean,
) {
    /** A matching source removal retires this token without affecting another attachment. */
    internal fun sourceCurrent(): Boolean = revision.get() == capturedRevision
}

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
    private val requestLock = Any()
    private val publicationLock = Any()
    private val sourceRevisions = mutableMapOf<DisplayThumbnailSource, WeakReference<AtomicLong>>()
    private val removingKeys = mutableMapOf<String, Int>()
    private val removingTags = mutableMapOf<String, Int>()

    /** Captures before an original read; weak revision cells disappear after their last request is released. */
    fun captureGeneration(source: DisplayThumbnailSource): DisplayThumbnailToken =
        synchronized(requestLock) {
            sourceRevisions.entries.removeAll { it.value.get() == null }
            val revision =
                sourceRevisions[source]?.get() ?: AtomicLong().also {
                    sourceRevisions[source] = WeakReference(it)
                }
            DisplayThumbnailToken(
                source,
                generation.get(),
                revision,
                revision.get(),
                invalidations.get() == 0 && source.attachmentKey !in removingKeys && source.ciphertextTag !in removingTags,
            )
        }

    /** Retires a source's pending work, or the whole session when [attachmentKey] is absent. */
    fun retirePending(attachmentKey: String? = null) {
        synchronized(requestLock) {
            if (attachmentKey == null) {
                generation.incrementAndGet()
            } else {
                retireSources { it.attachmentKey == attachmentKey }
            }
        }
    }

    /** Matching removals and session teardown fence a reader or encoder; unrelated removals preserve it. */
    fun isCurrent(token: DisplayThumbnailToken): Boolean =
        token.admitted && invalidations.get() == 0 && token.lifetime == generation.get() && token.sourceCurrent()

    /** Hydrates the authenticated disk index off main. */
    fun prepare() = disk.prepare()

    /** Includes the encrypted envelopes in Manage Storage's temporary-copy accounting. */
    fun residentBytes(): Long = disk.residentBytes()

    /** Reads only a matching source and sufficient decode ceiling; corruption is an ordinary cache miss. */
    @Suppress("ReturnCount") // Fail closed at each provenance or invalidation boundary.
    fun read(
        source: DisplayThumbnailSource,
        requestedEdge: Int,
        token: DisplayThumbnailToken,
    ): MediaThumbnail? {
        if (token.source != source || !isCurrent(token) || source.ciphertextTag.isBlank()) return null
        for (edge in thumbnailDecodeBuckets.filter { it >= requestedEdge }) {
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
        token: DisplayThumbnailToken,
        canPublish: () -> Boolean = { true },
    ) {
        if (token.source != source ||
            !isCurrent(token) ||
            source.ciphertextTag.isBlank() ||
            entry.maxEdgePx !in thumbnailDecodeBuckets
        ) {
            return
        }
        val bitmap = entry.bitmap
        if (bitmap.width !in 1..entry.maxEdgePx || bitmap.height !in 1..entry.maxEdgePx) return
        val bytes = encode(source, entry) ?: return
        synchronized(publicationLock) {
            if (isCurrent(token) && canPublish()) {
                val key = slot(source, entry.maxEdgePx)
                disk.put(key, bytes, disk.capturePublicationToken(), source.ciphertextTag)
                if (!isCurrent(token) || !canPublish()) disk.remove(key)
            }
        }
    }

    /** Retires every size, including slots hydrated after a process restart and not currently in memory. */
    fun remove(attachmentKey: String) =
        invalidate(attachmentKey = attachmentKey) {
            thumbnailDecodeBuckets.forEach { disk.remove(slot(attachmentKey, it)) }
        }

    /** Uses the persisted ciphertext tags to retire derivatives of unloaded expired messages. */
    fun removeByCiphertextTags(tags: Set<String>) {
        if (tags.isNotEmpty()) invalidate(tags = tags) { disk.removeByCiphertextTags(tags) }
    }

    /** Account teardown and Clear Cache retire both persisted data and all pending producers. */
    fun clear() = invalidate { disk.clear() }

    /** Fences matching requests before disk work and again after it, including requests admitted during removal. */
    private fun invalidate(
        attachmentKey: String? = null,
        tags: Set<String> = emptySet(),
        block: () -> Unit,
    ) {
        val global = attachmentKey == null && tags.isEmpty()
        val matches: (DisplayThumbnailSource) -> Boolean = {
            it.attachmentKey == attachmentKey || it.ciphertextTag in tags
        }
        synchronized(requestLock) {
            if (global) {
                invalidations.incrementAndGet()
                generation.incrementAndGet()
            } else {
                attachmentKey?.let { removingKeys[it] = removingKeys.getOrDefault(it, 0) + 1 }
                tags.forEach { removingTags[it] = removingTags.getOrDefault(it, 0) + 1 }
                retireSources(matches)
            }
        }
        try {
            synchronized(publicationLock) { block() }
        } finally {
            synchronized(requestLock) {
                if (global) {
                    generation.incrementAndGet()
                    invalidations.decrementAndGet()
                } else {
                    retireSources(matches)
                    attachmentKey?.let { endRemoval(removingKeys, it) }
                    tags.forEach { endRemoval(removingTags, it) }
                }
            }
        }
    }

    /** Revision cells are weak so removed sources cannot accumulate as permanent generation tombstones. */
    private fun retireSources(matches: (DisplayThumbnailSource) -> Boolean) {
        sourceRevisions.entries.removeAll { (source, reference) ->
            val revision = reference.get()
            if (matches(source)) revision?.incrementAndGet()
            revision == null
        }
    }

    /** Overlapping removals retain the admission fence until their final owner finishes. */
    private fun endRemoval(
        removals: MutableMap<String, Int>,
        key: String,
    ) {
        val count = removals.getValue(key) - 1
        if (count == 0) removals.remove(key) else removals[key] = count
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
