package dev.ipf.whitenoise.android.audio

/**
 * Owns the app-wide microphone lease and the playback interruption it causes.
 *
 * The lease belongs to the session that acquired it. A later session cannot reuse it while the
 * earlier recorder is still closing, and only the owning session's closure releases it, once.
 */
internal class ConversationDictationMicrophoneLease(
    private val tryAcquire: () -> Boolean,
    private val releaseMicrophone: () -> Unit,
    private val afterCapture: () -> Unit,
    private val diagnostic: (String) -> Unit,
) {
    var held = false
        private set

    /** Session whose capture currently interrupts playback, or null once it has been restored. */
    var captureSessionId: Long? = null
        private set

    private var playbackInterrupted = false

    /** Whether a replaced session's recorder still holds the lease. */
    fun heldByOtherSession(sessionId: Long): Boolean = held && captureSessionId != sessionId

    fun acquire(sessionId: Long): Boolean {
        val acquired = tryAcquire()
        diagnostic("event=microphone_lease acquired=$acquired")
        if (!acquired) return false
        held = true
        captureSessionId = sessionId
        playbackInterrupted = true
        return true
    }

    /** Restores only playback that [sessionId] interrupted and releases its microphone, at most once. */
    fun finish(sessionId: Long) {
        if (!playbackInterrupted || captureSessionId != sessionId) return
        playbackInterrupted = false
        captureSessionId = null
        if (held) {
            held = false
            diagnostic("event=microphone_lease_released")
            runCatching(releaseMicrophone)
        }
        runCatching(afterCapture)
    }
}
