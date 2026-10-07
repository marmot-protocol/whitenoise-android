package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.diagnostics.DmCreationDiagnostics
import dev.ipf.whitenoise.android.diagnostics.DmCreationInteraction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Drives the destination's actual Compose effect instead of calling the first-frame recorder directly. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class DmConversationFirstFrameTest {
    @get:Rule val composeRule = createComposeRule()
    private val records = mutableListOf<Map<String, Any>>()

    /** Clears process-local tickets between sandboxes and prevents the test clock from skipping the boundary. */
    @Before
    fun resetTickets() {
        DmCreationDiagnostics.attach(ApplicationProvider.getApplicationContext<Context>())
        composeRule.mainClock.autoAdvance = false
    }

    /** A mounted destination cannot claim success until its awaited frame arrives, and consumes the ticket once. */
    @Test
    fun actualEffectCompletesItsCapturedTicketOnTheNextFrameOnly() {
        val attempt = DmCreationInteraction(records::add).nextAttempt()
        DmCreationDiagnostics.awaitFrame("a", "g", 1, attempt)
        composeRule.setContent { RecordDmConversationFirstFrame("a", "g", 1) { 1 } }
        composeRule.runOnIdle { assertFalse(records.any { it["outcome"] == "success" }) }
        repeat(3) { composeRule.mainClock.advanceTimeByFrame() }
        composeRule.runOnIdle {
            assertEquals(1, records.count { it["phase"] == "first_frame" && it["outcome"] == "success" })
        }
    }

    /** Runtime invalidation before rendering prevents attribution to the prior session. */
    @Test
    fun runtimeChangeBeforeTheFrameLeavesTheOldTicketUnconsumed() {
        val attempt = DmCreationInteraction(records::add).nextAttempt()
        DmCreationDiagnostics.awaitFrame("a", "g", 1, attempt)
        var liveGeneration = 1
        composeRule.setContent { RecordDmConversationFirstFrame("a", "g", 1) { liveGeneration } }
        composeRule.runOnIdle { liveGeneration = 2 }
        repeat(3) { composeRule.mainClock.advanceTimeByFrame() }
        composeRule.runOnIdle { assertFalse(records.any { it["outcome"] == "success" }) }
    }

    /** A replacement ticket gets its own rendered frame; the old effect cannot claim that new attempt. */
    @Test
    fun replacementTicketCompletesOnlyItsOwnAttempt() {
        val interaction = DmCreationInteraction(records::add)
        val old = interaction.nextAttempt()
        DmCreationDiagnostics.awaitFrame("a", "g", 1, old)
        composeRule.setContent { RecordDmConversationFirstFrame("a", "g", 1) { 1 } }
        composeRule.runOnIdle {
            DmCreationDiagnostics.awaitFrame("a", "g", 1, interaction.nextAttempt())
            Snapshot.sendApplyNotifications()
        }
        repeat(4) { composeRule.mainClock.advanceTimeByFrame() }
        composeRule.runOnIdle {
            assertEquals(listOf(2L), records.filter { it["outcome"] == "success" }.map { it["attempt"] })
        }
    }

    /** Reopening a peer from an already-mounted conversation must not leave its first-frame trace pending. */
    @Test
    fun mountedDestinationObservesANewTicketWithoutChangingRouteKeys() {
        composeRule.setContent { RecordDmConversationFirstFrame("a", "g", 1) { 1 } }
        composeRule.runOnIdle {
            assertEquals(0, records.size)
            DmCreationDiagnostics.awaitFrame("a", "g", 1, DmCreationInteraction(records::add).nextAttempt())
            Snapshot.sendApplyNotifications()
            assertFalse(records.any { it["outcome"] == "success" })
        }
        repeat(4) { composeRule.mainClock.advanceTimeByFrame() }
        composeRule.runOnIdle {
            assertEquals(1, records.count { it["phase"] == "first_frame" && it["outcome"] == "success" })
        }
    }

    /** Another account's destination cannot consume the pending ticket even when the group matches. */
    @Test
    fun anotherAccountCannotClaimTheFrame() {
        DmCreationDiagnostics.awaitFrame("a", "g", 1, DmCreationInteraction(records::add).nextAttempt())
        composeRule.setContent { RecordDmConversationFirstFrame("other", "g", 1) { 1 } }
        repeat(3) { composeRule.mainClock.advanceTimeByFrame() }
        composeRule.runOnIdle { assertFalse(records.any { it["outcome"] == "success" }) }
    }
}
