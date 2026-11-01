package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatListAttachmentKindFfi
import dev.ipf.marmotkit.GroupSystemEventFfi
import dev.ipf.whitenoise.android.core.GroupSystemEvents
import dev.ipf.whitenoise.android.core.GroupSystemPreviewNames
import dev.ipf.whitenoise.android.core.MediaPreviewFallback
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.core.RemoteGiphyMedia

private const val CHAT_PREVIEW_STREAM_START_KIND = 1200uL
private const val CHAT_PREVIEW_EDIT_KIND = 1009uL

/** Raw presentation inputs; no row, profile, media reference or selected draft. */
internal data class ChatListPreviewInput(
    val plaintext: String,
    val kind: ULong,
    val deleted: Boolean,
    val attachmentKind: ChatListAttachmentKindFfi?,
    val attachmentCount: UInt,
    val groupSystem: GroupSystemEventFfi?,
) {
    override fun toString(): String = "ChatListPreviewInput(redacted)"
}

/** Chat-row preview renderer; fallback callbacks retain the row's lazy precedence. */
@Suppress("LongParameterList")
internal fun projectedChatListPreviewText(
    preview: ChatListPreviewInput?,
    copy: MessageTextCopy = MessageTextCopy.Default,
    empty: String = "No messages yet",
    groupSystemNames: GroupSystemPreviewNames? = null,
    mediaFallback: MediaPreviewFallback? = null,
    latestFallback: (String) -> String,
): String {
    if (preview == null) return latestFallback(empty)
    return when {
        preview.deleted -> copy.deleted
        MessageProjector.isPollKind(preview.kind) -> copy.poll
        preview.kind == CHAT_PREVIEW_STREAM_START_KIND -> preview.plaintext.ifBlank { copy.agentStreamStarted }
        preview.kind == CHAT_PREVIEW_EDIT_KIND -> latestFallback(empty)
        MessageProjector.isGroupSystemKind(preview.kind) ->
            GroupSystemEvents.previewText(
                plaintext = preview.plaintext,
                copy = copy.groupSystem,
                structured = preview.groupSystem,
                names = groupSystemNames,
            )
        MessageProjector.isChatKind(preview.kind) && RemoteGiphyMedia.isEnvelopeText(preview.plaintext) ->
            copy.giphyMedia
        preview.plaintext.isNotBlank() -> preview.plaintext
        preview.attachmentKind != null -> copy.attachmentLabel(preview.attachmentKind, preview.attachmentCount)
        mediaFallback != null -> mediaFallback.text(copy)
        else -> latestFallback(copy.message)
    }
}
