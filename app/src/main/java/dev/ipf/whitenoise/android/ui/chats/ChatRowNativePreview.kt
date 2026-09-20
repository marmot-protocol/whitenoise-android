package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.marmotkit.ChatListAttachmentKindFfi
import dev.ipf.marmotkit.SelectedChatPreviewFfi
import dev.ipf.whitenoise.android.state.ChatListItem

/** Draft presentation retains attachment-only native drafts without reviving stale legacy text. */
internal data class ChatRowNativePreview(
    val text: String?,
    val attachmentKind: ChatListAttachmentKindFfi?,
    val attachmentCount: UInt,
)

/** Prefers MarmotKit's atomic draft state while retaining old-row compatibility. */
internal fun chatRowDraftPreview(
    item: ChatListItem,
    legacyDraft: String?,
): ChatRowNativePreview? =
    when (val selected = item.selectedPreview) {
        is SelectedChatPreviewFfi.Draft ->
            selected.draft
                .takeIf { it.text.isNotBlank() || it.attachmentKind != null || it.attachmentCount > 0u }
                ?.let { draft ->
                    ChatRowNativePreview(
                        text = draft.text.takeIf(String::isNotBlank),
                        attachmentKind = draft.attachmentKind,
                        attachmentCount = draft.attachmentCount.toUInt(),
                    )
                }
        null ->
            legacyDraft
                ?.takeIf(String::isNotBlank)
                ?.let { ChatRowNativePreview(it, attachmentKind = null, attachmentCount = 0u) }
        else -> null
    }

/** Returns the draft's text-only compatibility projection for non-rendering callers. */
internal fun chatRowDraftPreviewText(
    item: ChatListItem,
    legacyDraft: String?,
): String? = chatRowDraftPreview(item, legacyDraft)?.text

/** Joins a localized prefix after Android resource trimming without spacing CJK punctuation. */
internal fun chatRowDraftText(
    prefix: String,
    draftText: String,
): String {
    val separator = if (prefix.lastOrNull()?.isWhitespace() == true || prefix.endsWith('：')) "" else " "
    return prefix + separator + draftText
}
