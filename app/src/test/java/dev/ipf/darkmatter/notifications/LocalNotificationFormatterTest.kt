package dev.ipf.darkmatter.notifications

import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalNotificationFormatterTest {
    @Test
    fun messageNotificationUsesSenderGroupAndPreviewText() {
        val content =
            LocalNotificationFormatter.content(
                update(
                    trigger = NotificationTriggerFfi.NEW_MESSAGE,
                    groupName = "Launch",
                    previewText = "We are go",
                    sender = user(displayName = "Alice"),
                ),
            )

        assertEquals("Alice in Launch", content?.title)
        assertEquals("We are go", content?.body)
        assertNull(content?.groupKey)
    }

    @Test
    fun inviteNotificationUsesInviteCopy() {
        val content =
            LocalNotificationFormatter.content(
                update(
                    trigger = NotificationTriggerFfi.GROUP_INVITE,
                    groupName = null,
                    previewText = null,
                    sender = user(displayName = "Bob"),
                ),
            )

        assertEquals("Group invite", content?.title)
        assertEquals("Invite from Bob", content?.body)
    }

    @Test
    fun selfAuthoredNotificationsAreSuppressed() {
        assertNull(
            LocalNotificationFormatter.content(
                update(
                    trigger = NotificationTriggerFfi.NEW_MESSAGE,
                    isFromSelf = true,
                ),
            ),
        )
    }

    @Test
    fun collidingNotificationKeysReceiveDistinctTags() {
        val first = LocalNotificationFormatter.content(update(trigger = NotificationTriggerFfi.NEW_MESSAGE, notificationKey = "Aa"))
        val second = LocalNotificationFormatter.content(update(trigger = NotificationTriggerFfi.NEW_MESSAGE, notificationKey = "BB"))

        assertEquals("Aa".hashCode(), "BB".hashCode())
        assertNotEquals(first?.notificationTag, second?.notificationTag)
    }

    @Test
    fun repeatedNotificationKeyKeepsStableIdentity() {
        val first = LocalNotificationFormatter.content(update(trigger = NotificationTriggerFfi.NEW_MESSAGE, notificationKey = "stable-key"))
        val second = LocalNotificationFormatter.content(update(trigger = NotificationTriggerFfi.NEW_MESSAGE, notificationKey = "stable-key"))

        assertEquals(first?.notificationTag, second?.notificationTag)
        assertEquals(first?.notificationId, second?.notificationId)
    }

    private fun update(
        trigger: NotificationTriggerFfi,
        notificationKey: String = "message:account:message",
        groupName: String? = "General",
        previewText: String? = "Hello",
        sender: NotificationUserFfi = user(),
        isFromSelf: Boolean = false,
    ) = NotificationUpdateFfi(
        notificationKey = notificationKey,
        conversationKey = "conversation:account:group",
        trigger = trigger,
        accountRef = "account",
        accountIdHex = "account",
        groupIdHex = "group",
        groupName = groupName,
        isDm = false,
        messageIdHex = "message",
        sender = sender,
        receiver = user(accountIdHex = "account", displayName = "Me"),
        previewText = previewText,
        timestampMs = 1234,
        isFromSelf = isFromSelf,
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
