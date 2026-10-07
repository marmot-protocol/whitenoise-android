package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ConversationOpenModeFfi
import dev.ipf.whitenoise.android.audio.PlaybackConversationDestination
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Probes an exact retained source without marking it read or guessing absence from a recent-message tail. */
internal suspend fun WhiteNoiseAppState.playbackSourceRetained(source: PlaybackConversationDestination): Boolean =
    marmotIo(MarmotTraceSection.CONVERSATION_WINDOW_OPEN) {
        retainedPlaybackSource(source) { owner ->
            val window =
                openConversationWindow(
                    accountRef = owner.accountRef,
                    groupIdHex = owner.groupIdHex,
                    mode = ConversationOpenModeFfi.MESSAGE,
                    messageIdHex = owner.messageIdHex,
                    initialRows = 1u,
                    timeoutMs = CONVERSATION_WINDOW_DEFAULT_DEADLINE,
                )
            FfiConversationWindowHandle(window, release = window::close)
        }
    }

/** Releases the temporary native window on success, missing/deleted rows, and cancellation before shell navigation. */
internal suspend fun retainedPlaybackSource(
    source: PlaybackConversationDestination,
    open: suspend (PlaybackConversationDestination) -> ConversationTimelineSubscriptionHandle,
): Boolean {
    // This explicit mode is captured only by the pending-audio UI, never inferred from an invalid ID.
    if (source.navigationFocusMessageId == null) return true
    val window = open(source)
    try {
        return window.snapshot()?.messages?.any { message ->
            message.messageIdHex.equals(source.messageIdHex, ignoreCase = true) &&
                message.groupIdHex.equals(source.groupIdHex, ignoreCase = true) &&
                !message.deleted &&
                message.invalidationStatus == null
        } == true
    } finally {
        withContext(NonCancellable) {
            try {
                window.cancel()
            } finally {
                window.close()
            }
        }
    }
}
