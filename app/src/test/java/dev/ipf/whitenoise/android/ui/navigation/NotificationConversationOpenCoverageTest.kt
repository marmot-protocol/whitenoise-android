package dev.ipf.whitenoise.android.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationConversationOpenCoverageTest {
    @Test
    fun notificationOpenClearsSearchFocusAndRequestsUnreadAnchor() {
        val previous =
            ConversationOpenContext(
                focusMessageId = "search-hit",
                notificationOpenRequestId = 0L,
            )

        val opened = nextNotificationConversationOpenContext(previous)

        assertEquals(null, opened.focusMessageId)
        assertTrue(opened.notificationOpenRequestId > 0L)
    }

    @Test
    fun repeatedNotificationOpenRequestsFreshAnchorForMountedConversation() {
        val first = nextNotificationConversationOpenContext(ConversationOpenContext())

        val repeated = nextNotificationConversationOpenContext(first)

        assertNotEquals(first.notificationOpenRequestId, repeated.notificationOpenRequestId)
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
}
