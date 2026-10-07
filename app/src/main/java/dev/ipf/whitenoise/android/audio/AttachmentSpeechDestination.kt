package dev.ipf.whitenoise.android.audio

import dev.ipf.whitenoise.android.audio.tts.TtsState

/** Canonical attachment owner captured before speech preparation; it never participates in history paging. */
internal data class AttachmentSpeechOwner(
    val accountRef: String,
    val groupIdHex: String,
    val messageIdHex: String,
    val attachmentIndex: Int,
)

/** Navigation-only identity for an accepted attachment speech session, retained after its reader closes. */
internal data class AttachmentSpeechDestination(
    val owner: AttachmentSpeechOwner,
    override val sessionId: Long,
) : PlaybackConversationDestination {
    override val accountRef: String get() = owner.accountRef
    override val groupIdHex: String get() = owner.groupIdHex
    override val messageIdHex: String get() = owner.messageIdHex
    override val ttsFocusSessionId: Long? get() = null

    /** Synthetic attachment passage IDs identify the queue only; the shell always opens the canonical parent. */
    fun current(state: TtsState): AttachmentSpeechDestination? =
        takeIf {
            (state is TtsState.Speaking || state is TtsState.Paused) &&
                state.sessionId == sessionId &&
                state.passage?.messageIdHex == "attachment:${owner.messageIdHex}:${owner.attachmentIndex}"
        }
}
