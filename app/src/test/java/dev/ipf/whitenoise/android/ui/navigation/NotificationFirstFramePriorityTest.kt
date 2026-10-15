package dev.ipf.whitenoise.android.ui.navigation

import dev.ipf.whitenoise.android.notifications.NotificationRouteFirstFrameGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The process-owned first-frame priority of a notification route: it ends only for a reason the route
 * owns, never because a shell composition went away, and a stale request or runtime cannot end or keep it.
 */
class NotificationFirstFramePriorityTest {
    private var generation = 1
    private val priority = NotificationFirstFramePriority { generation }

    /** A freshly started route exposes its gate until it releases it. */
    @Test
    fun theHeldGateIsExposedUntilItsRouteReleasesIt() {
        val gate = NotificationRouteFirstFrameGate(requestId = 5L, accountRef = "account-b")
        assertNull(priority.gate)

        priority.begin(gate)
        assertSame(gate, priority.gate)

        priority.release(requestId = 5L)
        assertNull(priority.gate)
        assertTrue(gate.isReleased)
    }

    /** A callback from a request that was replaced cannot end the window the newer tap now owns. */
    @Test
    fun aStaleRequestCannotReleaseItsSuccessorsGate() {
        val gate = NotificationRouteFirstFrameGate(requestId = 6L, accountRef = "account-b")
        priority.begin(gate)

        priority.release(requestId = 5L)

        assertSame(gate, priority.gate)
        assertFalse(gate.isReleased)
    }

    /** Releasing without a request id ends whatever window is held, as Back and task removal do. */
    @Test
    fun anUnscopedReleaseEndsTheWindowAndIsIdempotent() {
        val gate = NotificationRouteFirstFrameGate(requestId = 6L, accountRef = "account-b")
        priority.begin(gate)

        priority.release()
        priority.release()

        assertNull(priority.gate)
        assertTrue(gate.isReleased)
    }

    /** A newer tap's gate replaces and releases its predecessor, so work waiting on it can finish. */
    @Test
    fun beginningANewRouteReleasesThePredecessor() {
        val first = NotificationRouteFirstFrameGate(requestId = 1L, accountRef = "account-b")
        val second = NotificationRouteFirstFrameGate(requestId = 2L, accountRef = "account-b")
        priority.begin(first)

        priority.begin(second)

        assertTrue(first.isReleased)
        assertFalse(second.isReleased)
        assertSame(second, priority.gate)
    }

    /** Holding the same gate again is not a replacement and must not release it. */
    @Test
    fun beginningTheSameGateAgainKeepsItLive() {
        val gate = NotificationRouteFirstFrameGate(requestId = 1L, accountRef = "account-b")
        priority.begin(gate)

        priority.begin(gate)

        assertFalse(gate.isReleased)
        assertSame(gate, priority.gate)
    }

    /** A gate issued under a runtime that has since been replaced reads as absent and is released. */
    @Test
    fun aGateOutlivingItsRuntimeGenerationIsReleased() {
        val gate = NotificationRouteFirstFrameGate(requestId = 1L, accountRef = "account-b")
        priority.begin(gate)

        assertEquals(1L, priority.heldRequestId)
        generation += 1

        assertNull("the watchdog must stop timing a gate whose runtime is gone", priority.heldRequestId)
        assertNull(priority.gate)
        assertTrue(gate.isReleased)
    }

    /** A retired route's owned release ends only its own gate, even when its successor reuses the request id. */
    @Test
    fun anOwnedReleaseFromARetiredRouteKeepsASuccessorWithTheSameRequestId() {
        val retired = NotificationRouteFirstFrameGate(requestId = 5L, accountRef = "account-b")
        val successor = NotificationRouteFirstFrameGate(requestId = 5L, accountRef = "account-b")
        priority.begin(retired)
        priority.begin(successor)

        priority.releaseOwned(retired)

        assertTrue(retired.isReleased)
        assertFalse("the successor keeps its window", successor.isReleased)
        assertSame(successor, priority.gate)
    }

    /** After a runtime replacement, the old runtime's held callback cannot end the new runtime's window. */
    @Test
    fun anOwnedReleaseAfterARuntimeReplacementLeavesTheNewRuntimesGate() {
        val retired = NotificationRouteFirstFrameGate(requestId = 1L, accountRef = "account-b")
        priority.begin(retired)
        generation += 1
        val successor = NotificationRouteFirstFrameGate(requestId = 1L, accountRef = "account-b")
        priority.begin(successor)

        priority.releaseOwned(retired)

        assertSame(successor, priority.gate)
        assertEquals(1L, priority.heldRequestId)
        assertFalse(successor.isReleased)
    }

    /** An owned release of the gate still held ends the window, as a never-landing switch must. */
    @Test
    fun anOwnedReleaseOfTheHeldGateEndsTheWindow() {
        val gate = NotificationRouteFirstFrameGate(requestId = 3L, accountRef = "account-b")
        priority.begin(gate)

        priority.releaseOwned(gate)
        priority.releaseOwned(gate)

        assertNull(priority.gate)
        assertTrue(gate.isReleased)
    }

    /** The shell stays composed only for a selected, notification-owned conversation whose list is not loaded. */
    @Test
    fun theShellIsOwnedOnlyWhileAnOwnedConversationWaitsForItsChatList() {
        assertTrue(priority.ownsShell(route()))
        assertFalse("no conversation is selected", priority.ownsShell(route(conversationSelected = false)))
        assertFalse("an ordinary open has no route trace", priority.ownsShell(route(traceRequestId = null)))
        assertFalse("a loaded list needs no help", priority.ownsShell(route(localProjectionAvailable = true)))
        assertFalse("no active account to own", priority.ownsShell(route(activeAccountRef = null)))
    }

    /** App lock composes no shell at all, so a route never claims one, and the staged tap stays unconsumed. */
    @Test
    fun theShellIsNeverOwnedUnderAppLock() {
        assertFalse(priority.ownsShell(route(appLockScreenVisible = true)))
    }

    /** A route snapshot with one deviation per case. */
    private fun route(
        conversationSelected: Boolean = true,
        traceRequestId: Long? = 7L,
        activeAccountRef: String? = "account-b",
        localProjectionAvailable: Boolean = false,
        appLockScreenVisible: Boolean = false,
    ) = RetainedNotificationRouteState(
        conversationSelected = conversationSelected,
        openContext =
            ConversationOpenContext(
                notificationOpenRequestId = 1L,
                notificationRouteTraceRequestId = traceRequestId,
                pinnedAccountRef = "account-b",
            ),
        activeAccountRef = activeAccountRef,
        localProjectionAvailable = localProjectionAvailable,
        appLockScreenVisible = appLockScreenVisible,
    )
}
