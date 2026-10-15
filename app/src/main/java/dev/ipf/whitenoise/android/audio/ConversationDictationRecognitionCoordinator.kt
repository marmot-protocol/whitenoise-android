package dev.ipf.whitenoise.android.audio

/** Main-thread owner of recognized spans and the retry budget of one newly captured input. */
internal class ConversationDictationRecognitionCoordinator {
    internal sealed interface FailureResolution {
        data object ConfirmedDigitalSilence : FailureResolution
        data object Exhausted : FailureResolution
        data class Retained(val requeued: Boolean) : FailureResolution
    }
    internal data class Span(val id: Long, val text: String)

    internal data class Snapshot(val spans: List<Span>, val nextProviderSpanId: Long)

    private val spans = mutableListOf<Span>()
    private var nextProviderSpanId = -1L
    private var retry: Retry? = null

    private data class Retry(
        val chunkId: Long?,
        var version: String?,
        var attempts: Int = 0,
        var capacityAttempts: Int = 0,
        var contextAttempted: Boolean = false,
    )

    val transcript: String
        get() = spans.fold("") { text, span -> appendConversationDictationSegment(text, span.text) }

    /** Useful provisional text on a failed PCM settlement never changes recognized ownership. */
    fun provisionalTranscript(request: ConversationDictationAudioRequest?, text: String): String {
        val retained = if (request?.replacedSpanId == spans.lastOrNull()?.id) spans.dropLast(1) else spans
        val prefix = retained.fold("") { current, span -> appendConversationDictationSegment(current, span.text) }
        return appendConversationDictationSegment(prefix, text)
    }

    fun canAccept(request: ConversationDictationAudioRequest): Boolean =
        request.replacedSpanId == null || spans.lastOrNull()?.id == request.replacedSpanId

    /** Context replaces exactly the last recognized span, including corrections and punctuation. */
    fun accept(settlement: ConversationDictationAudioSettlement): Boolean {
        val replacement = settlement.replacedSpanId
        if (replacement != null && spans.lastOrNull()?.id != replacement) return false
        if (replacement != null) spans.removeAt(spans.lastIndex)
        spans += Span(settlement.spanId, settlement.transcript)
        retry = null
        return true
    }

    fun acceptProviderMicrophone(text: String) {
        spans += Span(nextProviderSpanId--, text.trim())
        retry = null
    }

    /** A changed request version does not grant fresh attempts to the same unresolved input. */
    fun recordFailure(
        chunkId: Long?,
        version: String?,
        capacity: Boolean,
    ): Int {
        val current = retry?.takeIf { it.chunkId == chunkId } ?: Retry(chunkId, version).also { retry = it }
        if (current.version != version) {
            // A changed byte request keeps its semantic failure budget but starts fresh capacity pacing.
            current.version = version
            current.capacityAttempts = 0
        }
        current.attempts += 1
        if (capacity) current.capacityAttempts += 1
        return current.attempts
    }

    val capacityAttempts: Int get() = retry?.capacityAttempts ?: 0
    val failureAttempts: Int get() = retry?.attempts ?: 0

    fun tryContextOnce(chunkId: Long, action: () -> Boolean): Boolean {
        val current = retry?.takeIf { it.chunkId == chunkId } ?: return false
        if (current.contextAttempted) return false
        val started = action()
        if (started) current.contextAttempted = true
        return started
    }

    /** One normalized policy for blank finals, NoMatch, NoSpeech, and retryable provider faults. */
    internal data class FailureInput(
        val session: ConversationDictationRecognitionSession?,
        val empty: Boolean,
        val callerAudioPending: Boolean,
        val canRetry: Boolean,
        val capacityFailure: Boolean,
        val maxRetries: Int,
    )

    fun resolveFailure(input: FailureInput): FailureResolution {
        val session = input.session
        val empty = input.empty
        val digitalSilence = empty && session?.callerAudioContainsSpeech() == false &&
            session?.callerAudioFullyFed() == true && session?.acknowledgeCallerAudio() == true
        val chunkId = session?.callerAudioChunkId()
        val request = session?.callerAudioRequest()
        val exhausted = !digitalSilence && input.callerAudioPending && input.canRetry &&
            recordFailure(chunkId, request?.version, input.capacityFailure) > input.maxRetries
        return when {
            digitalSilence -> {
                resetFailure()
                FailureResolution.ConfirmedDigitalSilence
            }
            exhausted -> FailureResolution.Exhausted
            else -> {
                val requeued =
                    if (empty) {
                        if (request?.replacedSpanId != null) {
                            session?.retryCallerAudio() == true
                        } else {
                            (chunkId != null && tryContextOnce(chunkId) {
                                session?.retryCallerAudioWithContext() == true
                            }) ||
                                session?.retryCallerAudioWithFollowingAudio() == true
                        }
                    } else {
                        session?.retryCallerAudio() == true
                    }
                FailureResolution.Retained(requeued)
            }
        }
    }

    fun resetFailure() {
        retry = null
    }

    fun snapshot(): Snapshot = Snapshot(spans.toList(), nextProviderSpanId)

    fun restore(snapshot: Snapshot) {
        spans.clear()
        spans.addAll(snapshot.spans)
        nextProviderSpanId = snapshot.nextProviderSpanId
        retry = null
    }

    fun reset() {
        spans.clear()
        nextProviderSpanId = -1L
        retry = null
    }
}
