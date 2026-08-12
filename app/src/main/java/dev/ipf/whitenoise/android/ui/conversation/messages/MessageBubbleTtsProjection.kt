package dev.ipf.whitenoise.android.ui.conversation.messages

import dev.ipf.whitenoise.android.audio.tts.TtsPassage

/** Rendered body identity that TTS projection and highlight must track. */
internal data class MessageBubbleTtsSpeakableIdentity(
    val bodyText: String,
)

internal data class MessageBubbleTtsGateInput(
    val messageIdHex: String,
    val ttsHighlightPassage: TtsPassage?,
    val textSelectionMode: Boolean,
    val deleted: Boolean,
    val persistedFailure: Boolean,
    val speakableIdentity: MessageBubbleTtsSpeakableIdentity?,
)

internal data class MessageBubbleTtsProjectionState(
    val candidate: Boolean,
    val effectivePassage: TtsPassage?,
    val effectiveProgress: TtsReadAloudProgress?,
)

internal fun messageBubbleTtsSpeakableIdentity(
    bodyText: String?,
    deleted: Boolean,
    persistedFailure: Boolean,
): MessageBubbleTtsSpeakableIdentity? {
    if (deleted || persistedFailure || bodyText.isNullOrBlank()) return null
    return MessageBubbleTtsSpeakableIdentity(bodyText = bodyText)
}

internal fun messageBubbleTtsProjectionCandidate(input: MessageBubbleTtsGateInput): Boolean =
    !input.deleted &&
        !input.persistedFailure &&
        !input.textSelectionMode &&
        input.speakableIdentity != null &&
        input.ttsHighlightPassage?.messageIdHex == input.messageIdHex

internal fun resolveMessageBubbleTtsProjectionState(
    gateInput: MessageBubbleTtsGateInput,
    projectionId: String?,
    progress: TtsReadAloudProgress?,
): MessageBubbleTtsProjectionState {
    val candidate = messageBubbleTtsProjectionCandidate(gateInput)
    val effectivePassage =
        if (!candidate) {
            null
        } else {
            effectiveTtsHighlightPassage(
                ttsHighlightPassage = gateInput.ttsHighlightPassage,
                messageIdHex = gateInput.messageIdHex,
                projectionId = projectionId,
                textSelectionMode = gateInput.textSelectionMode,
            )
        }
    return MessageBubbleTtsProjectionState(
        candidate = candidate,
        effectivePassage = effectivePassage,
        effectiveProgress =
            effectiveTtsReadAloudProgress(
                progress = progress,
                effectivePassage = effectivePassage,
            ),
    )
}
