package dev.ipf.whitenoise.android.audio

/** Main-thread owner of recognized spans and the retry budget of one newly captured input. */
internal class ConversationDictationRecognitionCoordinator {
    internal sealed interface FailureResolution {
        data object ConfirmedDigitalSilence : FailureResolution

        data object Exhausted : FailureResolution

        data class Retained(
            val requeued: Boolean,
        ) : FailureResolution
    }

    internal data class Span(
        val id: Long,
        val text: String,
    )

    internal data class Snapshot(
        val spans: List<Span>,
        val nextProviderSpanId: Long,
    )

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
    fun provisionalTranscript(
        request: ConversationDictationAudioRequest?,
        text: String,
    ): String {
        val retained = if (request?.replacedSpanId == spans.lastOrNull()?.id) spans.dropLast(1) else spans
        val prefix = retained.fold("") { current, span -> appendConversationDictationSegment(current, span.text) }
        return appendConversationDictationSegment(prefix, text)
    }

    fun canAccept(request: ConversationDictationAudioRequest): Boolean {
        val replacement = request.replacedSpanId
        return replacement == null || spans.lastOrNull()?.id == replacement
    }

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

    fun tryContextOnce(
        chunkId: Long,
        action: () -> Boolean,
    ): Boolean {
        val current = retry?.takeIf { it.chunkId == chunkId }
        return if (current == null || current.contextAttempted) {
            false
        } else {
            val started = action()
            if (started) current.contextAttempted = true
            started
        }
    }

    /** One normalized policy for blank finals, NoMatch, NoSpeech, and retryable provider faults. */
    internal data class FailureInput(
        val session: ConversationDictationRecognitionSession?,
        val empty: Boolean,
        val callerAudioPending: Boolean,
        val countInputRetry: Boolean,
        val capacityFailure: Boolean,
        val maxRetries: Int,
    )

    fun resolveFailure(input: FailureInput): FailureResolution {
        val session = input.session
        val empty = input.empty
        val digitalSilence =
            empty &&
                session?.callerAudioContainsSpeech() == false &&
                session?.callerAudioFullyFed() == true &&
                session?.acknowledgeCallerAudio() == true
        val chunkId = session?.callerAudioChunkId()
        val request = session?.callerAudioRequest()
        val exhausted =
            !digitalSilence &&
                input.callerAudioPending &&
                input.countInputRetry &&
                recordFailure(chunkId, session?.callerAudioRequestVersion(), input.capacityFailure) > input.maxRetries
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
                            (
                                chunkId != null &&
                                    tryContextOnce(chunkId) {
                                        session?.retryCallerAudioWithContext() == true
                                    }
                            ) ||
                                session?.retryCallerAudioWithFollowingAudio() == true
                        }
                    } else {
                        session?.retryCallerAudio() == true
                    }
                FailureResolution.Retained(requeued)
            }
        }
    }

    /** The kind of provider failure on the final caller-audio chunk after an explicit finish. */
    internal enum class TailFault {
        /** NoSpeech, NoMatch or a blank final result. */
        Empty,

        /** A retryable provider fault such as a disconnect, busy recognizer or network error. */
        Transient,

        /** The provider stopped answering within the generation deadline. */
        TimedOut,
    }

    /** The final chunk of a finished capture whose recognition failed. */
    internal data class TailInput(
        val chunkId: Long?,
        val finalChunk: Boolean,
        val chunkHasSpeech: Boolean?,
        val tailMillis: Long?,
        val fault: TailFault,
    )

    /**
     * Decides whether a failing final chunk may be skipped so recognized text can still be delivered.
     *
     * The audio after the last recognized sentence is usually breath, room noise or the Send tap.
     * Holding the whole message for it turns a successful dictation into a draft. Earlier chunks,
     * long tails with real speech and first transient faults still take the bounded retry path.
     */
    fun shouldDropFinalTail(input: TailInput): Boolean {
        if (!input.finalChunk) return false
        val priorFailures = retry?.takeIf { it.chunkId == input.chunkId }?.attempts ?: 0
        val shortTail = input.tailMillis != null && input.tailMillis <= SHORT_FINAL_TAIL_MILLIS
        return when (input.fault) {
            TailFault.Empty -> input.chunkHasSpeech == false || priorFailures >= 1
            TailFault.Transient -> shortTail && priorFailures >= 1
            TailFault.TimedOut -> shortTail
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

    internal companion object {
        /** Final chunks up to this length hold at most a short trailing phrase. */
        const val SHORT_FINAL_TAIL_MILLIS = 1_500L
    }
}
