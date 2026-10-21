package dev.ipf.whitenoise.android.audio.tts

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.ui.SpeakableTextProjection
import dev.ipf.whitenoise.android.ui.legacyTextToSpeakableProjection
import dev.ipf.whitenoise.android.ui.markdownDocumentToSpeakableProjection
import dev.ipf.whitenoise.android.ui.parseMarkdownOrEmptyDocument

/** Builds the single active message projection consumed by every TTS entry point. */
@Suppress("ReturnCount", "LongParameterList") // Explicit rendering and conversation inputs share this one speech projection.
internal suspend fun projectTtsSpeakableEntry(
    message: AppMessageRecordFfi,
    editedText: String?,
    senderDisplayName: String,
    parseMarkdown: suspend (String) -> MarkdownDocumentFfi,
    mentionDisplayName: ((String) -> String?)? = null,
    conversationMemberCount: Int? = null,
): TtsSpeakableEntry? {
    val source = resolveTtsSpeakableSource(message, editedText) ?: return null
    val document =
        resolveTtsSpeakableDocument(
            message = message,
            source = source,
            parseMarkdown = parseMarkdown,
        )
    val names = linkedMapOf<String, String?>()
    val projection =
        speakableProjectionFromDocument(
            source = source.text,
            document = document,
            mentionDisplayName = { key ->
                if (!names.containsKey(key)) names[key] = mentionDisplayName?.invoke(key)
                names[key]
            },
        ) ?: return null
    return projection.text
        .takeIf(String::isNotBlank)
        ?.let {
            TtsSpeakableEntry(
                senderKey = message.sender,
                senderDisplayName = senderDisplayName,
                conversationMemberCount = conversationMemberCount,
                text = it,
                messageIdHex = message.messageIdHex,
                timelineAt = message.recordedAt,
                spokenTextSpans = projection.toTtsSpans(),
                projectionId = projection.projectionId,
                speechRoles = projection.speechRoles,
                visibleLeaves = projection.visibleLeaves,
                sourceText = source.text,
                mentionPresentation = TtsMentionPresentation(names.toMap()),
            )
        }
}

/** Uses stored native tokens until an edit supplies a different authored source. */
internal suspend fun resolveTtsSpeakableDocument(
    message: AppMessageRecordFfi,
    source: TtsSpeakableSource,
    parseMarkdown: suspend (String) -> MarkdownDocumentFfi,
): MarkdownDocumentFfi {
    if (source.useStoredContentTokens) return message.contentTokens
    return parseMarkdownOrEmptyDocument(source.text, parseMarkdown)
}

/** Keeps the legacy plain-text fallback when native parsing produced no blocks. */
internal fun speakableProjectionFromDocument(
    source: String,
    document: MarkdownDocumentFfi,
    mentionDisplayName: ((String) -> String?)? = null,
): SpeakableTextProjection? {
    if (source.isBlank()) return null
    return if (document.blocks.isEmpty()) {
        legacyTextToSpeakableProjection(source)
    } else {
        markdownDocumentToSpeakableProjection(
            document = document,
            mentionDisplayName = mentionDisplayName,
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
