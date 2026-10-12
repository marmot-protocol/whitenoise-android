package dev.ipf.whitenoise.android.ui

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownTimestampStyleFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

private const val BROADCAST_FRAME_TIMEOUT_MILLIS = 500L

// Well short of the hourly cap the stale wait would sleep towards, so only a restarted ticker passes.
private const val SECOND_TICK_TIMEOUT_MILLIS = 1_500L

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MarkdownTimestampRefreshTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val relative = listOf(MarkdownInlineFfi.Timestamp(0, MarkdownTimestampStyleFfi.RELATIVE))

    /** A clock correction from "3 days ago" to "10 seconds ago" reschedules the next refresh to one second. */
    @Test
    fun clockChangeReschedulesRelativeRefresh() {
        var now = 3L * 86_400_000
        var revision = -1L
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { revision = rememberTimestampRevision(relative) { now } }
        composeRule.mainClock.advanceTimeByFrame()
        val settled = revision
        // A days-old label waits for its next boundary, capped at an hour, so a minute passes silently.
        composeRule.mainClock.advanceTimeBy(60_000)
        assertEquals(settled, revision)

        now = 10_000
        composeRule.runOnUiThread {
            ApplicationProvider.getApplicationContext<Context>().sendBroadcast(Intent(Intent.ACTION_TIME_CHANGED))
        }
        // Robolectric delivers broadcasts from the paused main looper. Publish the receiver's state
        // write explicitly, then frame until it recomposes, so the result does not hang on post order.
        shadowOf(Looper.getMainLooper()).idle()
        Snapshot.sendApplyNotifications()
        composeRule.mainClock.advanceTimeUntil(BROADCAST_FRAME_TIMEOUT_MILLIS) { revision > settled }
        val corrected = revision

        // The seconds label must keep counting rather than sleep out the old hour-long wait.
        composeRule.mainClock.advanceTimeUntil(SECOND_TICK_TIMEOUT_MILLIS) { revision > corrected }
        assertTrue("the next second boundary refreshes the label", revision > corrected)
    }
}
