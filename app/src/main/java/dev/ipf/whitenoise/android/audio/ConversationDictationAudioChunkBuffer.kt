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

    /** Moves the oldest sealed chunk into the in-flight set. */
    @Synchronized
    fun poll(): ConversationDictationAudioChunk? =
        if (inFlight.isNotEmpty()) {
            null
        } else {
            queued.pollFirst()?.also { chunk -> inFlight[chunk.chunkId] = chunk }
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
        queued.clear()
        inFlight.clear()
        currentSize = 0
        currentHasSpeech = false
        bufferedBytes = 0
        finished = true
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
