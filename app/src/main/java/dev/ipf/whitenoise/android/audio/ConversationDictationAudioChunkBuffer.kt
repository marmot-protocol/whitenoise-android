package dev.ipf.whitenoise.android.audio

import java.util.ArrayDeque

private const val DEFAULT_DICTATION_CHUNK_BYTES = 960_000
private const val DEFAULT_DICTATION_BUFFER_BYTES = 2_880_000

/** One immutable, non-overlapping caller-audio segment owned by a logical dictation session. */
internal data class ConversationDictationAudioChunk(
    val sessionId: Long,
    val chunkId: Long,
    val firstSample: Long,
    val lastSampleExclusive: Long,
    val hasSpeech: Boolean,
    val pcm: ByteArray,
)

/**
 * Thread-safe bounded storage between continuous microphone capture and serial provider requests.
 *
 * Bytes remain accounted while queued or in flight and are released only after a final result is
 * acknowledged. A rejected append changes no state, so callers can stop capture visibly instead of
 * silently dropping a partial read.
 */
internal class ConversationDictationAudioChunkBuffer(
    private val sessionId: Long,
    private val chunkBytes: Int = DEFAULT_DICTATION_CHUNK_BYTES,
    private val maxBufferedBytes: Int = DEFAULT_DICTATION_BUFFER_BYTES,
    private val minimumTailBytes: Int = 0,
) {
    private val queued = ArrayDeque<ConversationDictationAudioChunk>()
    private val inFlight = mutableMapOf<Long, ConversationDictationAudioChunk>()
    private var current = ByteArray(chunkBytes)
    private var currentSize = 0
    private var currentHasSpeech = false
    private var nextChunkId = 0L
    private var nextSample = 0L
    private var finished = false

    var bufferedBytes: Int = 0
        private set

    val hasPending: Boolean
        @Synchronized get() = currentSize > 0 || queued.isNotEmpty() || inFlight.isNotEmpty()

    init {
        require(chunkBytes > 0 && chunkBytes % 2 == 0)
        require(maxBufferedBytes >= chunkBytes && maxBufferedBytes % 2 == 0)
        require(minimumTailBytes in 0..chunkBytes && minimumTailBytes % 2 == 0)
    }

    /** Appends one complete PCM16 read, or rejects all of it when the bounded backlog is full. */
    @Synchronized
    fun append(
        source: ByteArray,
        length: Int,
        hasSpeech: Boolean,
    ): Boolean {
        require(length in 0..source.size)
        require(length % 2 == 0)
        if (finished || bufferedBytes + length > maxBufferedBytes) return false
        var sourceOffset = 0
        while (sourceOffset < length) {
            val copied = minOf(length - sourceOffset, current.size - currentSize)
            source.copyInto(current, currentSize, sourceOffset, sourceOffset + copied)
            currentSize += copied
            sourceOffset += copied
            currentHasSpeech = currentHasSpeech || hasSpeech
            if (currentSize == current.size) sealCurrent()
        }
        bufferedBytes += length
        return true
    }

    /** Seals the final short chunk. Repeated finish requests do not duplicate it. */
    @Synchronized
    fun finish() {
        if (finished) return
        finished = true
        coalesceFinalTail()
        if (currentSize > 0) sealCurrent()
    }

    /** Seals an utterance boundary once enough PCM exists to avoid rapid tiny provider requests. */
    @Synchronized
    fun sealCurrentIfAtLeast(minimumBytes: Int): Boolean {
        require(minimumBytes > 0 && minimumBytes % 2 == 0)
        if (finished || currentSize < minimumBytes) return false
        sealCurrent()
        return true
    }

    /** Holds the newest boundary until a useful successor exists or capture finishes. */
    @Synchronized
    fun poll(): ConversationDictationAudioChunk? {
        val boundaryHeld = !finished && queued.size == 1 && currentSize < minimumTailBytes
        return if (inFlight.isNotEmpty() || boundaryHeld) {
            null
        } else {
            queued.pollFirst()?.also { chunk -> inFlight[chunk.chunkId] = chunk }
        }
    }

    /** A final chunk is proven only after capture seals and no later PCM can extend its input. */
    @Synchronized
    fun isFinalChunk(chunkId: Long): Boolean =
        when {
            !finished || currentSize != 0 || queued.isNotEmpty() -> false
            else -> inFlight.size == 1 && inFlight.containsKey(chunkId)
        }

    /** Releases one successfully transcribed chunk exactly once. */
    @Synchronized
    fun acknowledge(chunkId: Long): Boolean {
        val chunk = inFlight.remove(chunkId) ?: return false
        bufferedBytes -= chunk.pcm.size
        return true
    }

    /** Returns a failed in-flight chunk to the front without changing byte accounting or identity. */
    @Synchronized
    fun retry(chunkId: Long): Boolean {
        val chunk = inFlight.remove(chunkId) ?: return false
        queued.addFirst(chunk)
        return true
    }

    /**
     * Requeues one rejected chunk after extending it with contiguous audio captured after it.
     *
     * Some providers classify a short speech-bearing chunk as no-speech. Retrying the identical
     * bytes can then block every later chunk. Coalescing preserves sample order and byte accounting
     * while giving the provider more context, up to the normal maximum chunk size.
     */
    @Synchronized
    fun retryWithFollowingAudio(chunkId: Long): Boolean {
        val original = inFlight.remove(chunkId) ?: return false
        if (currentSize > 0 && !finished) sealCurrent()
        var merged = original
        while (merged.pcm.size < chunkBytes && queued.isNotEmpty()) {
            val following = queued.removeFirst()
            if (following.firstSample != merged.lastSampleExclusive) {
                queued.addFirst(following)
                break
            }
            val consumedBytes = minOf(chunkBytes - merged.pcm.size, following.pcm.size)
            val combined = ByteArray(merged.pcm.size + consumedBytes)
            merged.pcm.copyInto(combined)
            following.pcm.copyInto(combined, destinationOffset = merged.pcm.size, endIndex = consumedBytes)
            merged =
                merged.copy(
                    lastSampleExclusive = merged.lastSampleExclusive + consumedBytes / 2L,
                    hasSpeech = merged.hasSpeech || following.hasSpeech,
                    pcm = combined,
                )
            if (consumedBytes < following.pcm.size) {
                queued.addFirst(
                    following.copy(
                        firstSample = following.firstSample + consumedBytes / 2L,
                        pcm = following.pcm.copyOfRange(consumedBytes, following.pcm.size),
                    ),
                )
            }
        }
        queued.addFirst(merged)
        return true
    }

    /** Clears volatile audio after cancellation or an unrecoverable provider failure. */
    @Synchronized
    fun discard() {
        queued.forEach { it.pcm.fill(0) }
        inFlight.values.forEach { it.pcm.fill(0) }
        current.fill(0)
        queued.clear()
        inFlight.clear()
        currentSize = 0
        currentHasSpeech = false
        bufferedBytes = 0
        finished = true
    }

    /**
     * A stop just after a sentence boundary must not send a context-free subsecond request.
     * The last unclaimed chunk is still ours: merge its short tail, or rebalance a full chunk
     * into two bounded requests. No samples are discarded, replayed or classified as silence.
     */
    private fun coalesceFinalTail() {
        if (currentSize == 0 || currentSize >= minimumTailBytes || queued.isEmpty()) return
        val previous = queued.removeLast()
        val combined = ByteArray(previous.pcm.size + currentSize)
        previous.pcm.copyInto(combined)
        current.copyInto(combined, destinationOffset = previous.pcm.size, endIndex = currentSize)
        val hasSpeech = previous.hasSpeech || currentHasSpeech
        val prefixBytes = if (combined.size <= chunkBytes) combined.size else combined.size - minimumTailBytes
        queued.addLast(
            previous.copy(
                lastSampleExclusive = previous.firstSample + prefixBytes / 2L,
                hasSpeech = hasSpeech,
                pcm = combined.copyOf(prefixBytes),
            ),
        )
        nextSample = previous.firstSample + prefixBytes / 2L
        current.fill(0)
        currentSize = combined.size - prefixBytes
        combined.copyInto(current, startIndex = prefixBytes)
        // Conservatively retain speech evidence after rebalancing; never infer silence from duration.
        currentHasSpeech = currentSize > 0 && hasSpeech
        previous.pcm.fill(0)
        combined.fill(0)
    }

    /** Copies the partial buffer into an immutable chunk and advances its non-overlapping sample range. */
    @Synchronized
    private fun sealCurrent() {
        val pcm = current.copyOf(currentSize)
        val firstSample = nextSample
        val sampleCount = pcm.size / 2L
        queued.addLast(
            ConversationDictationAudioChunk(
                sessionId = sessionId,
                chunkId = ++nextChunkId,
                firstSample = firstSample,
                lastSampleExclusive = firstSample + sampleCount,
                hasSpeech = currentHasSpeech,
                pcm = pcm,
            ),
        )
        nextSample += sampleCount
        current = ByteArray(chunkBytes)
        currentSize = 0
        currentHasSpeech = false
    }
}
