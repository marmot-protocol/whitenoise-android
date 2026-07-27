package dev.ipf.whitenoise.android.ui.conversation.composer

import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.whitenoise.android.core.MediaPreviewFallback
import dev.ipf.whitenoise.android.core.ReplyMediaKind
import dev.ipf.whitenoise.android.core.replyMediaKindFromMime
import dev.ipf.whitenoise.android.media.MediaReferenceSupport

internal fun composerReplyMediaKind(
    mediaFallback: MediaPreviewFallback?,
    tags: List<MessageTagFfi>,
    sourceEpoch: ULong?,
): ReplyMediaKind {
    mediaFallback?.let { return it.kind }
    val refs = MediaReferenceSupport.parseAllImetaTags(tags, sourceEpoch ?: 0uL)
    return replyMediaKindFromMime(refs.firstOrNull()?.mediaType)
}
