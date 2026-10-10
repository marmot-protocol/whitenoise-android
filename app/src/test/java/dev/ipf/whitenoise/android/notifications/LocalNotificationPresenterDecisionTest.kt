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

    /** A message moves to the expandable text block at exactly the threshold, never one code point earlier. */
    @Test
    fun expandedStyleBeginsAtExactlyTheThreshold() {
        val threshold = MIN_EXPANDED_SINGLE_MESSAGE_CODE_POINTS

        assertFalse(expanded(LongBodies.plain(threshold - 1)))
        assertTrue(expanded(LongBodies.plain(threshold)))
        assertTrue(expanded(LongBodies.plain(threshold + 1)))
    }

    /** The threshold is positive, so a short message keeps the conversation template, and stays under the bound. */
    @Test
    fun thresholdStaysPositiveAndBelowTheSafetyBound() {
        assertTrue(MIN_EXPANDED_SINGLE_MESSAGE_CODE_POINTS in 1 until MAX_NOTIFICATION_MESSAGE_BODY_CODE_POINTS)
        assertFalse(expanded(""))
        assertFalse(expanded("a"))
        assertFalse(expanded("Short and ordinary."))
    }

    /** Right-to-left and zero-width-joiner bodies are measured in code points, not UTF-16 units or glyphs. */
    @Test
    fun thresholdCountsCodePointsForRtlAndZwjBodies() {
        val threshold = MIN_EXPANDED_SINGLE_MESSAGE_CODE_POINTS

        listOf(LongBodies::rtl, LongBodies::zwj).forEach { body ->
            assertFalse(expanded(body(threshold - 1)))
            assertTrue(expanded(body(threshold)))
        }
        assertTrue(LongBodies.zwj(threshold - 1).length > threshold)
    }

    /** Any carried history keeps the conversation template, so no earlier message is ever dropped. */
    @Test
    fun carriedHistoryKeepsTheConversationTemplateForAnyBodyLength() {
        listOf(
            LongBodies.plain(MIN_EXPANDED_SINGLE_MESSAGE_CODE_POINTS),
            LongBodies.atSafetyBound(),
        ).forEach { body ->
            assertFalse(expanded(body, carriedMessageCount = 1))
            assertFalse(expanded(body, carriedMessageCount = CARRIED_NOTIFICATION_MESSAGE_HISTORY_CAP))
        }
    }

    /** A redacted card carries no message text, so it never uses the expandable text block. */
    @Test
    fun redactedCardNeverUsesTheExpandedTextBlock() {
        assertFalse(expanded(LongBodies.plain(MIN_EXPANDED_SINGLE_MESSAGE_CODE_POINTS), redactContent = true))
        assertFalse(expanded(LongBodies.atSafetyBound(), redactContent = true))
    }

    /** The 1,000 code point bound still cuts an oversized body, and the cut body still takes the text block. */
    @Test
    fun safetyBoundStillCutsAnOversizedBodyThatThenQualifies() {
        val bounded = boundedNotificationMessageText(LongBodies.pastSafetyBound())

        assertEquals(MAX_NOTIFICATION_MESSAGE_BODY_CODE_POINTS, LongBodies.codePoints(bounded))
        assertEquals(LongBodies.pastSafetyBound().take(MAX_NOTIFICATION_MESSAGE_BODY_CODE_POINTS), bounded)
        assertEquals(LongBodies.atSafetyBound(), boundedNotificationMessageText(LongBodies.atSafetyBound()))
        assertTrue(expanded(bounded))
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

    /** Evaluates the style rule for one body, defaulting to a first message on an unredacted card. */
    private fun expanded(
        body: CharSequence,
        carriedMessageCount: Int = 0,
        redactContent: Boolean = false,
    ): Boolean = shouldUseExpandedSingleMessageStyle(body, carriedMessageCount, redactContent)

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
