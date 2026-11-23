@file:Suppress("TooManyFunctions") // One encrypted derivative store and its lifecycle/codec operations.

package dev.ipf.whitenoise.android.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
    val sourceMessageIdHex: String? = null,
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
    private val decodePixels: (ByteArray, Int, Int) -> Bitmap? = { bytes, offset, length ->
        BitmapFactory.decodeByteArray(bytes, offset, length)
    },
) {
    private val removalsCompleted = MutableStateFlow(0L)
    private val generation = AtomicLong()
    private val invalidations = AtomicInteger()
    private val requestLock = Any()
    private val publicationLock = Any()
    private val sourceRevisions = mutableMapOf<DisplayThumbnailSource, WeakReference<AtomicLong>>()
    private val removingKeys = mutableMapOf<String, Int>()
    private val removingTags = mutableMapOf<String, Int>()
    private val removingMessages = mutableMapOf<String, Int>()
    private val queuedMessagePrefixes = mutableSetOf<String>()

    // Logical slot -> authenticated source. Payloads are decrypted to fill it only once per process; writes add to it
    // and lookups drop slots the disk no longer holds, which covers removal, LRU eviction and discard.
    private val sourceIndex = mutableMapOf<String, DisplayThumbnailSource>()
    private val indexScanLock = Any()
    private var indexed = false
    private val messageSweepRunning =
        java.util.concurrent.atomic
            .AtomicBoolean()

    /** Elapsed-realtime instant of the last complete native reconciliation in this process, if any. */
    @Volatile
    var reconciledAtElapsedMillis: Long? = null

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
                invalidations.get() == 0 &&
                    source.attachmentKey !in removingKeys &&
                    removingMessages.keys.none { source.attachmentKey.startsWith(it, ignoreCase = true) } &&
                    source.ciphertextTag !in removingTags,
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

    /** Retires every pending producer before a deleted message's asynchronous disk cleanup is dispatched. */
    fun retireMessagePending(attachmentKeyPrefix: String) {
        synchronized(requestLock) {
            retireSources { it.attachmentKey.startsWith(attachmentKeyPrefix, ignoreCase = true) }
        }
    }

    /** Matching removals and session teardown fence a reader or encoder; unrelated removals preserve it. */
    fun isCurrent(token: DisplayThumbnailToken): Boolean =
        token.admitted && invalidations.get() == 0 && token.lifetime == generation.get() && token.sourceCurrent()

    /** Suspends a retired loader until matching cleanup permits a new request, without polling native state. */
    suspend fun awaitRemovals(source: DisplayThumbnailSource) {
        removalsCompleted.first { captureGeneration(source).admitted }
    }

    /** Hydrates the authenticated disk index and the source index off main. */
    fun prepare() {
        disk.prepare()
        storedSources()
    }

    /** Enumerates the authenticated sources still on disk; only the first call in a process decrypts payloads. */
    fun storedSources(): Set<DisplayThumbnailSource> {
        indexStoredSources()
        val snapshot = synchronized(sourceIndex) { sourceIndex.toMap() }
        val gone = snapshot.keys.filterNot(disk::containsAfterHydration)
        synchronized(sourceIndex) { gone.filterNot(disk::contains).forEach(sourceIndex::remove) }
        return (snapshot - gone.toSet()).values.toSet()
    }

    /** Reads every header once, discarding legacy or malformed derivatives; later writes keep the index current. */
    private fun indexStoredSources() =
        synchronized(indexScanLock) {
            if (indexed) return@synchronized
            val scanned = mutableMapOf<String, DisplayThumbnailSource>()
            disk.forEachStoredEntry { stored, bytes ->
                val header = runCatching { header(bytes) }.getOrNull()
                if (header == null) {
                    disk.discardStoredEntryIfMatches(stored, bytes)
                } else {
                    scanned[slot(header.source, header.edge)] = header.source
                }
            }
            // A write that landed during the scan already recorded its newer source.
            synchronized(sourceIndex) { scanned.forEach(sourceIndex::putIfAbsent) }
            indexed = true
        }

    /** Records a deleted message for the next sweep; it costs no disk read, so it is safe under a commit lock. */
    fun queueMessageRemoval(attachmentKeyPrefix: String) {
        synchronized(queuedMessagePrefixes) { queuedMessagePrefixes += attachmentKeyPrefix }
    }

    /**
     * Removes every size/index of each queued message, fencing writers that have yet to publish a slot. A caller that
     * finds a sweep running returns at once: that sweep drains the queue, this caller's message included. Its pixels
     * and pending producers were already fenced when the message was queued. A batch that fails stays queued for the
     * next sweep, which the next delete or reconciliation pass starts.
     */
    fun removeQueuedMessages() {
        // One caller drains for everyone; the rest return at once instead of parking an IO thread on a lock while
        // the cache's one-time startup scan runs. Rechecking after release catches a message queued in between.
        while (messageSweepRunning.compareAndSet(false, true)) {
            try {
                while (true) {
                    val prefixes = takeQueuedMessages()
                    if (prefixes.isEmpty()) break
                    removeMessageBatch(prefixes)
                }
            } finally {
                messageSweepRunning.set(false)
            }
            if (synchronized(queuedMessagePrefixes) { queuedMessagePrefixes.isEmpty() }) return
        }
    }

    /** Removes one taken batch; a failed batch, other callers' messages included, is queued again for a later sweep. */
    private fun removeMessageBatch(prefixes: Set<String>) {
        var removed = false
        try {
            invalidate(messagePrefixes = prefixes) { removeMessageSlots(prefixes) }
            removed = true
        } finally {
            if (!removed) synchronized(queuedMessagePrefixes) { queuedMessagePrefixes += prefixes }
        }
    }

    /** Removes every stored size of each source under [prefixes]; a lookup in the source index, not a scan. */
    private fun removeMessageSlots(prefixes: Set<String>) =
        storedSources()
            .filter { source -> prefixes.any { source.attachmentKey.startsWith(it, ignoreCase = true) } }
            .forEach { source -> thumbnailDecodeBuckets.forEach { disk.remove(slot(source, it)) } }

    /** Takes every queued deleted-message prefix at once, so concurrent deletes share one sweep. */
    private fun takeQueuedMessages(): Set<String> =
        synchronized(queuedMessagePrefixes) {
            queuedMessagePrefixes.toSet().also { queuedMessagePrefixes.clear() }
        }

    /** Authenticates source and bounds without allocating another decoded bitmap. */
    fun hasMatching(
        source: DisplayThumbnailSource,
        requestedEdge: Int,
        token: DisplayThumbnailToken,
    ): Boolean {
        if (token.source != source || !isCurrent(token)) return false
        return thumbnailDecodeBuckets.filter { it >= requestedEdge }.any { edge ->
            val header = disk.get(slot(source, edge))?.let { runCatching { header(it) }.getOrNull() }
            header != null &&
                header.source.copy(sourceMessageIdHex = source.sourceMessageIdHex) == source &&
                header.edge == edge &&
                isCurrent(token)
        }
    }

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
            val key = slot(source, edge)
            val bytes = disk.get(key)
            if (bytes != null) {
                val header = runCatching { header(bytes) }.getOrNull()
                val matchesSource = header?.source?.copy(sourceMessageIdHex = source.sourceMessageIdHex) == source
                if (header != null && (!matchesSource || header.edge != edge)) {
                    continue
                }
                val entry = header?.let { runCatching { decode(bytes, it) }.getOrNull() }
                if (entry == null) {
                    synchronized(publicationLock) { disk.discardIfMatches(key, bytes) }
                } else {
                    if (isCurrent(token)) return entry
                    entry.bitmap.recycle()
                    return null
                }
            }
        }
        return null
    }

    /**
     * Encodes off main before taking the publication lock. The caller limits outstanding encodes to one, without
     * queuing retained bitmaps. Invalidation and publication share the lock, so even an absent slot cannot be
     * resurrected by a decode that started before removal. Displayed/shared bitmaps are never recycled here.
     */
    @Suppress("ReturnCount", "LongParameterList") // Source descriptor and independent publication lifetime fences.
    fun write(
        source: DisplayThumbnailSource,
        entry: MediaThumbnail,
        token: DisplayThumbnailToken,
        sourceMessageIdHex: String? = source.sourceMessageIdHex,
        canPublish: () -> Boolean = { true },
    ) {
        if (token.source != source || !isCurrent(token) || source.ciphertextTag.isBlank()) return
        val bitmap = entry.bitmap
        if (entry.maxEdgePx !in thumbnailDecodeBuckets ||
            bitmap.width !in 1..entry.maxEdgePx ||
            bitmap.height !in 1..entry.maxEdgePx
        ) {
            return
        }
        val bytes = encode(source, entry, sourceMessageIdHex) ?: return
        synchronized(publicationLock) {
            if (isCurrent(token) && canPublish()) {
                val key = slot(source, entry.maxEdgePx)
                disk.put(key, bytes, disk.capturePublicationToken(), source.ciphertextTag)
                // Index the resolved source the header carries, so the slot maps to one id before and after restart.
                synchronized(sourceIndex) { sourceIndex[key] = source.copy(sourceMessageIdHex = sourceMessageIdHex) }
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
    fun clear(onFailure: (Throwable) -> Unit = {}) =
        invalidate {
            disk.clear(onFailure)
            synchronized(sourceIndex) { sourceIndex.clear() }
        }

    /** Fences matching requests before disk work and again after it, including requests admitted during removal. */
    private fun invalidate(
        attachmentKey: String? = null,
        tags: Set<String> = emptySet(),
        messagePrefixes: Set<String> = emptySet(),
        block: () -> Unit,
    ) {
        val global = attachmentKey == null && tags.isEmpty() && messagePrefixes.isEmpty()
        val matches: (DisplayThumbnailSource) -> Boolean = {
            it.attachmentKey == attachmentKey ||
                it.ciphertextTag in tags ||
                messagePrefixes.any { prefix -> it.attachmentKey.startsWith(prefix, ignoreCase = true) }
        }
        synchronized(requestLock) {
            if (global) {
                invalidations.incrementAndGet()
                generation.incrementAndGet()
            } else {
                attachmentKey?.let { removingKeys[it] = removingKeys.getOrDefault(it, 0) + 1 }
                messagePrefixes.forEach { removingMessages[it] = removingMessages.getOrDefault(it, 0) + 1 }
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
                    messagePrefixes.forEach { endRemoval(removingMessages, it) }
                    tags.forEach { endRemoval(removingTags, it) }
                }
                removalsCompleted.value += 1L
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
        sourceMessageIdHex: String?,
    ): ByteArray? {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).apply {
            writeInt(MAGIC)
            writeUTF(source.attachmentKey)
            writeUTF(sourceMessageIdHex.orEmpty())
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

    private data class Header(
        val source: DisplayThumbnailSource,
        val edge: Int,
        val offset: Int,
        val length: Int,
    )

    /** Parses authenticated provenance and verified codec dimensions without allocating pixel storage. */
    @Suppress("ReturnCount")
    private fun header(bytes: ByteArray): Header? {
        val input = ByteArrayInputStream(bytes)
        val fields = DataInputStream(input)
        if (fields.readInt() != MAGIC) return null
        val key = fields.readUTF()
        val sourceId = fields.readUTF().ifEmpty { null }
        val source = DisplayThumbnailSource(key, fields.readLong().toULong(), fields.readUTF(), sourceId)
        val edge = fields.readInt()
        val width = fields.readInt()
        val height = fields.readInt()
        if (edge !in thumbnailDecodeBuckets || width !in 1..edge || height !in 1..edge) return null
        val offset = bytes.size - input.available()
        val length = input.available()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, offset, length, bounds)
        if (bounds.outWidth != width || bounds.outHeight != height) return null
        return Header(source, edge, offset, length)
    }

    /** Allocates pixels only after authenticating source identity and the requested decode ceiling. */
    private fun decode(
        bytes: ByteArray,
        header: Header,
    ): MediaThumbnail? {
        val bitmap = decodePixels(bytes, header.offset, header.length) ?: return null
        return MediaThumbnail(bitmap, header.edge)
    }

    companion object {
        const val MAX_BYTES = 32L * 1024 * 1024
        const val MAX_ENTRY_BYTES = 8 * 1024 * 1024
        private const val MAGIC = 0x574e5402
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
