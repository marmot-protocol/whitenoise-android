package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatListDraftPreviewFfi
import dev.ipf.marmotkit.SelectedChatPreviewFfi
import dev.ipf.whitenoise.android.ui.chats.ChatRowPortFixtures
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPreviewRetentionTest {
    /** Even a blank-text attachment draft owns its preview independently of the last message. */
    @Test
    fun onlySelectedMessagesProvideRetention() {
        val item = ChatRowPortFixtures.item()
        val attachmentDraft =
            SelectedChatPreviewFfi.Draft(
                ChatListDraftPreviewFfi("", false, 1uL, null),
            )
        for (selection in listOf(attachmentDraft, SelectedChatPreviewFfi.Invitation, SelectedChatPreviewFfi.Empty)) {
            assertNull(item.copy(selectedPreview = selection).messagePreviewForRetention())
        }
        assertSame(item.projection?.lastMessage, item.messagePreviewForRetention())
        assertSame(
            item.projection?.lastMessage,
            item.copy(selectedPreview = SelectedChatPreviewFfi.Message).messagePreviewForRetention(),
        )
    }

    /** The supplied deadline is inclusive and does not inherit the group's current duration. */
    @Test
    fun expiresAtPinnedDeadline() {
        val preview =
            checkNotNull(ChatRowPortFixtures.item().projection?.lastMessage).copy(
                retentionSeconds = 300uL,
                retentionExpiresAt = 400uL,
            )
        assertFalse(preview.previewExpired(399uL))
        assertTrue(preview.previewExpired(400uL))
        assertTrue(preview.previewExpired(500uL))
    }

    /** Unknown, explicitly disabled, and overflowed retention never invent an expiry. */
    @Test
    fun incompleteDecisionsAreRetained() {
        val preview = checkNotNull(ChatRowPortFixtures.item().projection?.lastMessage)
        assertFalse(preview.copy(retentionSeconds = null, retentionExpiresAt = 1uL).previewExpired(999uL))
        assertFalse(preview.copy(retentionSeconds = 0uL, retentionExpiresAt = 1uL).previewExpired(999uL))
        assertFalse(preview.copy(retentionSeconds = 60uL, retentionExpiresAt = null).previewExpired(999uL))
        assertFalse(preview.copy(retentionSeconds = 60uL, retentionExpiresAt = ULong.MAX_VALUE).previewExpired(999uL))
    }
}
