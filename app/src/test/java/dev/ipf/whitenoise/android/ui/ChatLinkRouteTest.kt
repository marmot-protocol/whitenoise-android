package dev.ipf.whitenoise.android.ui

import dev.ipf.whitenoise.android.notifications.InboundIntentRouting
import dev.ipf.whitenoise.android.notifications.NotificationNavStep
import dev.ipf.whitenoise.android.notifications.NotificationTarget
import dev.ipf.whitenoise.android.notifications.NotificationTargetKind
import dev.ipf.whitenoise.android.notifications.resolveNotificationNav
import dev.ipf.whitenoise.android.notifications.routeInboundIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A resolved chat link enters the notification route, so it inherits cold/warm start, app lock and account
 * switching.
 */
class ChatLinkRouteTest {
    private val groupId = "0123456789abcdef0123456789abcdef"
    private val link = "marmot://chat/$groupId"

    @Test
    fun chatLinkTarget_opensTheChatWithoutAMessageAnchor() {
        assertEquals(
            NotificationTarget("acct-a", groupId, messageIdHex = null, kind = NotificationTargetKind.MESSAGE),
            chatLinkTarget("acct-a", groupId),
        )
    }

    @Test
    fun coldStart_chatLinkReplacesItsPendingPayloadWithANotificationRoute() {
        // Cold start: the link arrived with the launch intent and is still pending as a payload.
        val pending = InboundIntentRouting(notificationTarget = null, profilePayload = link, shareRequest = null)
        val routed =
            routeInboundIntent(
                parsedTarget = chatLinkTarget("acct-a", groupId),
                shareRequest = null,
                dataString = null,
                current = pending,
            )
        assertEquals(chatLinkTarget("acct-a", groupId), routed.notificationTarget)
        assertNull(routed.profilePayload)
        assertEquals(1L, routed.notificationRequestId)
    }

    @Test
    fun warmStartWithAnotherChatOpen_chatLinkIsANewRouteRequest() {
        // Warm start: an earlier notification route already opened another chat.
        val earlier = NotificationTarget("acct-a", "ff".repeat(16), "m1", NotificationTargetKind.MESSAGE)
        val current = InboundIntentRouting(null, link, null, notificationRequestId = 4L)
        val routed =
            routeInboundIntent(
                parsedTarget = chatLinkTarget("acct-a", groupId),
                shareRequest = null,
                dataString = null,
                current = current,
            )
        assertEquals(chatLinkTarget("acct-a", groupId), routed.notificationTarget)
        assertEquals(5L, routed.notificationRequestId)
        assertEquals(
            NotificationNavStep.OpenConversation(groupId, null),
            resolveNotificationNav(
                target = requireNotNull(routed.notificationTarget),
                knownAccountRefs = setOf("acct-a"),
                activeAccountRef = "acct-a",
                chatListReady = true,
                availableGroupIds = setOf(groupId, earlier.groupIdHex),
            ),
        )
    }

    @Test
    fun chatLinkHeldByInactiveAccount_switchesToThatAccountFirst() {
        assertEquals(
            NotificationNavStep.SwitchAccount("acct-b"),
            resolveNotificationNav(
                target = chatLinkTarget("acct-b", groupId),
                knownAccountRefs = setOf("acct-a", "acct-b"),
                activeAccountRef = "acct-a",
                chatListReady = true,
                availableGroupIds = emptySet(),
            ),
        )
    }
}
