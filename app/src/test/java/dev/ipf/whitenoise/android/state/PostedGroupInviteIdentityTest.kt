package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.NotificationTrafficClassFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PostedGroupInviteIdentityTest {
    @Test
    fun redactedPostedInviteIsTrackedWithoutDisplayedIdentity() {
        val update = update(NotificationTriggerFfi.GROUP_INVITE)

        val identity =
            postedGroupInviteIdentity(
                update = update,
                posted = true,
                redactContent = true,
                displayedName = "Alice",
            )

        assertEquals(update, identity?.update)
        assertNull(identity?.displayedName)
    }

    @Test
    fun unredactedPostedInviteRetainsDisplayedName() {
        val identity =
            postedGroupInviteIdentity(
                update = update(NotificationTriggerFfi.GROUP_INVITE),
                posted = true,
                redactContent = false,
                displayedName = "Alice",
            )

        assertEquals("Alice", identity?.displayedName)
    }

    @Test
    fun unpostedOrNonInviteNotificationsAreNotTracked() {
        assertNull(
            postedGroupInviteIdentity(
                update = update(NotificationTriggerFfi.GROUP_INVITE),
                posted = false,
                redactContent = true,
                displayedName = null,
            ),
        )
        assertNull(
            postedGroupInviteIdentity(
                update = update(NotificationTriggerFfi.NEW_MESSAGE),
                posted = true,
                redactContent = false,
                displayedName = "Alice",
            ),
        )
    }

    private fun update(trigger: NotificationTriggerFfi) =
        NotificationUpdateFfi(
            notificationKey = "invite:account-a:group-a",
            conversationKey = "conversation:account-a:group-a",
            trigger = trigger,
            trafficClass = NotificationTrafficClassFfi.STANDARD,
            accountRef = "account-a",
            accountIdHex = "self",
            groupIdHex = "group-a",
            groupName = null,
            isDm = true,
            isMention = false,
            messageIdHex = null,
            sender = NotificationUserFfi("alice", displayName = "Alice", pictureUrl = null),
            receiver = NotificationUserFfi("self", displayName = "Me", pictureUrl = null),
            previewText = null,
            reactionEmoji = null,
            reactedToPreview = null,
            timestampMs = 1L,
            isFromSelf = false,
        )
}
