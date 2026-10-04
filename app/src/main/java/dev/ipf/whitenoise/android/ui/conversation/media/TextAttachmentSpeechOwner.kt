package dev.ipf.whitenoise.android.ui.conversation.media

import dev.ipf.whitenoise.android.audio.tts.TtsController
import dev.ipf.whitenoise.android.audio.tts.TtsSeekResult
import dev.ipf.whitenoise.android.audio.tts.TtsSpeakableEntry
import dev.ipf.whitenoise.android.audio.tts.TtsState
import dev.ipf.whitenoise.android.ui.conversation.messages.RenderedTextHit

/** Reader callbacks address only their source, local account and exact live speech session. */
internal class TextAttachmentSpeechOwner(
    private val controller: TtsController,
    private val sourceIsCurrent: () -> Boolean,
    private val accountOwnsSpeech: () -> Boolean,
) {
    fun ownsAttachment(
        state: TtsState,
        messageIdHex: String,
        attachmentIndex: Int,
    ): Boolean =
        sourceIsCurrent() &&
            accountOwnsSpeech() &&
            textAttachmentOwnsSpeech(
                state,
                messageIdHex,
                attachmentIndex,
            )

    fun playback(
        entry: TtsSpeakableEntry,
        state: TtsState,
        startAt: (RenderedTextHit) -> Unit,
    ): TextAttachmentPlayback {
        val ownedState = if (accountOwnsSpeech()) state else TtsState.Idle()
        return TextAttachmentPlayback(
            entry = entry,
            state = ownedState,
            prepared = controller.preparedSpeechFor(entry.messageIdHex, entry.projectionId),
            isCurrent = sourceIsCurrent,
            seek = { sentence, revision -> seek(entry, ownedState.sessionId, sentence, revision) },
            startAt = { hit ->
                val current = controller.state.value
                val sameLifetime = current.sessionId == state.sessionId && current::class == state::class
                if (sourceIsCurrent() && sameLifetime) startAt(hit)
            },
        )
    }

    private fun seek(
        entry: TtsSpeakableEntry,
        sessionId: Long,
        sentence: Int,
        revision: String,
    ): TtsState? {
        val current = controller.state.value
        val currentPassage = current.passage
        val owns = sourceIsCurrent() && accountOwnsSpeech() && current.sessionId == sessionId
        val sameSource =
            currentPassage?.messageIdHex == entry.messageIdHex &&
                currentPassage.projectionId == revision &&
                entry.projectionId == revision
        return if (!owns || !sameSource) {
            null
        } else {
            when (controller.seekToSentence(entry.messageIdHex, sentence, revision)) {
                TtsSeekResult.Repositioned, TtsSeekResult.RepositionedAcrossMessages -> controller.state.value
                else -> null
            }
        }
    }
}
