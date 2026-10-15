package dev.ipf.whitenoise.android.audio

/** Recognized PCM context and exact provider-request settlement over one synchronized capture buffer. */
internal class ConversationDictationAudioRequestLedger(
    private val buffer: ConversationDictationAudioChunkBuffer,
) {
    private data class Anchor(
        val spanId: Long,
        val transcript: String,
        val parts: List<ConversationDictationAudioChunk>,
    ) {
        val bytes: Int get() = parts.sumOf { it.pcm.size }
    }

    private var anchor: Anchor? = null
    private var contextChunkId: Long? = null

    /** Claims one new chunk with its exact optional replay span. */
    fun pollRequest(): ConversationDictationAudioRequest? = synchronized(buffer) {
        val chunk = buffer.poll() ?: return@synchronized null
        val previous = anchor?.takeIf { contextChunkId == chunk.chunkId }
        ConversationDictationAudioRequest(chunk, previous?.parts.orEmpty(), previous?.spanId)
    }

    /** Read-only admission lets the feeder retire its lease before requeueing. */
    fun canRetryWithContext(request: ConversationDictationAudioRequest): Boolean = synchronized(buffer) {
        val previous = anchor
        val fits = previous != null && request.replacedSpanId == null &&
            previous.bytes + request.chunk.pcm.size <= buffer.requestBytes
        fits && buffer.inFlight[request.chunk.chunkId] === request.chunk
    }

    /** Only a whole recognized span can be replayed inside the provider request ceiling. */
    fun retryWithContext(request: ConversationDictationAudioRequest): Boolean = synchronized(buffer) {
        if (!canRetryWithContext(request)) return@synchronized false
        contextChunkId = request.chunk.chunkId
        buffer.retry(request.chunk.chunkId)
    }

    /** Settles new coverage and either replaces the whole span or preserves exact unchanged replay. */
    fun acknowledge(
        request: ConversationDictationAudioRequest,
        transcript: String,
    ): ConversationDictationAudioSettlement? = synchronized(buffer) {
        val chunk = buffer.inFlight[request.chunk.chunkId] ?: return@synchronized null
        if (chunk !== request.chunk || transcript.isBlank() || !matchesReplay(request)) {
            return@synchronized null
        }
        buffer.inFlight.remove(chunk.chunkId)
        val normalized = transcript.trim()
        val previous = anchor
        val unchanged = previous != null && request.replacedSpanId != null && normalized == previous.transcript
        val latest = if (unchanged) requireNotNull(previous) else Anchor(chunk.chunkId, normalized, request.pcmParts)
        releaseCoveredPcm(previous, latest, chunk, unchanged)
        anchor = latest
        contextChunkId = null
        buffer.bufferedBytes -=
            if (unchanged) chunk.pcm.size
            else if (request.replacedSpanId == null) previous?.bytes ?: 0
            else 0
        ConversationDictationAudioSettlement(latest.spanId, request.replacedSpanId, normalized)
    }

    /** Ordinary acknowledgment or forward coalescing cannot keep a rejected context claim. */
    fun cancelContext(chunkId: Long? = null) = synchronized(buffer) {
        if (chunkId == null || contextChunkId == chunkId) contextChunkId = null
    }

    /** Volatile recognized context is wiped with the capture it belongs to. */
    fun discard() = synchronized(buffer) {
        anchor?.parts?.forEach { it.pcm.fill(0) }
        anchor = null
        contextChunkId = null
    }

    private fun matchesReplay(request: ConversationDictationAudioRequest): Boolean {
        val replacement = request.replacedSpanId
        if (replacement == null) return contextChunkId != request.chunk.chunkId
        val previous = anchor
        val matchingAnchor = previous != null && previous.spanId == replacement && previous.parts == request.replayParts
        return matchingAnchor && contextChunkId == request.chunk.chunkId
    }

    private fun releaseCoveredPcm(
        previous: Anchor?,
        latest: Anchor,
        chunk: ConversationDictationAudioChunk,
        unchanged: Boolean,
    ) {
        if (unchanged) {
            chunk.pcm.fill(0)
        } else {
            previous?.parts?.filterNot { retained -> latest.parts.any { it === retained } }
                ?.forEach { it.pcm.fill(0) }
        }
    }
}
