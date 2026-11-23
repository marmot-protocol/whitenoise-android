package dev.ipf.whitenoise.android.audio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Recordings started while an earlier dictation is still being transcribed, oldest first.
 *
 * Each one owns captured PCM only, for its own conversation. None owns a recognizer generation: the
 * speech service accepts one caller-audio request at a time, so each recording waits until the
 * dictations ahead of it have delivered and is then promoted into the ordinary session slot. Only the
 * newest can still be recording; the microphone has one owner.
 */
internal class ConversationDictationFollowUpOwner {
    internal enum class Phase {
        /** Accepted, waiting for the previous recorder to release the microphone. */
        Arming,

        /** Recording into its own bounded buffer. */
        Capturing,

        /** Recording ended; the buffered audio waits for the dictations ahead of it. */
        Sealed,
    }

    internal data class Pending(
        val sessionId: Long,
        /** This recording's conversation; the draft is captured again at promotion. */
        val target: ConversationDictationTarget,
        val phase: Phase,
        /** Null until the user chooses, or when recording ended without a choice. */
        val mode: ConversationDictationDeliveryMode? = null,
    ) {
        val key: ConversationDictationKey
            get() = ConversationDictationKey.from(target.accountRef, target.groupIdHex)
    }

    var queue: List<Pending> by mutableStateOf(emptyList())

    /** Discarded recorders whose native closure has not been acknowledged yet. */
    var discardsClosing by mutableIntStateOf(0)

    val discardClosing: Boolean
        get() = discardsClosing > 0

    /** The newest recording: the one the controls show and Paste, Send and Cancel act on. */
    val pending: Pending?
        get() = queue.lastOrNull()

    /** The next recording to promote. */
    val head: Pending?
        get() = queue.firstOrNull()

    /** A queued Send whose conversation lost an earlier dictation must not send on its own. */
    var blockedSendSessionId: Long? = null

    /** The conversation of the dictation currently ahead of the queue. */
    var drainingKey: ConversationDictationKey? = null

    /** Conversations where an earlier dictation in this chain ended without delivering. */
    val undelivered = mutableSetOf<ConversationDictationKey>()

    val capturing: Boolean
        get() = queue.any { it.phase == Phase.Capturing }

    /** Null when no follow-up owns the completion commands. */
    val acceptsCompletion: Boolean?
        get() = pending?.let { it.phase == Phase.Capturing }

    /** The command that shows progress once the newest recording itself has been finished. */
    val processingMode: ConversationDictationDeliveryMode?
        get() =
            pending
                ?.takeIf { it.phase == Phase.Sealed }
                ?.let { it.mode ?: ConversationDictationDeliveryMode.PasteIntoDraft }

    fun replaceNewest(updated: Pending) {
        queue = queue.dropLast(1) + updated
    }

    fun removeNewest() {
        queue = queue.dropLast(1)
        if (queue.isEmpty()) resetChain()
    }

    fun resetChain() {
        drainingKey = null
        undelivered.clear()
    }

    internal companion object {
        /** Four waiting recordings of at most 90 seconds each stay near 11.5 MB of PCM. */
        const val MAX_QUEUED = 4
    }
}
