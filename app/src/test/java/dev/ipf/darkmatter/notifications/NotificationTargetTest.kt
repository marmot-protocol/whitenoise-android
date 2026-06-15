package dev.ipf.darkmatter.notifications

import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
                dataString = "darkmatter://profile/npub1abc",
                current = InboundIntentRouting(null, "darkmatter://profile/old"),
            )
        assertEquals(target, routed.notificationTarget)
        assertNull(routed.profilePayload)
    }

    @Test
    fun routeInboundIntent_dataUriBecomesProfilePayload() {
        val routed =
            routeInboundIntent(
                parsedTarget = null,
                dataString = "darkmatter://profile/npub1abc",
                current = noPending,
            )
        assertNull(routed.notificationTarget)
        assertEquals("darkmatter://profile/npub1abc", routed.profilePayload)
    }

    @Test
    fun routeInboundIntent_datalessNonNotificationIntentPreservesPendingDeepLink() {
        // Regression for #67: a bare relaunch intent (no data, not a
        // notification) must not silently discard a queued profile deep link.
        val pending = InboundIntentRouting(null, "darkmatter://profile/npub1pending")
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
        assertEquals(NotificationNavStep.OpenConversation("group-1"), step)
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
