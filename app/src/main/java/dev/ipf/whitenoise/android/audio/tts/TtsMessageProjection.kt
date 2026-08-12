package dev.ipf.whitenoise.android.audio.tts

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.ui.SpeakableTextProjection
import dev.ipf.whitenoise.android.ui.legacyTextToSpeakableProjection
import dev.ipf.whitenoise.android.ui.markdownDocumentToSpeakableProjection
import kotlinx.coroutines.CancellationException

/** Builds the single active message projection consumed by every TTS entry point. */
internal suspend fun projectTtsSpeakableEntry(
    message: AppMessageRecordFfi,
    editedText: String?,
    senderDisplayName: String,
    parseMarkdown: suspend (String) -> MarkdownDocumentFfi,
    mentionDisplayName: ((String) -> String?)? = null,
    isGroupMember: ((String) -> Boolean)? = null,
): TtsSpeakableEntry? {
    val source = MessageProjector.copyableText(message, editedText) ?: return null
    val hasActiveEdit = message.kind == 9uL && !editedText.isNullOrBlank()
    val document =
        if (!hasActiveEdit && message.contentTokens.blocks.isNotEmpty()) {
            message.contentTokens
        } else {
            try {
                parseMarkdown(source)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                MarkdownDocumentFfi(
                    truncated = false,
                    blocks = emptyList(),
                    blankLinesBefore = byteArrayOf(),
                )
            }
        }
    val projection =
        if (document.blocks.isEmpty()) {
            legacyTextToSpeakableProjection(source)
        } else {
            markdownDocumentToSpeakableProjection(
                document = document,
                mentionDisplayName = mentionDisplayName,
                isGroupMember = isGroupMember,
            )
        }
    return projection.text
        .takeIf(String::isNotBlank)
        ?.let {
            TtsSpeakableEntry(
                senderKey = message.sender,
                senderDisplayName = senderDisplayName,
                text = it,
                messageIdHex = message.messageIdHex,
                timelineAt = message.recordedAt,
                spokenTextSpans = projection.toTtsSpans(),
                projectionId = projection.projectionId,
            )
        }
}

private fun SpeakableTextProjection.toTtsSpans(): List<TtsSpokenTextSpan> =
    spans.map { span ->
        TtsSpokenTextSpan(
            spoken = TtsTextRange(span.spokenStart, span.spokenEnd),
            visible = TtsVisibleTextSpan(span.leafId, span.visibleStart, span.visibleEnd),
        )
    }
