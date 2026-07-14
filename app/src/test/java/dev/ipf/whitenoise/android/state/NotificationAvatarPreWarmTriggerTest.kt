package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatListUpdateTriggerFfi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationAvatarPreWarmTriggerTest {
    @Test
    fun preWarmOnlyForNotificationRelevantLiveUpdates() {
        assertTrue(shouldPreWarmNotificationAvatars(ChatListUpdateTriggerFfi.NEW_GROUP))
        assertTrue(shouldPreWarmNotificationAvatars(ChatListUpdateTriggerFfi.NEW_LAST_MESSAGE))
        assertFalse(shouldPreWarmNotificationAvatars(ChatListUpdateTriggerFfi.LAST_MESSAGE_DELETED))
        assertFalse(shouldPreWarmNotificationAvatars(ChatListUpdateTriggerFfi.ARCHIVE_CHANGED))
        assertFalse(shouldPreWarmNotificationAvatars(ChatListUpdateTriggerFfi.PENDING_CONFIRMATION_CHANGED))
        assertFalse(shouldPreWarmNotificationAvatars(ChatListUpdateTriggerFfi.MEMBERSHIP_CHANGED))
        assertFalse(shouldPreWarmNotificationAvatars(ChatListUpdateTriggerFfi.UNREAD_CHANGED))
        assertFalse(shouldPreWarmNotificationAvatars(ChatListUpdateTriggerFfi.SNAPSHOT_REFRESH))
        assertFalse(shouldPreWarmNotificationAvatars(ChatListUpdateTriggerFfi.REMOVED))
    }
}
