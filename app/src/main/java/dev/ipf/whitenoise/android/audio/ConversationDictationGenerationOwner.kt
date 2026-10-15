package dev.ipf.whitenoise.android.audio

/** Owns one recognizer generation and retires callbacks before Android session teardown. */
internal class ConversationDictationGenerationOwner {
    private var nextId = 0L
    var activeId: Long? = null
        private set
    var session: ConversationDictationRecognitionSession? = null
        private set
    private var timeout: ConversationDictationTimeoutHandle? = null
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

    fun ready(now: Long): Long {
        cancelTimeout()
        readyAtElapsedMillis = now
        return now
    }

    fun markSpeech() {
        hasSpeech = true
    }

    fun markCallerAudioEndpointing() {
        callerAudioEndpointingActive = true
    }

    fun armTimeout(handle: ConversationDictationTimeoutHandle) {
        cancelTimeout()
        timeout = handle
    }

    fun cancelTimeout() {
        timeout?.cancel()
        timeout = null
    }

    fun retire(): ConversationDictationRecognitionSession? {
        cancelTimeout()
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
