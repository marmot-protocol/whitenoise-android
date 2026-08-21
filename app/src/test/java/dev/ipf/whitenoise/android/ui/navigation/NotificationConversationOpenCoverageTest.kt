package dev.ipf.whitenoise.android.ui.navigation

import dev.ipf.whitenoise.android.notifications.NotificationTarget
import dev.ipf.whitenoise.android.notifications.NotificationTargetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationConversationOpenCoverageTest {
    @Test
    fun messageNotificationReplacesSearchFocusWithTheExactTappedMessage() {
        val previous =
            ConversationOpenContext(
                focusMessageId = "search-hit",
                focusMessageRequestId = 4L,
                notificationOpenRequestId = 0L,
            )

        val opened =
            nextNotificationConversationOpenContext(
                current = previous,
                focusMessageId = "notification-message",
            )

        assertEquals("notification-message", opened.focusMessageId)
        assertEquals(5L, opened.focusMessageRequestId)
        assertTrue(opened.notificationOpenRequestId > 0L)
    }

    @Test
    fun notificationWithoutAnExplicitMessageKeepsTheOldestUnreadPolicy() {
        val opened = nextNotificationConversationOpenContext(ConversationOpenContext())

        assertEquals(null, opened.focusMessageId)
        assertTrue(opened.notificationOpenRequestId > 0L)
    }

    @Test
    fun repeatedNotificationOpenRequestsFreshAnchorForMountedConversation() {
        val first =
            nextNotificationConversationOpenContext(
                current = ConversationOpenContext(),
                focusMessageId = "same-message",
            )

        val repeated =
            nextNotificationConversationOpenContext(
                current = first,
                focusMessageId = "same-message",
            )

        assertNotEquals(first.notificationOpenRequestId, repeated.notificationOpenRequestId)
        assertNotEquals(first.focusMessageRequestId, repeated.focusMessageRequestId)
    }

    @Test
    fun notificationOpenCarriesProductionTraceOwnershipToFirstFrame() {
        val opened =
            nextNotificationConversationOpenContext(
                current = ConversationOpenContext(),
                notificationRouteTraceRequestId = 73L,
            )

        assertEquals(73L, opened.notificationRouteTraceRequestId)
    }

    @Test
    fun backFromNotificationLoadingCancelsAndSettlesTheExactRoute() {
        val target = NotificationTarget("secondary", "group", "message", NotificationTargetKind.MESSAGE)
        val events = mutableListOf<String>()

        assertTrue(
            cancelNotificationLoadingRoute(
                target = target,
                requestId = 73L,
                onHandled = { handled, requestId -> events += "handled:${handled.groupIdHex}:$requestId" },
                finishTrace = { requestId -> events += "trace:$requestId" },
                onSettled = { requestId -> events += "settled:$requestId" },
            ),
        )
        assertEquals(listOf("handled:group:73", "trace:73", "settled:73"), events)
    }

    @Test
    fun backCancellationIgnoresAnAlreadyClearedRoute() {
        assertFalse(
            cancelNotificationLoadingRoute(
                target = null,
                requestId = 73L,
                onHandled = { _, _ -> error("must not handle") },
                finishTrace = { error("must not finish") },
                onSettled = { error("must not settle") },
            ),
        )
    }
}
