package dev.ipf.whitenoise.android.notifications

import dev.ipf.marmotkit.NotificationTrafficClassFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import dev.ipf.marmotkit.StickerRefFfi
import dev.ipf.whitenoise.android.core.ReplyMediaKind
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalNotificationStickerTest {
    @Test
    fun captionlessStickerReadsAsStickerAndACaptionStillWins() {
        assertEquals("Sticker", body(previewText = null))
        assertEquals("Sticker", body(previewText = "  "))
        assertEquals("see you", body(previewText = "see you"))
    }

    private fun body(previewText: String?): String? =
        LocalNotificationFormatter
            .content(
                update = stickerUpdate(previewText),
                senderNameOverride = null,
                previewTextOverride = null,
                reactedToPreviewOverride = null,
                mediaKind = ReplyMediaKind.None,
                shortNpub = { "npub1sender" },
            )?.body

    private fun stickerUpdate(previewText: String?): NotificationUpdateFfi {
        val sender = NotificationUserFfi(accountIdHex = "sender", displayName = "Alice", pictureUrl = null)
        return NotificationUpdateFfi(
            isMention = false,
            notificationKey = "message:account:message",
            conversationKey = "conversation:account:group",
            trigger = NotificationTriggerFfi.NEW_MESSAGE,
            trafficClass = NotificationTrafficClassFfi.STANDARD,
            accountRef = "account",
            accountIdHex = "account",
            groupIdHex = "group",
            groupName = "General",
            isDm = false,
            messageIdHex = "message",
            sender = sender,
            receiver = NotificationUserFfi(accountIdHex = "account", displayName = "Me", pictureUrl = null),
            previewText = previewText,
            timestampMs = 1234,
            isFromSelf = false,
            reactionEmoji = null,
            reactedToPreview = null,
            sticker = StickerRefFfi("30031:author:pack", "wave", "ab".repeat(32)),
        )
    }
}
