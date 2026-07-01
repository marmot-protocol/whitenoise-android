package dev.ipf.whitenoise.android.notifications

import androidx.core.app.NotificationCompat
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNotificationPresenterDecisionTest {
    @Test
    fun skipsWhenNotificationPermissionIsMissing() {
        assertNull(
            decideNotificationPost(
                update = update(trigger = NotificationTriggerFfi.NEW_MESSAGE),
                canPost = false,
                formatterReturnedContent = true,
            ),
        )
    }

    @Test
    fun skipsWhenFormatterReturnedNoContent() {
        assertNull(
            decideNotificationPost(
                update = update(trigger = NotificationTriggerFfi.NEW_MESSAGE),
                canPost = true,
                formatterReturnedContent = false,
            ),
        )
    }

    @Test
    fun reactionUsesPlainStyleAndNoActions() {
        val decision = decision(update(trigger = NotificationTriggerFfi.NEW_MESSAGE, reactionEmoji = "👍"))

        assertSame(NotificationStyleChoice.Plain, decision?.style)
        assertEquals(emptyList<NotificationActionKind>(), decision?.actions)
        assertEquals(0, decision?.historyCap)
        assertFalse(decision?.replaceExistingBeforePost ?: true)
    }

    @Test
    fun newMessageUsesMessagingStyleAndReplyActions() {
        val decision = decision(update(trigger = NotificationTriggerFfi.NEW_MESSAGE, reactionEmoji = null))

        assertSame(NotificationStyleChoice.Messaging, decision?.style)
        assertEquals(listOf(NotificationActionKind.REPLY, NotificationActionKind.MARK_READ), decision?.actions)
        assertEquals(CARRIED_NOTIFICATION_MESSAGE_HISTORY_CAP, decision?.historyCap)
        assertTrue(decision?.replaceExistingBeforePost ?: false)
    }

    @Test
    fun groupInviteUsesInviteExtrasStyleAndNoActions() {
        val decision =
            decision(
                update(
                    trigger = NotificationTriggerFfi.GROUP_INVITE,
                    accountRef = "account-a",
                    groupIdHex = "group-a",
                ),
            )
        val style = decision?.style as? NotificationStyleChoice.InviteWithExtras

        assertEquals("account-a", style?.accountRef)
        assertEquals("group-a", style?.groupIdHex)
        assertEquals(emptyList<NotificationActionKind>(), decision?.actions)
        assertEquals(0, decision?.historyCap)
        assertFalse(decision?.replaceExistingBeforePost ?: true)
    }

    @Test
    fun groupInviteWithBlankGroupIdUsesPlainStyleAndNoActions() {
        val decision = decision(update(trigger = NotificationTriggerFfi.GROUP_INVITE, groupIdHex = ""))

        assertSame(NotificationStyleChoice.Plain, decision?.style)
        assertEquals(emptyList<NotificationActionKind>(), decision?.actions)
        assertEquals(0, decision?.historyCap)
    }

    @Test
    fun historyCapCarriesOnlyPreviousMessagesThatFitBesideTheNewOne() {
        val decision = decision(update(trigger = NotificationTriggerFfi.NEW_MESSAGE)) ?: error("missing decision")
        val oldHistory = (1..30).toList()

        assertEquals(24, decision.historyCap)
        assertEquals((7..30).toList(), capNotificationHistory(oldHistory, decision.historyCap))
    }

    @Test
    fun channelRoutingReturnsConcreteChannelIdsForEachPostKind() {
        val updatesWithExpectedChannels =
            listOf(
                update(trigger = NotificationTriggerFfi.NEW_MESSAGE, isDm = true) to
                    NotificationChannelSpec.DIRECT_MESSAGES,
                update(trigger = NotificationTriggerFfi.NEW_MESSAGE, isDm = false) to
                    NotificationChannelSpec.GROUP_MESSAGES,
                update(trigger = NotificationTriggerFfi.NEW_MESSAGE, isDm = false, reactionEmoji = "❤️") to
                    NotificationChannelSpec.REACTIONS,
                update(trigger = NotificationTriggerFfi.GROUP_INVITE) to
                    NotificationChannelSpec.INVITES,
            )

        updatesWithExpectedChannels.forEach { (update, expectedSpec) ->
            val decision = decision(update)

            assertEquals(expectedSpec.id, decision?.channelId)
            assertEquals(expectedSpec.importance, decision?.importance)
        }
    }

    @Test
    fun categoriesMatchMessageAndInviteTriggers() {
        assertEquals(
            NotificationCompat.CATEGORY_MESSAGE,
            decision(update(trigger = NotificationTriggerFfi.NEW_MESSAGE))?.category,
        )
        assertEquals(
            NotificationCompat.CATEGORY_EVENT,
            decision(update(trigger = NotificationTriggerFfi.GROUP_INVITE))?.category,
        )
    }

    @Test
    fun inviteDismissalRequiresBothAccountAndGroupToMatch() {
        assertTrue(
            shouldDismissInvite(
                extraAccountRef = "account-a",
                extraGroupIdHex = "group-a",
                accountRef = "account-a",
                groupIdHex = "group-a",
            ),
        )
        assertFalse(
            shouldDismissInvite(
                extraAccountRef = "account-b",
                extraGroupIdHex = "group-a",
                accountRef = "account-a",
                groupIdHex = "group-a",
            ),
        )
        assertFalse(
            shouldDismissInvite(
                extraAccountRef = "account-a",
                extraGroupIdHex = "group-b",
                accountRef = "account-a",
                groupIdHex = "group-a",
            ),
        )
    }

    @Test
    fun inviteDismissalRejectsBlankTargets() {
        assertFalse(
            shouldDismissInvite(
                extraAccountRef = "account-a",
                extraGroupIdHex = "group-a",
                accountRef = " ",
                groupIdHex = "group-a",
            ),
        )
        assertFalse(
            shouldDismissInvite(
                extraAccountRef = "account-a",
                extraGroupIdHex = "group-a",
                accountRef = "account-a",
                groupIdHex = " ",
            ),
        )
    }

    private fun decision(update: NotificationUpdateFfi): NotificationPostDecision? =
        decideNotificationPost(
            update = update,
            canPost = true,
            formatterReturnedContent = true,
            spec = NotificationChannelSpec.forUpdate(update),
        )

    private fun update(
        trigger: NotificationTriggerFfi,
        accountRef: String = "account",
        groupIdHex: String = "group",
        isDm: Boolean = false,
        reactionEmoji: String? = null,
    ) = NotificationUpdateFfi(
        isMention = false,
        notificationKey = "message:$accountRef:message",
        conversationKey = "conversation:$accountRef:$groupIdHex",
        trigger = trigger,
        accountRef = accountRef,
        accountIdHex = accountRef,
        groupIdHex = groupIdHex,
        groupName = "General",
        isDm = isDm,
        messageIdHex = "message",
        sender = user(),
        receiver = user(accountIdHex = accountRef, displayName = "Me"),
        previewText = "Hello",
        reactionEmoji = reactionEmoji,
        reactedToPreview = null,
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
