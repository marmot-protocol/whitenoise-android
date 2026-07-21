package dev.ipf.whitenoise.android.ui.conversation.composer

import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.whitenoise.android.core.MediaPreviewFallback
import dev.ipf.whitenoise.android.core.ReplyMediaKind
import dev.ipf.whitenoise.android.core.replyMediaKindFromMime
import dev.ipf.whitenoise.android.media.MediaReferenceParser

internal fun composerReplyMediaKind(
    mediaFallback: MediaPreviewFallback?,
    tags: List<MessageTagFfi>,
): ReplyMediaKind {
    mediaFallback?.let { return it.kind }
    val refs = MediaReferenceParser.parseAllImetaTags(tags)
    return replyMediaKindFromMime(refs.firstOrNull()?.mediaType)
}
