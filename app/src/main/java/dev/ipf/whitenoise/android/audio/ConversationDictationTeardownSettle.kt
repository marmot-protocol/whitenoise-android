package dev.ipf.whitenoise.android.audio

import dev.ipf.whitenoise.android.audio.ConversationDictationController.Companion.GENERATION_RESTART_DELAY_MILLIS

/** Unbind settle after a caller-audio generation ended with its provider's own result or error. */
internal const val PROVIDER_ENDED_TEARDOWN_SETTLE_MILLIS = 50L

/**
 * Returns how long a replacement recognizer waits after a teardown.
 *
 * Only a finish drain after a caller-audio generation whose provider already delivered its terminal
 * callback gets the short settle. Listening restarts, cancel, timeout, idle recycle, pipe failure and
 * microphone-mode providers keep the full restart delay.
 */
internal fun conversationDictationTeardownSettleMillis(providerEnded: Boolean): Long =
    if (providerEnded) PROVIDER_ENDED_TEARDOWN_SETTLE_MILLIS else GENERATION_RESTART_DELAY_MILLIS

/** Owns when a replacement recognizer may use the short settle, and the fallback after it fails. */
internal class ConversationDictationShortSettle {
    /** Logical session whose recognizer ended on its own at the last teardown, or null. */
    private var providerEndedSession: Long? = null
    private var disabled = false
    private var generationStartedEarly = false

    fun noteTeardown(providerEndedSession: Long?) {
        this.providerEndedSession = providerEndedSession
    }

    /** Settle before replacing the last torn-down recognizer; another take's provider terminal never shortens it. */
    fun teardownSettleMillis(
        sessionId: Long,
        finishing: Boolean,
    ): Long = settleMillis(providerEnded = providerEndedSession == sessionId, finishing = finishing)

    /** The short settle applies only to a finish drain after a provider terminal, until a short-settle disconnect. */
    fun settleMillis(
        providerEnded: Boolean,
        finishing: Boolean,
    ): Long = conversationDictationTeardownSettleMillis(providerEnded && finishing && !disabled)

    /** Marks a generation that started before the full restart delay had passed since the last teardown. */
    fun noteGenerationStart(
        now: Long,
        releasedAt: Long?,
    ) {
        generationStartedEarly = releasedAt != null && now - releasedAt < GENERATION_RESTART_DELAY_MILLIS
    }

    /**
     * Returns true when a generation started after the short settle reports a provider disconnect.
     *
     * That provider needs longer to unbind, so this controller keeps the full delay from then on.
     */
    fun takeShortSettleDisconnect(failure: ConversationDictationFailure): Boolean {
        if (!generationStartedEarly || failure != ConversationDictationFailure.ProviderDisconnected) return false
        generationStartedEarly = false
        disabled = true
        return true
    }
}
