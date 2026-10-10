package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.ipf.whitenoise.android.notifications.NotificationRouteFirstFrameGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The backstop on the process-owned first-frame priority: a route whose every release path stayed silent
 * cannot keep the chat-list bind deferred past the bound, while a route that releases on time is untouched.
 * Time moves only when a test advances it, and every assertion keeps a wide margin around the bound because
 * the timer starts at the first frame after a gate is held.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationFirstFrameWatchdogTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val priority = NotificationFirstFramePriority { 1 }

    /** Mounts the watchdog over [priority] with a short bound and freezes the clock until a test advances it. */
    @Before
    fun mountWatchdog() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { NotificationFirstFrameWatchdog(priority, maxHoldMs = HOLD_MS) }
    }

    /** Moves the composition clock forward, running any frame and timer that falls inside the step. */
    private fun advance(millis: Long) {
        composeRule.mainClock.advanceTimeBy(millis)
    }

    /** Runs [block] on the UI thread, publishes its state writes and composes one frame so the watchdog sees them. */
    private fun onUi(block: () -> Unit) {
        composeRule.runOnUiThread {
            block()
            Snapshot.sendApplyNotifications()
        }
        composeRule.mainClock.advanceTimeByFrame()
    }

    /** A held gate that nothing releases ends once the bound passes, and not before. */
    @Test
    fun aGateNoRouteEventReleasedEndsAtTheBound() {
        val gate = NotificationRouteFirstFrameGate(requestId = 7L, accountRef = "account-b")

        onUi { priority.begin(gate) }
        advance(HOLD_MS - MARGIN_MS)
        assertFalse(gate.isReleased)

        advance(2 * MARGIN_MS)
        assertTrue(gate.isReleased)
        assertNull(priority.gate)
    }

    /** A route that releases on its first frame leaves nothing for the bound to do afterwards. */
    @Test
    fun aGateReleasedEarlyStaysReleasedAndTheBoundIsHarmless() {
        val gate = NotificationRouteFirstFrameGate(requestId = 7L, accountRef = "account-b")

        onUi { priority.begin(gate) }
        advance(HOLD_MS / 2L)
        onUi { priority.release(requestId = 7L) }
        advance(3L * HOLD_MS)

        assertTrue(gate.isReleased)
        assertNull(priority.gate)
        assertNull(priority.heldRequestId)
    }

    /** A newer tap restarts the bound, so it never inherits the time its predecessor already used. */
    @Test
    fun aNewerRequestRestartsTheBound() {
        val first = NotificationRouteFirstFrameGate(requestId = 1L, accountRef = "account-b")
        val second = NotificationRouteFirstFrameGate(requestId = 2L, accountRef = "account-b")

        onUi { priority.begin(first) }
        advance(HOLD_MS / 2L)
        onUi { priority.begin(second) }
        advance(HOLD_MS - MARGIN_MS)
        assertTrue(first.isReleased)
        assertFalse(second.isReleased)

        advance(2 * MARGIN_MS)
        assertTrue(second.isReleased)
    }

    private companion object {
        const val HOLD_MS = 1_000L
        const val MARGIN_MS = 150L
    }
}
