package dev.ipf.whitenoise.android.audio

/** Owns restart reservation, timeout cancellation and provider-specific retry pacing. */
internal class ConversationDictationRestartOwner {
    private var restartId = 0L
    private var reserved = false
    private var timeout: ConversationDictationTimeoutHandle? = null
    var lastTeardownAtElapsedMillis: Long? = null
        private set
    private var consecutiveNoSpeech = 0
    private var disconnectRetries = 0
    private var permissionRetryUsed = false

    val pending: Boolean get() = reserved

    fun reserve(): Long {
        reserved = true
        return ++restartId
    }

    fun install(
        id: Long,
        handle: ConversationDictationTimeoutHandle,
    ) {
        if (id == restartId && reserved) timeout = handle else handle.cancel()
    }

    fun consume(id: Long): Boolean {
        if (id != restartId || !reserved) return false
        reserved = false
        timeout = null
        return true
    }

    fun cancel() {
        timeout?.cancel()
        timeout = null
        reserved = false
        restartId += 1L
    }

    fun noteTeardown(now: Long) {
        lastTeardownAtElapsedMillis = now
    }

    fun noteNoSpeech(
        rapid: Boolean,
        limit: Int,
    ): Boolean {
        consecutiveNoSpeech = if (rapid) consecutiveNoSpeech + 1 else 0
        return consecutiveNoSpeech >= limit
    }

    fun resetRapidNoSpeech() {
        consecutiveNoSpeech = 0
    }

    fun takePermissionRetry(): Boolean {
        if (permissionRetryUsed) return false
        permissionRetryUsed = true
        return true
    }

    fun nextDisconnectDelay(delays: LongArray): Long? {
        val delay = delays.getOrNull(disconnectRetries)
        if (delay != null) disconnectRetries += 1
        return delay
    }

    fun resetPolicy() {
        consecutiveNoSpeech = 0
        disconnectRetries = 0
        permissionRetryUsed = false
    }
}
