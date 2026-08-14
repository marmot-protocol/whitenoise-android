package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.ui.text.AnnotatedString
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.audio.tts.TtsSpeakableEntry
import dev.ipf.whitenoise.android.ui.legacyTextToSpeakableProjection
import dev.ipf.whitenoise.android.ui.markdownDocumentToSpeakableText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class TextAttachmentPreview(
    val candidate: TextAttachmentCandidate,
    val text: String,
    val markdownDocument: MarkdownDocumentFfi? = null,
) {
    val isTruncated: Boolean
        get() = markdownDocument?.truncated == true
}

internal enum class TextAttachmentUnavailableReason {
    DownloadFailed,
    TooLarge,
    InvalidEncoding,
    Binary,
}

internal sealed interface TextAttachmentReaderState {
    data object Loading : TextAttachmentReaderState

    data class Ready(
        val preview: TextAttachmentPreview,
    ) : TextAttachmentReaderState

    data class Unavailable(
        val reason: TextAttachmentUnavailableReason,
    ) : TextAttachmentReaderState
}

internal suspend fun loadTextAttachmentPreview(
    candidate: TextAttachmentCandidate,
    bytes: ByteArray,
    parseMarkdown: suspend (String) -> MarkdownDocumentFfi,
): TextAttachmentReaderState {
    val decoded = withContext(Dispatchers.Default) { decodeTextAttachment(bytes) }
    return when (decoded) {
        TextAttachmentDecodeResult.Binary ->
            TextAttachmentReaderState.Unavailable(TextAttachmentUnavailableReason.Binary)
        TextAttachmentDecodeResult.InvalidEncoding ->
            TextAttachmentReaderState.Unavailable(TextAttachmentUnavailableReason.InvalidEncoding)
        TextAttachmentDecodeResult.TooLarge ->
            TextAttachmentReaderState.Unavailable(TextAttachmentUnavailableReason.TooLarge)
        is TextAttachmentDecodeResult.Success -> {
            val document =
                if (candidate.format == TextAttachmentFormat.Markdown) {
                    parseMarkdown(decoded.text)
                } else {
                    null
                }
            TextAttachmentReaderState.Ready(
                TextAttachmentPreview(
                    candidate = candidate,
                    text = decoded.text,
                    markdownDocument = document,
                ),
            )
        }
    }
}

internal fun textAttachmentCopyText(
    selected: List<AnnotatedString>,
    fullText: String,
): String = selected.joinToString(separator = "\n", transform = AnnotatedString::text).ifEmpty { fullText }

internal fun textAttachmentSpeakableText(preview: TextAttachmentPreview): String =
    preview.markdownDocument
        ?.takeIf { it.blocks.isNotEmpty() }
        ?.let(::markdownDocumentToSpeakableText)
        ?: legacyTextToSpeakableProjection(preview.text).text

internal fun textAttachmentTtsEntry(
    preview: TextAttachmentPreview,
    senderKey: String,
    senderDisplayName: String,
    messageIdHex: String,
    attachmentIndex: Int,
): TtsSpeakableEntry =
    TtsSpeakableEntry(
        senderKey = senderKey,
        senderDisplayName = "$senderDisplayName · ${preview.candidate.displayName}",
        text = textAttachmentSpeakableText(preview),
        messageIdHex = "attachment:$messageIdHex:$attachmentIndex",
    )
