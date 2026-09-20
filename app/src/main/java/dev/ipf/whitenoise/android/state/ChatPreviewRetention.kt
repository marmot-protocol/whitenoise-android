package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatListMessagePreviewFfi
import dev.ipf.marmotkit.SelectedChatPreviewFfi

/** A selected draft, invitation, or empty row never inherits the previous message's deadline. */
internal fun ChatListItem.messagePreviewForRetention(): ChatListMessagePreviewFfi? =
    projection?.lastMessage?.takeIf {
        selectedPreview == null || selectedPreview == SelectedChatPreviewFfi.Message
    }

/** Uses only the selected message's pinned finite deadline; unknown, disabled, and overflow remain distinct. */
internal fun ChatListMessagePreviewFfi?.previewExpired(nowSeconds: ULong): Boolean =
    this != null &&
        retentionSeconds != null &&
        retentionSeconds != 0uL &&
        retentionExpiresAt?.let { nowSeconds >= it } == true
