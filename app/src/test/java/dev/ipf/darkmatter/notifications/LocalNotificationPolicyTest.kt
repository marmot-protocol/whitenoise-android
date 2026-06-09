package dev.ipf.darkmatter.notifications

import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNotificationPolicyTest {
    @Test
    fun foregroundActiveConversationNotificationIsSuppressed() {
        assertFalse(
            LocalNotificationPolicy.shouldPost(
                update(groupIdHex = "active-group"),
                appInForeground = true,
                activeConversationGroupIdHex = "active-group",
            ),
        )
    }

    @Test
    fun foregroundOtherConversationNotificationStillPosts() {
        assertTrue(
            LocalNotificationPolicy.shouldPost(
                update(groupIdHex = "other-group"),
                appInForeground = true,
                activeConversationGroupIdHex = "active-group",
            ),
        )
    }

    @Test
    fun backgroundActiveConversationNotificationStillPosts() {
        assertTrue(
            LocalNotificationPolicy.shouldPost(
                update(groupIdHex = "active-group"),
                appInForeground = false,
                activeConversationGroupIdHex = "active-group",
            ),
        )
    }

    private fun update(groupIdHex: String) =
        NotificationUpdateFfi(
            notificationKey = "message:account:message",
            conversationKey = "conversation:account:$groupIdHex",
            trigger = NotificationTriggerFfi.NEW_MESSAGE,
            accountRef = "account",
            accountIdHex = "account",
            groupIdHex = groupIdHex,
            groupName = "General",
            isDm = false,
            messageIdHex = "message",
            sender = user(),
            receiver = user(accountIdHex = "account", displayName = "Me"),
            previewText = "Hello",
            timestampMs = 1234,
            isFromSelf = false,
        )

    private fun user(
        accountIdHex: String = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        displayName: String? = null,
    ) = NotificationUserFfi(
        accountIdHex = accountIdHex,
        displayName = displayName,
        pictureUrl = null,
    )
}
