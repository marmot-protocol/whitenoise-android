package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import android.net.Uri
import dev.ipf.whitenoise.android.media.GroupSvgPolicy
import dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaDecoder
import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.ui.conversation.media.BoundedDocumentRead
import dev.ipf.whitenoise.android.ui.conversation.media.normalizeDocumentMime
import dev.ipf.whitenoise.android.ui.conversation.media.queryDisplayName
import dev.ipf.whitenoise.android.ui.conversation.media.readBoundedDocument
import dev.ipf.whitenoise.android.ui.conversation.media.safeDocumentDisplayName
import dev.ipf.whitenoise.android.ui.conversation.media.safeGetType

/** Preserves positively admitted original vectors through the existing encrypted attachment owner. */
internal fun readChatSvgAttachment(
    context: Context,
    uri: Uri,
    remainingBytes: Long,
): ImageAttachmentReadOutcome? {
    val resolver = context.contentResolver
    if (normalizeDocumentMime(safeGetType(resolver, uri)) != "image/svg+xml") return null
    val cap = remainingBytes.coerceIn(0, GroupSvgPolicy.MAX_BYTES.toLong()).toInt()
    return when (val read = readBoundedDocument(cap) { resolver.openInputStream(uri) }) {
        BoundedDocumentRead.TooLarge -> ImageAttachmentReadOutcome(null, overflowed = true)
        BoundedDocumentRead.Empty, BoundedDocumentRead.Unreadable -> ImageAttachmentReadOutcome(null)
        is BoundedDocumentRead.Success -> {
            val info = IsolatedMediaDecoder.svgInspect(read.bytes)
            ImageAttachmentReadOutcome(
                info?.let {
                    PendingAttachment(
                        plaintextBytes = read.bytes,
                        mediaType = "image/svg+xml",
                        fileName = safeDocumentDisplayName(queryDisplayName(resolver, uri)),
                        dim = "${it.width}x${it.height}",
                    )
                },
            )
        }
    }
}
