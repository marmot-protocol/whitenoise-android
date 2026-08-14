package dev.ipf.whitenoise.android.audio.tts

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.whitenoise.android.core.MessageProjector

internal data class TtsSpeakableSource(
    val text: String,
    val useStoredContentTokens: Boolean,
)

internal fun resolveTtsSpeakableSource(
    message: AppMessageRecordFfi,
    editedText: String?,
): TtsSpeakableSource? {
    val text = MessageProjector.copyableText(message, editedText) ?: return null
    val hasActiveEdit = message.kind == 9uL && !editedText.isNullOrBlank()
    return TtsSpeakableSource(
        text = text,
        useStoredContentTokens = !hasActiveEdit && message.contentTokens.blocks.isNotEmpty(),
    )
}
