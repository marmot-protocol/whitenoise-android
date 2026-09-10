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

    /** Clears volatile audio after cancellation or an unrecoverable provider failure. */
    @Synchronized
    fun discard() {
        queued.clear()
        inFlight.clear()
        currentSize = 0
        bufferedBytes = 0
        finished = true
    }

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
                pcm = pcm,
            ),
        )
        nextSample += sampleCount
        current = ByteArray(chunkBytes)
        currentSize = 0
    }
}
