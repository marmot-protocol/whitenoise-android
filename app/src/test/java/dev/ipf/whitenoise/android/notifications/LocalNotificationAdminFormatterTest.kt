package dev.ipf.whitenoise.android.notifications

import dev.ipf.marmotkit.NotificationTrafficClassFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalNotificationAdminFormatterTest {
    @Test
    fun madeAdminNamesTheGroupAndUsesMembershipIdentity() {
        val content = content(update(trigger = NotificationTriggerFfi.MADE_ADMIN, groupName = "Launch"))

        assertEquals("You are now an admin of Launch", content?.title)
        assertEquals("You are now an admin", content?.body)
        assertEquals("group-membership|account|group", content?.notificationTag)
        assertEquals(LocalNotificationFormatter.GROUP_MEMBERSHIP_NOTIFICATION_ID, content?.notificationId)
    }

    @Test
    fun removedAsAdminNamesTheGroup() {
        val content = content(update(trigger = NotificationTriggerFfi.REMOVED_AS_ADMIN, groupName = "Launch"))

        assertEquals("You are no longer an admin of Launch", content?.title)
        assertEquals("You are no longer an admin", content?.body)
    }

    @Test
    fun adminRoleTitlesFallBackForUnnamedGroups() {
        assertEquals(
            "You are now an admin of a group",
            content(update(trigger = NotificationTriggerFfi.MADE_ADMIN, groupName = null))?.title,
        )
        assertEquals(
            "You are no longer an admin of a group",
            content(update(trigger = NotificationTriggerFfi.REMOVED_AS_ADMIN, groupName = null))?.title,
        )
    }

    @Test
    fun payloadAdminSummaryWinsTheGenericBody() {
        val content =
            content(
                update(
                    trigger = NotificationTriggerFfi.MADE_ADMIN,
                    previewText = "Alice made you an admin",
                ),
            )

        assertEquals("Alice made you an admin", content?.body)
    }

    @Test
    fun resolvedAdminSummaryWinsThePayloadSummary() {
        val content =
            content(
                update(
                    trigger = NotificationTriggerFfi.REMOVED_AS_ADMIN,
                    previewText = "Someone removed you as admin",
                ),
                previewTextOverride = "Alice removed you as admin",
            )

        assertEquals("Alice removed you as admin", content?.body)
    }

    @Test
    fun selfAuthoredAdminRoleChangeIsSuppressed() {
        assertNull(content(update(trigger = NotificationTriggerFfi.MADE_ADMIN, isFromSelf = true)))
        assertNull(content(update(trigger = NotificationTriggerFfi.REMOVED_AS_ADMIN, isFromSelf = true)))
    }

    @Test
    fun directMessagesNeverRenderAdminRoleNotifications() {
        assertNull(content(update(trigger = NotificationTriggerFfi.MADE_ADMIN, isDm = true)))
        assertNull(content(update(trigger = NotificationTriggerFfi.REMOVED_AS_ADMIN, isDm = true)))
    }

    private fun content(
        update: NotificationUpdateFfi,
        previewTextOverride: String? = null,
    ): LocalNotificationContent? =
        LocalNotificationFormatter.content(
            update = update,
            previewTextOverride = previewTextOverride,
            shortNpub = { SAMPLE_SHORT_NPUB },
        )

    private fun update(
        trigger: NotificationTriggerFfi,
        groupName: String? = "General",
        previewText: String? = null,
        isFromSelf: Boolean = false,
        isDm: Boolean = false,
    ) = NotificationUpdateFfi(
        isMention = false,
        notificationKey = "membership:account:message",
        conversationKey = "conversation:account:group",
        trigger = trigger,
        trafficClass = NotificationTrafficClassFfi.STANDARD,
        accountRef = "account",
        accountIdHex = "account",
        groupIdHex = "group",
        groupName = groupName,
        isDm = isDm,
        messageIdHex = "message",
        sender = user(),
        receiver = user(accountIdHex = "account", displayName = "Me"),
        previewText = previewText,
        timestampMs = 1234,
        isFromSelf = isFromSelf,
        reactionEmoji = null,
        reactedToPreview = null,
    )

    private fun user(
        accountIdHex: String = SAMPLE_ACCOUNT_ID_HEX,
        displayName: String? = null,
    ) = NotificationUserFfi(
        accountIdHex = accountIdHex,
        displayName = displayName,
        pictureUrl = null,
    )

    private companion object {
        const val SAMPLE_ACCOUNT_ID_HEX = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val SAMPLE_SHORT_NPUB = "npub1qy352...hstefp92"
    }
}
