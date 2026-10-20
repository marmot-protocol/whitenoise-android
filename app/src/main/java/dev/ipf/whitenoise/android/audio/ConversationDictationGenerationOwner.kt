package dev.ipf.whitenoise.android.audio

/** Owns one recognizer generation and retires callbacks before Android session teardown. */
internal class ConversationDictationGenerationOwner(
    private val cancelDeadline: () -> Unit,
) {
    private var nextId = 0L
    var activeId: Long? = null
        private set
    var session: ConversationDictationRecognitionSession? = null
        private set
    var hasSpeech = false
        private set
    var callerAudioEndpointingActive = false
        private set
    var readyAtElapsedMillis: Long? = null
        private set

    fun begin(): Long {
        val id = ++nextId
        activeId = id
        hasSpeech = false
        readyAtElapsedMillis = null
        return id
    }

    fun attach(created: ConversationDictationRecognitionSession) {
        check(activeId != null && session == null)
        session = created
    }

    /** Cancels the provider generation deadline and records when readiness was observed. */
    fun ready(now: Long): Long {
        cancelDeadline()
        readyAtElapsedMillis = now
        return now
    }

    fun markSpeech() {
        hasSpeech = true
    }

    fun markCallerAudioEndpointing() {
        callerAudioEndpointingActive = true
    }

    /** Cancels the generation deadline and detaches callbacks before returning the retired session. */
    fun retire(): ConversationDictationRecognitionSession? {
        cancelDeadline()
        val retired = session
        session = null
        activeId = null
        hasSpeech = false
        readyAtElapsedMillis = null
        return retired
    }

    fun resetPolicy() {
        hasSpeech = false
        callerAudioEndpointingActive = false
        readyAtElapsedMillis = null
    }
}
