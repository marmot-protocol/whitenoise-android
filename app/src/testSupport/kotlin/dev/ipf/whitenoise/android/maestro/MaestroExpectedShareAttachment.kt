package dev.ipf.whitenoise.android.maestro

import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.marmotkit.MessageDraftFfi

/** Expected native ownership from the actual imported private-copy snapshot, never UI text. */
internal data class MaestroExpectedShareAttachment(
    val id: String,
    val name: String,
    val mime: String,
) {
    fun matches(actual: MessageDraftAttachmentFfi): Boolean =
        actual.id == id &&
            actual.fileName == name &&
            actual.mediaType == mime &&
            actual.plaintext.contentEquals(byteArrayOf(1, 2, DOCUMENT_TRAILING_BYTE)) &&
            actual.dim == null &&
            actual.thumbhash == null &&
            actual.durationSeconds == null &&
            actual.waveformSamples.isEmpty()

    private companion object {
        const val DOCUMENT_TRAILING_BYTE: Byte = 3
    }
}

/** File-only drafts own native attachment bytes with empty text; cancellation must leave no native draft. */
internal fun maestroShareDraftMatches(
    draft: MessageDraftFfi?,
    group: String,
    text: String?,
    attachments: List<MaestroExpectedShareAttachment>,
): Boolean {
    if (text == null && attachments.isEmpty()) return draft == null
    return draft?.let {
        it.groupIdHex == group &&
            it.content == text.orEmpty() &&
            it.replyToMessageIdHex == null &&
            it.mediaAttachments.size == attachments.size &&
            it.mediaAttachments.zip(attachments).all { (actual, expected) -> expected.matches(actual) }
    } ?: false
}
