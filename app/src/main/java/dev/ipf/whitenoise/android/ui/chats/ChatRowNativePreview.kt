package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.marmotkit.SelectedChatPreviewFfi
import dev.ipf.whitenoise.android.state.ChatListItem

/** Prefers MarmotKit's atomic row preview while retaining old-row compatibility. */
internal fun chatRowDraftPreviewText(
    item: ChatListItem,
    legacyDraft: String?,
): String? =
    when (val selected = item.selectedPreview) {
        is SelectedChatPreviewFfi.Draft -> selected.draft.text
        null -> legacyDraft
        else -> null
    }?.takeIf { it.isNotBlank() }
