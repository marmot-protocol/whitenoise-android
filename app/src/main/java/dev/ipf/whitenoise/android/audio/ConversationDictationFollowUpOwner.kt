package dev.ipf.whitenoise.android.audio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * One recording started while the previous dictation is still being transcribed.
 *
 * It owns captured PCM only. It never owns a recognizer generation: the speech service accepts one
 * caller-audio request at a time, so this audio waits until the previous session has delivered and
 * is then promoted into the ordinary session slot.
 */
internal class ConversationDictationFollowUpOwner {
    internal enum class Phase {
        /** Accepted, waiting for the previous recorder to release the microphone. */
        Arming,

        /** Recording into its own bounded buffer. */
        Capturing,

        /** Recording ended; the buffered audio waits for the previous session. */
        Sealed,
    }

    internal data class Pending(
        val sessionId: Long,
        /** The previous session's immutable origin; the draft is captured again at promotion. */
        val origin: ConversationDictationTarget,
        val phase: Phase,
        /** Null until the user chooses, or when recording ended without a choice. */
        val mode: ConversationDictationDeliveryMode? = null,
    )

    var pending: Pending? by mutableStateOf(null)
    var discardClosing by mutableStateOf(false)

    /** A queued Send whose predecessor did not deliver must not send on its own. */
    var blockedSendSessionId: Long? = null

    val capturing: Boolean
        get() = pending?.let { it.phase == Phase.Capturing } == true

    /** Null when no follow-up owns the completion commands. */
    val acceptsCompletion: Boolean?
        get() = pending?.let { it.phase == Phase.Capturing }

    /** The command that shows progress once the follow-up itself has been finished. */
    val processingMode: ConversationDictationDeliveryMode?
        get() =
            pending
                ?.takeIf { it.phase == Phase.Sealed }
                ?.let { it.mode ?: ConversationDictationDeliveryMode.PasteIntoDraft }
}
