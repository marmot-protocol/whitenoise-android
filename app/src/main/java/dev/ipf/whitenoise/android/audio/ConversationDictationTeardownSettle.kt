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
