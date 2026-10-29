package dev.ipf.whitenoise.android.notifications

import androidx.core.app.NotificationCompat
import dev.ipf.marmotkit.NotificationTrafficClassFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
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
    }

    @Test
    fun agentActivityUsesPlainStyleAndNoReplyActions() {
        val decision =
            decision(
                update(
                    trigger = NotificationTriggerFfi.NEW_MESSAGE,
                    trafficClass = NotificationTrafficClassFfi.AGENT_ACTIVITY,
                ),
            )

        assertSame(NotificationStyleChoice.Plain, decision?.style)
        assertEquals(emptyList<NotificationActionKind>(), decision?.actions)
        assertEquals(0, decision?.historyCap)
    }

    @Test
    fun newMessageUsesMessagingStyleAndReplyAndMarkReadActions() {
        val decision = decision(update(trigger = NotificationTriggerFfi.NEW_MESSAGE, reactionEmoji = null))

        assertSame(NotificationStyleChoice.Messaging, decision?.style)
        assertEquals(
            listOf(
                NotificationActionKind.REPLY,
                NotificationActionKind.MARK_READ,
            ),
            decision?.actions,
        )
        assertEquals(CARRIED_NOTIFICATION_MESSAGE_HISTORY_CAP, decision?.historyCap)
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
    }

    @Test
    fun groupInviteWithBlankGroupIdUsesPlainStyleAndNoActions() {
        val decision = decision(update(trigger = NotificationTriggerFfi.GROUP_INVITE, groupIdHex = ""))

        assertSame(NotificationStyleChoice.Plain, decision?.style)
        assertEquals(emptyList<NotificationActionKind>(), decision?.actions)
        assertEquals(0, decision?.historyCap)
    }

    @Test
    fun removalUsesPlainEventStyleOnTheMembershipChannel() {
        val decision = decision(update(trigger = NotificationTriggerFfi.REMOVED_FROM_GROUP))

        assertSame(NotificationStyleChoice.Plain, decision?.style)
        assertEquals(NotificationChannelSpec.GROUP_MEMBERSHIP.id, decision?.channelId)
        assertEquals(NotificationCompat.CATEGORY_EVENT, decision?.category)
        assertEquals(emptyList<NotificationActionKind>(), decision?.actions)
    }

    @Test
    fun adminRoleChangesUsePlainEventStyleOnTheMembershipChannel() {
        listOf(
            NotificationTriggerFfi.MADE_ADMIN,
            NotificationTriggerFfi.REMOVED_AS_ADMIN,
        ).forEach { trigger ->
            val decision = decision(update(trigger = trigger))

            assertSame(NotificationStyleChoice.Plain, decision?.style)
            assertEquals(NotificationChannelSpec.GROUP_MEMBERSHIP.id, decision?.channelId)
            assertEquals(NotificationCompat.CATEGORY_EVENT, decision?.category)
            assertEquals(emptyList<NotificationActionKind>(), decision?.actions)
        }
    }

    @Test
    fun historyCapCarriesOnlyPreviousMessagesThatFitBesideTheNewOne() {
        val decision = decision(update(trigger = NotificationTriggerFfi.NEW_MESSAGE)) ?: error("missing decision")
        val oldHistory = (1..30).toList()

        assertEquals(24, decision.historyCap)
        assertEquals((7..30).toList(), capNotificationHistory(oldHistory, decision.historyCap))
    }

    @Test
    fun messageBodyCapCountsUnicodeCodePointsWithoutSplittingSurrogatePairs() {
        val emoji = "\uD83D\uDE00"
        val oversized = emoji.repeat(MAX_NOTIFICATION_MESSAGE_BODY_CODE_POINTS + 1)

        val bounded = boundedNotificationMessageText(oversized)

        assertEquals(MAX_NOTIFICATION_MESSAGE_BODY_CODE_POINTS, bounded.codePointCount(0, bounded.length))
        assertEquals(emoji, bounded.takeLast(emoji.length))
    }

    /** The body safety bound still applies independently of the native conversation template. */
    @Test
    fun safetyBoundStillCutsAnOversizedBody() {
        val bounded = boundedNotificationMessageText(LongBodies.pastSafetyBound())

        assertEquals(MAX_NOTIFICATION_MESSAGE_BODY_CODE_POINTS, LongBodies.codePoints(bounded))
        assertEquals(LongBodies.pastSafetyBound().take(MAX_NOTIFICATION_MESSAGE_BODY_CODE_POINTS), bounded)
        assertEquals(LongBodies.atSafetyBound(), boundedNotificationMessageText(LongBodies.atSafetyBound()))
    }

    /** The per-activity cap counts manifest and other dynamic shortcuts too, so a full set makes room first. */
    @Test
    fun conversationShortcutPruneCountCountsManifestAndOtherDynamicShortcutsAgainstTheCap() {
        val conversations = (1..13).map { "${CONVERSATION_SHORTCUT_PREFIX}$it" }.toSet()
        val dynamic = conversations + "${CONVERSATION_SHORTCUT_PREFIX}14"
        val new = "${CONVERSATION_SHORTCUT_PREFIX}new"

        val prune = { ids: Set<String>, id: String -> conversationShortcutPruneCount(ids, 1, 15, id) }
        assertEquals("14 conversations and a manifest shortcut fill the cap", 1, prune(dynamic, new))
        assertEquals(0, prune(conversations, new))
        assertEquals("another dynamic shortcut counts too", 2, prune(dynamic + "other", new))
        assertEquals("republishing an existing shortcut needs no room", 0, prune(dynamic, dynamic.first()))
    }

    /** Pruning never removes a shortcut backing a live card or held for a pending write, and gives up without room. */
    @Test
    fun conversationShortcutPrunePlanSparesLiveAndHeldShortcuts() {
        val ids = (1..14).map { "${CONVERSATION_SHORTCUT_PREFIX}$it" }.toSet()
        val new = "${CONVERSATION_SHORTCUT_PREFIX}new"
        val lastUsed = ids.withIndex().associate { (index, id) -> id to index.toLong() }
        val plan = { protectedIds: Set<String> ->
            conversationShortcutPrunePlan(ids, 1, 15, new, protectedIds, lastUsed)
        }

        assertEquals(listOf("${CONVERSATION_SHORTCUT_PREFIX}1"), plan(emptySet()))
        assertEquals(
            "the least recently used unprotected shortcut goes instead",
            listOf("${CONVERSATION_SHORTCUT_PREFIX}2"),
            plan(setOf("${CONVERSATION_SHORTCUT_PREFIX}1")),
        )
        assertEquals("protected shortcuts filling the cap skip the publication", null, plan(ids))
        assertEquals(
            "a cap with room needs no removal",
            emptyList<String>(),
            conversationShortcutPrunePlan(ids - ids.first(), 1, 15, new, ids, lastUsed),
        )
    }

    /** Overlapping posts for one conversation each hold its shortcut, which stays held until the last releases. */
    @Test
    fun inFlightShortcutHoldsAreCountedPerPost() {
        InFlightConversationShortcuts.clearForTests()
        val id = "${CONVERSATION_SHORTCUT_PREFIX}shared"
        InFlightConversationShortcuts.hold(id, nowMs = 0)
        InFlightConversationShortcuts.hold(id, nowMs = 1)

        InFlightConversationShortcuts.release(id)
        assertEquals("the second post still holds it", setOf(id), InFlightConversationShortcuts.ids(nowMs = 2))
        InFlightConversationShortcuts.release(id)
        assertEquals(emptySet<String>(), InFlightConversationShortcuts.ids(nowMs = 3))

        InFlightConversationShortcuts.hold(id, nowMs = 0)
        assertEquals("an unreleased hold lapses", emptySet<String>(), InFlightConversationShortcuts.ids(nowMs = 60_001))
    }

    @Test
    fun conversationShortcutRemovalOrderRemovesLeastRecentlyUsedFirst() {
        val existing =
            setOf(
                "${CONVERSATION_SHORTCUT_PREFIX}a",
                "${CONVERSATION_SHORTCUT_PREFIX}b",
                "${CONVERSATION_SHORTCUT_PREFIX}c",
            )
        val lastUsed =
            mapOf(
                "${CONVERSATION_SHORTCUT_PREFIX}a" to 30L,
                "${CONVERSATION_SHORTCUT_PREFIX}b" to 10L,
                "${CONVERSATION_SHORTCUT_PREFIX}c" to 20L,
            )

        assertEquals(
            listOf("${CONVERSATION_SHORTCUT_PREFIX}b", "${CONVERSATION_SHORTCUT_PREFIX}c"),
            conversationShortcutRemovalOrder(
                existingShortcutIds = existing,
                lastUsed = lastUsed,
                protectedShortcutId = "${CONVERSATION_SHORTCUT_PREFIX}a",
            ).take(2),
        )
    }

    @Test
    fun conversationShortcutRemovalOrderTreatsUnknownUsageAsOldestButNeverRemovesProtectedShortcut() {
        val existing =
            setOf(
                "${CONVERSATION_SHORTCUT_PREFIX}current",
                "${CONVERSATION_SHORTCUT_PREFIX}old",
                "${CONVERSATION_SHORTCUT_PREFIX}known",
            )
        val lastUsed = mapOf("${CONVERSATION_SHORTCUT_PREFIX}known" to 1L)

        assertEquals(
            listOf("${CONVERSATION_SHORTCUT_PREFIX}old", "${CONVERSATION_SHORTCUT_PREFIX}known"),
            conversationShortcutRemovalOrder(
                existingShortcutIds = existing,
                lastUsed = lastUsed,
                protectedShortcutId = "${CONVERSATION_SHORTCUT_PREFIX}current",
            ),
        )
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
                update(trigger = NotificationTriggerFfi.REMOVED_FROM_GROUP) to
                    NotificationChannelSpec.GROUP_MEMBERSHIP,
                update(trigger = NotificationTriggerFfi.MADE_ADMIN) to
                    NotificationChannelSpec.GROUP_MEMBERSHIP,
                update(trigger = NotificationTriggerFfi.REMOVED_AS_ADMIN) to
                    NotificationChannelSpec.GROUP_MEMBERSHIP,
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
        assertEquals(
            NotificationCompat.CATEGORY_EVENT,
            decision(update(trigger = NotificationTriggerFfi.REMOVED_FROM_GROUP))?.category,
        )
        assertEquals(
            NotificationCompat.CATEGORY_EVENT,
            decision(update(trigger = NotificationTriggerFfi.MADE_ADMIN))?.category,
        )
        assertEquals(
            NotificationCompat.CATEGORY_EVENT,
            decision(update(trigger = NotificationTriggerFfi.REMOVED_AS_ADMIN))?.category,
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

    @Test
    fun shouldCancelRepliedConversationCardMatchesGenerationAndFailsClosed() {
        assertTrue(shouldCancelRepliedConversationCard("msg-a", "msg-a"))
        assertFalse(shouldCancelRepliedConversationCard("msg-a", "msg-b"))
        assertFalse(shouldCancelRepliedConversationCard("msg-a", null))
        assertFalse(shouldCancelRepliedConversationCard(null, "msg-a"))
        assertFalse(shouldCancelRepliedConversationCard("", "msg-a"))
        assertFalse(shouldCancelRepliedConversationCard("msg-a", ""))
    }

    private fun decision(update: NotificationUpdateFfi): NotificationPostDecision? =
        decideNotificationPost(
            update = update,
            canPost = true,
            formatterReturnedContent = true,
            spec = NotificationChannelSpec.forUpdate(update),
        )

    /** Builds a typed update whose keys derive from the given account and group, as the decision cases expect. */
    private fun update(
        trigger: NotificationTriggerFfi,
        accountRef: String = "account",
        groupIdHex: String = "group",
        isDm: Boolean = false,
        reactionEmoji: String? = null,
        trafficClass: NotificationTrafficClassFfi = NotificationTrafficClassFfi.STANDARD,
    ) = notificationUpdate(
        trigger = trigger,
        trafficClass = trafficClass,
        accountRef = accountRef,
        groupIdHex = groupIdHex,
        isDm = isDm,
        reactionEmoji = reactionEmoji,
        sender = notificationUser(),
        receiver = notificationUser(accountIdHex = accountRef, displayName = "Me"),
        previewText = "Hello",
    )
}
