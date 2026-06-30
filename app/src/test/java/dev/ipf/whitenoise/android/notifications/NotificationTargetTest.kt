package dev.ipf.whitenoise.android.notifications

import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationTargetTest {
    // ---- routeInboundIntent -------------------------------------------------

    private val noPending = InboundIntentRouting(notificationTarget = null, profilePayload = null)

    @Test
    fun routeInboundIntent_notificationTargetWinsAndClearsProfileLink() {
        val target = NotificationTarget("acct-a", "g1", "m1", NotificationTargetKind.MESSAGE)
        val routed =
            routeInboundIntent(
                parsedTarget = target,
                dataString = "whitenoise://profile/npub1abc",
                current = InboundIntentRouting(null, "whitenoise://profile/old"),
            )
        assertEquals(target, routed.notificationTarget)
        assertNull(routed.profilePayload)
    }

    @Test
    fun routeInboundIntent_dataUriBecomesProfilePayload() {
        val routed =
            routeInboundIntent(
                parsedTarget = null,
                dataString = "whitenoise://profile/npub1abc",
                current = noPending,
            )
        assertNull(routed.notificationTarget)
        assertEquals("whitenoise://profile/npub1abc", routed.profilePayload)
    }

    @Test
    fun routeInboundIntent_datalessNonNotificationIntentPreservesPendingDeepLink() {
        // Regression for #67: a bare relaunch intent (no data, not a
        // notification) must not silently discard a queued profile deep link.
        val pending = InboundIntentRouting(null, "whitenoise://profile/npub1pending")
        val routed = routeInboundIntent(parsedTarget = null, dataString = null, current = pending)
        assertEquals(pending, routed)
    }

    @Test
    fun routeInboundIntent_datalessIntentPreservesPendingNotificationTarget() {
        val target = NotificationTarget("acct-a", "g1", "m1", NotificationTargetKind.MESSAGE)
        val pending = InboundIntentRouting(target, null)
        val routed = routeInboundIntent(parsedTarget = null, dataString = null, current = pending)
        assertEquals(pending, routed)
    }

    // ---- fromUpdate ---------------------------------------------------------

    @Test
    fun fromUpdate_messageNotification_carriesMessageId() {
        val target = NotificationNavigation.fromUpdate(update(messageId = "m1"))
        assertEquals(
            NotificationTarget("acct-a", "group-1", "m1", NotificationTargetKind.MESSAGE),
            target,
        )
    }

    @Test
    fun fromUpdate_invite_dropsMessageId() {
        val target =
            NotificationNavigation.fromUpdate(
                update(trigger = NotificationTriggerFfi.GROUP_INVITE, messageId = "m1"),
            )
        assertEquals(NotificationTargetKind.INVITE, target?.kind)
        assertNull("invite target should not carry a message id", target?.messageIdHex)
    }

    @Test
    fun fromUpdate_blankAccountOrGroup_returnsNull() {
        assertNull(NotificationNavigation.fromUpdate(update(accountRef = "")))
        assertNull(NotificationNavigation.fromUpdate(update(groupIdHex = "")))
    }

    // ---- parseExtras (untrusted intent fields) ------------------------------

    @Test
    fun parseExtras_validMessage_roundTrips() {
        val target =
            NotificationNavigation.parseExtras(
                action = NotificationNavigation.ACTION_OPEN,
                accountRef = "acct-a",
                groupIdHex = "group-1",
                messageIdHex = "m1",
                kindName = "MESSAGE",
            )
        assertEquals(NotificationTarget("acct-a", "group-1", "m1", NotificationTargetKind.MESSAGE), target)
    }

    @Test
    fun parseExtras_wrongAction_returnsNull() {
        assertNull(
            NotificationNavigation.parseExtras("android.intent.action.VIEW", "a", "g", null, "MESSAGE"),
        )
    }

    @Test
    fun parseExtras_missingFieldsOrBadKind_returnNull() {
        val ok = NotificationNavigation.ACTION_OPEN
        assertNull(NotificationNavigation.parseExtras(ok, null, "g", null, "MESSAGE"))
        assertNull(NotificationNavigation.parseExtras(ok, "a", " ", null, "MESSAGE"))
        assertNull(NotificationNavigation.parseExtras(ok, "a", "g", null, "NONSENSE"))
        assertNull(NotificationNavigation.parseExtras(ok, "a", "g", null, null))
    }

    @Test
    fun parseExtras_inviteIgnoresMessageId() {
        val target =
            NotificationNavigation.parseExtras(
                NotificationNavigation.ACTION_OPEN,
                "a",
                "g",
                "m1",
                "INVITE",
            )
        assertNull(target?.messageIdHex)
    }

    // ---- PendingIntent identity --------------------------------------------

    @Test
    fun requestCode_isStablePerKeyAndDistinctAcrossKeys() {
        assertEquals(
            NotificationNavigation.requestCode("key-1"),
            NotificationNavigation.requestCode("key-1"),
        )
        assertNotEquals(
            NotificationNavigation.requestCode("key-1"),
            NotificationNavigation.requestCode("key-2"),
        )
    }

    @Test
    fun targetUriString_isDistinctPerKey() {
        assertNotEquals(
            NotificationNavigation.targetUriString("key-1"),
            NotificationNavigation.targetUriString("key-2"),
        )
        // Blank key still yields a usable, identical URI (not an exception).
        assertEquals(
            NotificationNavigation.targetUriString(""),
            NotificationNavigation.targetUriString(""),
        )
    }

    // ---- Notification actions ----------------------------------------------

    @Test
    fun actionTarget_messageNotification_requiresMessageTarget() {
        val target =
            NotificationActions.targetFromUpdate(
                update(messageId = "m1"),
                notificationTag = "acct-a|group-1",
                notificationId = 0,
            )
        assertEquals(
            NotificationActionTarget(
                NotificationTarget("acct-a", "group-1", "m1", NotificationTargetKind.MESSAGE),
                "acct-a|group-1",
                0,
            ),
            target,
        )
        assertNull(
            NotificationActions.targetFromUpdate(
                update(trigger = NotificationTriggerFfi.GROUP_INVITE, messageId = "m1"),
                notificationTag = "invite-key",
                notificationId = 0,
            ),
        )
        assertNull(
            NotificationActions.targetFromUpdate(
                update(messageId = null),
                notificationTag = "acct-a|group-1",
                notificationId = 0,
            ),
        )
        assertNull(
            NotificationActions.targetFromUpdate(
                update(messageId = "m1"),
                notificationTag = " ",
                notificationId = 0,
            ),
        )
    }

    @Test
    fun actionParseFields_validReply_roundTrips() {
        val action =
            NotificationActions.parseRawFields(
                action = NotificationActions.ACTION_REPLY,
                accountRef = "acct-a",
                groupIdHex = "group-1",
                messageIdHex = "m1",
                targetKindName = "MESSAGE",
                notificationTag = "acct-a|group-1",
                notificationId = 0,
            )
        assertEquals(
            NotificationAction(
                NotificationActionKind.REPLY,
                NotificationTarget("acct-a", "group-1", "m1", NotificationTargetKind.MESSAGE),
                "acct-a|group-1",
                0,
            ),
            action,
        )
    }

    @Test
    fun actionParseFields_rejectsUntrustedOrIncompletePayloads() {
        assertNull(
            NotificationActions.parseRawFields(
                action = "android.intent.action.VIEW",
                accountRef = "acct-a",
                groupIdHex = "group-1",
                messageIdHex = "m1",
                targetKindName = "MESSAGE",
                notificationTag = "acct-a|group-1",
                notificationId = 0,
            ),
        )
        assertNull(
            NotificationActions.parseRawFields(
                action = NotificationActions.ACTION_MARK_READ,
                accountRef = "acct-a",
                groupIdHex = "group-1",
                messageIdHex = null,
                targetKindName = "MESSAGE",
                notificationTag = "acct-a|group-1",
                notificationId = 0,
            ),
        )
        assertNull(
            NotificationActions.parseRawFields(
                action = NotificationActions.ACTION_MARK_READ,
                accountRef = "acct-a",
                groupIdHex = "group-1",
                messageIdHex = "m1",
                targetKindName = "INVITE",
                notificationTag = "acct-a|group-1",
                notificationId = 0,
            ),
        )
        assertNull(
            NotificationActions.parseRawFields(
                action = NotificationActions.ACTION_MARK_READ,
                accountRef = "acct-a",
                groupIdHex = "group-1",
                messageIdHex = "m1",
                targetKindName = "MESSAGE",
                notificationTag = " ",
                notificationId = 0,
            ),
        )
        assertNull(
            NotificationActions.parseRawFields(
                action = NotificationActions.ACTION_MARK_READ,
                accountRef = "acct-a",
                groupIdHex = "group-1",
                messageIdHex = "m1",
                targetKindName = "MESSAGE",
                notificationTag = "acct-a|group-1",
                notificationId = null,
            ),
        )
    }

    @Test
    fun actionIdentity_isDistinctPerKindAndTag() {
        assertNotEquals(
            NotificationActions.requestCode(NotificationActionKind.REPLY, "acct-a|group-1"),
            NotificationActions.requestCode(NotificationActionKind.MARK_READ, "acct-a|group-1"),
        )
        assertNotEquals(
            NotificationActions.requestCode(NotificationActionKind.REPLY, "acct-a|group-1"),
            NotificationActions.requestCode(NotificationActionKind.REPLY, "acct-a|group-2"),
        )
        assertNotEquals(
            NotificationActions.actionUriString(NotificationActionKind.REPLY, "acct-a|group-1"),
            NotificationActions.actionUriString(NotificationActionKind.MARK_READ, "acct-a|group-1"),
        )
    }

    @Test
    fun replyActionHandled_gatesOnSentOnly_markReadIsBestEffort() {
        // A successful send must dismiss the notification regardless of whether
        // the best-effort mark-read step succeeded. Gating on mark-read would
        // leave the inline RemoteInput field active and let the user re-send,
        // posting a duplicate message to the group (issue #272).
        assertTrue(notificationReplyActionHandled(sent = true))
        assertFalse(notificationReplyActionHandled(sent = false))
    }

    @Test
    fun replyActionBudget_reservesDismissHeadroomInsideGoAsyncBudget() {
        // Use production defaults so this catches drift in the receiver's
        // cooperative send/dismiss budget wiring, not just helper arithmetic.
        val dismissBudget = notificationReplyDismissBudgetMs()
        val sendBudget = notificationReplySendPhaseBudgetMs(dismissBudgetMs = dismissBudget)

        assertEquals(950L, dismissBudget)
        assertEquals(6_750L, sendBudget)
        assertEquals(300L, 8_000L - sendBudget - dismissBudget)
    }

    // ---- resolveNotificationNav (routing FSM) -------------------------------

    private val target = NotificationTarget("acct-a", "group-1", null, NotificationTargetKind.MESSAGE)

    @Test
    fun nav_unknownAccount_isMissingAccount() {
        val step =
            resolveNotificationNav(
                target,
                knownAccountRefs = setOf("acct-b"),
                activeAccountRef = "acct-b",
                chatListReady = true,
                availableGroupIds = setOf("group-1"),
            )
        assertEquals(NotificationNavStep.MissingAccount, step)
    }

    @Test
    fun nav_backgroundAccount_switchesFirst() {
        val step =
            resolveNotificationNav(
                target,
                knownAccountRefs = setOf("acct-a", "acct-b"),
                activeAccountRef = "acct-b",
                chatListReady = true,
                availableGroupIds = setOf("group-1"),
            )
        assertEquals(NotificationNavStep.SwitchAccount("acct-a"), step)
    }

    @Test
    fun nav_activeAccount_doesNotSwitch_andOpensWhenReady() {
        val step =
            resolveNotificationNav(
                target,
                knownAccountRefs = setOf("acct-a"),
                activeAccountRef = "acct-a",
                chatListReady = true,
                availableGroupIds = setOf("group-1"),
            )
        assertEquals(NotificationNavStep.OpenConversation("group-1", null), step)
    }

    @Test
    fun nav_messageNotificationOpen_carriesMessageFocus() {
        val step =
            resolveNotificationNav(
                target.copy(messageIdHex = "message-123"),
                knownAccountRefs = setOf("acct-a"),
                activeAccountRef = "acct-a",
                chatListReady = true,
                availableGroupIds = setOf("group-1"),
            )
        assertEquals(NotificationNavStep.OpenConversation("group-1", "message-123"), step)
    }

    @Test
    fun nav_activeAccountButChatListNotReady_awaits() {
        val step =
            resolveNotificationNav(
                target,
                knownAccountRefs = setOf("acct-a"),
                activeAccountRef = "acct-a",
                chatListReady = false,
                availableGroupIds = emptySet(),
            )
        assertEquals(NotificationNavStep.AwaitChatList, step)
    }

    @Test
    fun nav_readyButGroupAbsent_isMissingConversation() {
        val step =
            resolveNotificationNav(
                target,
                knownAccountRefs = setOf("acct-a"),
                activeAccountRef = "acct-a",
                chatListReady = true,
                availableGroupIds = setOf("group-other"),
            )
        assertEquals(NotificationNavStep.MissingConversation, step)
    }

    // ---- helpers ------------------------------------------------------------

    private fun update(
        accountRef: String = "acct-a",
        groupIdHex: String = "group-1",
        messageId: String? = "m1",
        trigger: NotificationTriggerFfi = NotificationTriggerFfi.NEW_MESSAGE,
    ): NotificationUpdateFfi =
        NotificationUpdateFfi(
            isMention = false,
            notificationKey = "key-1",
            conversationKey = "conv-1",
            trigger = trigger,
            accountRef = accountRef,
            accountIdHex = "acctid-a",
            groupIdHex = groupIdHex,
            groupName = "Group",
            isDm = false,
            messageIdHex = messageId,
            sender = NotificationUserFfi("sender-hex", "Sender", null),
            receiver = NotificationUserFfi("receiver-hex", "Me", null),
            previewText = "hi",
            reactionEmoji = null,
            reactedToPreview = null,
            timestampMs = 0L,
            isFromSelf = false,
        )
}
