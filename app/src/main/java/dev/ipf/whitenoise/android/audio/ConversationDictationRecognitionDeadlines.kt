package dev.ipf.whitenoise.android.audio

/** Recognition timers share one policy; session and generation identity remain with their owners. */
internal class ConversationDictationRecognitionDeadlines {
    enum class GenerationPhase(
        val diagnosticName: String,
    ) {
        Startup("recognizer_start"),
        Processing("processing"),
    }

    private var sessionTimeout: ConversationDictationTimeoutHandle? = null
    private var generationTimeout: ConversationDictationTimeoutHandle? = null
    private var generationPhase: GenerationPhase? = null
    private var drainingSessionId: Long? = null

    /** Reports whether this session owns the active caller-audio drain deadline. */
    fun isDraining(sessionId: Long): Boolean = drainingSessionId == sessionId

    /** Replaces the session bound and clears any previous caller-audio drain policy. */
    fun armSession(create: () -> ConversationDictationTimeoutHandle) {
        clearSession()
        sessionTimeout = create()
    }

    /** One bounded attempt covers every queued chunk and replacement recognizer. */
    fun beginCallerAudioDrain(
        sessionId: Long,
        create: () -> ConversationDictationTimeoutHandle,
    ) {
        if (drainingSessionId == sessionId) return
        clearSession()
        drainingSessionId = sessionId
        if (generationPhase == GenerationPhase.Processing) cancelGeneration()
        sessionTimeout = create()
    }

    /** Completed audio hands off to the completion owner's separate validation and dispatch bounds. */
    fun finishCallerAudioDrain(sessionId: Long) {
        if (isDraining(sessionId)) clearSession()
    }

    /** Replaces the provider bound unless this session already owns a processing drain deadline. */
    fun armGeneration(
        sessionId: Long,
        phase: GenerationPhase,
        create: () -> ConversationDictationTimeoutHandle,
    ) {
        // A suppressed processing timer also leaves any outstanding startup bound intact.
        if (drainingSessionId == sessionId && phase == GenerationPhase.Processing) return
        cancelGeneration()
        generationPhase = phase
        generationTimeout = create()
    }

    /** Readiness and generation retirement release only that provider's timer. */
    fun cancelGeneration() {
        generationTimeout?.cancel()
        generationTimeout = null
        generationPhase = null
    }

    /** Cancels the session or drain bound and ends processing-timer suppression. */
    fun clearSession() {
        sessionTimeout?.cancel()
        sessionTimeout = null
        drainingSessionId = null
    }
}
