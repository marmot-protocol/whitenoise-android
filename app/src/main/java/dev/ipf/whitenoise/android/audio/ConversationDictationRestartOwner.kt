package dev.ipf.whitenoise.android.audio

import dev.ipf.whitenoise.android.audio.ConversationDictationController.Companion.GENERATION_RESTART_DELAY_MILLIS

/** Owns restart reservation, timeout cancellation and provider-specific retry pacing. */
internal class ConversationDictationRestartOwner {
    private var restartId = 0L
    private var reserved = false
    private var timeout: ConversationDictationTimeoutHandle? = null
    var lastTeardownAtElapsedMillis: Long? = null
        private set
    private var teardownProviderEnded = false
    private var shortSettleDisabled = false
    private var shortSettleGeneration = false
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

    fun noteTeardown(
        now: Long,
        providerEnded: Boolean,
    ) {
        lastTeardownAtElapsedMillis = now
        teardownProviderEnded = providerEnded
    }

    /** Settle before replacing the last torn-down recognizer, decided when the replacement is due. */
    fun teardownSettleMillis(finishing: Boolean): Long = settleMillis(teardownProviderEnded, finishing)

    /** The short settle applies only to a finish drain after a provider terminal, until a short-settle disconnect. */
    fun settleMillis(
        providerEnded: Boolean,
        finishing: Boolean,
    ): Long = conversationDictationTeardownSettleMillis(providerEnded && finishing && !shortSettleDisabled)

    /** Marks a generation that started before the full restart delay had passed since the last teardown. */
    fun noteGenerationStart(now: Long) {
        val releasedAt = lastTeardownAtElapsedMillis
        shortSettleGeneration = releasedAt != null && now - releasedAt < GENERATION_RESTART_DELAY_MILLIS
    }

    /**
     * Returns true when a generation started after the short settle reports a provider disconnect.
     *
     * That provider needs longer to unbind, so this controller keeps the full delay from then on.
     */
    fun takeShortSettleDisconnect(failure: ConversationDictationFailure): Boolean {
        if (!shortSettleGeneration || failure != ConversationDictationFailure.ProviderDisconnected) return false
        shortSettleGeneration = false
        shortSettleDisabled = true
        return true
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
