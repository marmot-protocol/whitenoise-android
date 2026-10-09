package dev.ipf.whitenoise.android.maestro

import dev.ipf.marmotkit.MessageDraftFfi

/** Expected native ownership from the actual imported private-copy snapshot, never UI text. */
internal data class MaestroExpectedShareAttachment(
    val id: String,
    val name: String,
    val mime: String,
)

/** File-only drafts own native attachment bytes with empty text; cancellation must leave no native draft. */
internal fun maestroShareDraftMatches(
    draft: MessageDraftFfi?,
    group: String,
    text: String?,
    attachments: List<MaestroExpectedShareAttachment>,
): Boolean {
    if (text == null && attachments.isEmpty()) return draft == null
    if (draft == null || draft.groupIdHex != group || draft.content != text.orEmpty()) return false
    if (draft.replyToMessageIdHex != null || draft.mediaAttachments.size != attachments.size) return false
    return draft.mediaAttachments.zip(attachments).all { (actual, expected) ->
        actual.id == expected.id &&
            actual.fileName == expected.name &&
            actual.mediaType == expected.mime &&
            actual.plaintext.contentEquals(byteArrayOf(1, 2, 3)) &&
            actual.dim == null &&
            actual.thumbhash == null &&
            actual.durationSeconds == null &&
            actual.waveformSamples.isEmpty()
    }
}
