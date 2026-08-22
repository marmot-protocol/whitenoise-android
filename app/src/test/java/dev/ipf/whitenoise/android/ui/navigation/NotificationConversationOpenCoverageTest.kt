package dev.ipf.whitenoise.android.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    @Test
    fun notificationOpenCarriesDurableReadThroughUntilUnreadBoundaryCapture() {
        val opened =
            nextNotificationConversationOpenContext(
                current = ConversationOpenContext(),
                notificationReadThroughMessageId = "message-1",
            )

        assertEquals("message-1", opened.notificationReadThroughMessageId)
    }

    @Test
    fun inactiveAccountProductionRoutePrioritizesTargetUntilFirstFrame() {
        val source =
            listOf(
                File("src/main/java/dev/ipf/whitenoise/android/ui/navigation/MainShell.kt"),
                File("app/src/main/java/dev/ipf/whitenoise/android/ui/navigation/MainShell.kt"),
            ).first(File::exists)
                .readText()
        val gateCreated = source.indexOf("NotificationRouteFirstFrameGate(")
        val prioritySwitch =
            source.indexOf(
                "AccountSwitchPreloadPolicy.TARGET_CONVERSATION_FIRST",
                startIndex = gateCreated,
            )
        val broadBindGuard = source.indexOf("if (deferNotificationChatListBind) return@LaunchedEffect")
        val firstFrameCallback = source.indexOf("onFirstFrameCommitted = {")
        val release =
            source.indexOf(
                "releaseNotificationFirstFrameGate(requestId)",
                startIndex = firstFrameCallback,
            )

        assertTrue("the production switch route must own a first-frame priority gate", gateCreated >= 0)
        assertTrue("notification activation must select target-first preload", prioritySwitch > gateCreated)
        assertTrue("the broad chat-list bind must honor the priority window", broadBindGuard >= 0)
        assertTrue("the readable conversation frame must release deferred work", release > firstFrameCallback)
    }
}
