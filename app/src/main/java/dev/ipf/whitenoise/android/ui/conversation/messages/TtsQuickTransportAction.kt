package dev.ipf.whitenoise.android.ui.conversation.messages

import dev.ipf.whitenoise.android.audio.tts.TtsState

/** What a two-finger swipe down over a message means, given what is playing. */
internal enum class TtsQuickTransportAction {
    /** Nothing is reading this conversation: start at this message. */
    StartReadingMessage,

    /** This conversation is being read aloud: hold it where it is. */
    Pause,

    /** This conversation is parked mid-read: carry on from there. */
    Resume,

    /** Nothing to start and nothing to hold. */
    Ignore,
}

/**
 * Resolves the gesture against playback state, as a pure function, because the
 * interesting part is which of four things a single gesture means and that is
 * worth being able to enumerate rather than trace through a callback.
 *
 * Two rules carry the design.
 *
 * **Toggling belongs to the conversation that owns the session.** Read-aloud is
 * process-wide: a session started in one chat keeps playing while the reader
 * browses another. A swipe on a message in some *other* conversation is not a
 * request to pause what is playing — the listener is looking at this message,
 * and the obvious reading of a swipe here is "read THIS". So the toggle applies
 * only where the session lives, and everywhere else the same gesture starts a
 * new read, taking the session over exactly as the message action would.
 *
 * **An errored session is not a paused one.** The transport bar stays up after
 * a synthesis failure, but there is nothing to resume; swiping again should try
 * the message under the fingers rather than nothing at all. That falls out of
 * the ordering below without a special case: only Speaking and Paused toggle.
 *
 * [canSpeakMessage] is the message's own eligibility — a deleted message, or
 * one with no speakable content. It gates only the start, never the toggle:
 * holding what is already playing does not require the message under the
 * fingers to have anything to say.
 */
internal fun ttsQuickTransportActionFor(
    state: TtsState,
    ownsSession: Boolean,
    canSpeakMessage: Boolean,
): TtsQuickTransportAction =
    when {
        ownsSession && state is TtsState.Speaking -> TtsQuickTransportAction.Pause
        ownsSession && state is TtsState.Paused -> TtsQuickTransportAction.Resume
        canSpeakMessage -> TtsQuickTransportAction.StartReadingMessage
        else -> TtsQuickTransportAction.Ignore
    }
