package dev.ipf.whitenoise.android.audio.tts

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.ui.SpeakableTextProjection
import dev.ipf.whitenoise.android.ui.legacyTextToSpeakableProjection
import dev.ipf.whitenoise.android.ui.markdownDocumentToSpeakableProjection
import kotlinx.coroutines.CancellationException

/** Builds the single active message projection consumed by every TTS entry point. */
@Suppress("ReturnCount")
internal suspend fun projectTtsSpeakableEntry(
    message: AppMessageRecordFfi,
    editedText: String?,
    senderDisplayName: String,
    parseMarkdown: suspend (String) -> MarkdownDocumentFfi,
    mentionDisplayName: ((String) -> String?)? = null,
    isGroupMember: ((String) -> Boolean)? = null,
): TtsSpeakableEntry? {
    val source = resolveTtsSpeakableSource(message, editedText) ?: return null
    val document =
        resolveTtsSpeakableDocument(
            message = message,
            source = source,
            parseMarkdown = parseMarkdown,
        )
    val projection =
        speakableProjectionFromDocument(
            source = source.text,
            document = document,
            mentionDisplayName = mentionDisplayName,
            isGroupMember = isGroupMember,
        ) ?: return null
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

internal suspend fun resolveTtsSpeakableDocument(
    message: AppMessageRecordFfi,
    source: TtsSpeakableSource,
    parseMarkdown: suspend (String) -> MarkdownDocumentFfi,
): MarkdownDocumentFfi {
    if (source.useStoredContentTokens) return message.contentTokens
    return try {
        parseMarkdown(source.text)
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

internal fun speakableProjectionFromDocument(
    source: String,
    document: MarkdownDocumentFfi,
    mentionDisplayName: ((String) -> String?)? = null,
    isGroupMember: ((String) -> Boolean)? = null,
): SpeakableTextProjection? {
    if (source.isBlank()) return null
    return if (document.blocks.isEmpty()) {
        legacyTextToSpeakableProjection(source)
    } else {
        markdownDocumentToSpeakableProjection(
            document = document,
            mentionDisplayName = mentionDisplayName,
            isGroupMember = isGroupMember,
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
