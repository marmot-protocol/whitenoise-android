package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Motion contract for a new newest row while the reader follows the tail: the
 * rows above slide up to their new slots and the new row rises from beneath its
 * own, together, instead of landing in one frame or popping in and then sliding.
 * Layout snaps; what the reader sees is the laid-out top plus the drawn shift.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ConversationTailEntranceTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Only a single append seen by a tail-following reader of an anchored transcript arms the entrance. */
    @Test
    fun onlyAnAppendSeenFromTheTailArms() {
        assertTrue(arms(latest = "m2", followed = "m1"))
        assertFalse("nothing changed", arms(latest = "m1", followed = "m1"))
        assertFalse(
            "a history reader is not following the tail",
            arms(latest = "m2", followed = "m1", followingTail = false),
        )
        assertFalse(
            "a batch of arrivals, or a trim that dropped the followed row, is not a single append",
            arms(latest = "m3", followed = "m1", previousIsNewestButOne = false),
        )
        assertFalse("the first publication has nothing to enter from", arms(latest = "m1", followed = null))
        assertFalse(
            "an unanchored transcript is still finding its place",
            arms(latest = "m2", followed = "m1", anchored = false),
        )
    }

    /** The frame that lays the new row out draws every row shifted by its height, then all of them ease up together. */
    @Test
    fun newestRowRisesWhileTheRowsAboveSlideUpTogether() {
        val harness = TailHarness()
        setTailContent(harness)
        composeRule.mainClock.autoAdvance = false
        composeRule.waitForIdle()
        val listBottom =
            composeRule
                .onNodeWithTag(LIST_TAG)
                .fetchSemanticsNode()
                .boundsInRoot.bottom
        val startTop = seenTop("m3")
        assertEquals("the newest row rests on the list's bottom edge", listBottom, startTop + rowHeightPx(), 1f)
        assertEquals("nothing is shifted while no row enters", 0f, harness.entrance.shiftPx, 0f)

        composeRule.runOnUiThread {
            harness.rows = listOf("m4") + harness.rows
            harness.latestId = "m4"
            Snapshot.sendApplyNotifications()
        }
        composeRule.mainClock.advanceTimeByFrame()
        // The screen pins the tail one frame later; do the same here.
        composeRule.runOnIdle {
            harness.followedId = "m4"
            harness.listState.requestScrollToItem(0, 0)
        }
        composeRule.mainClock.advanceTimeByFrame()

        // The list has laid the new row out in its slot, yet the frame draws
        // every row shifted by that row's height: nothing has visibly moved.
        assertEquals(
            "the frame draws every row shifted by the new row's height",
            rowHeightPx(),
            harness.entrance.shiftPx,
            1f,
        )
        assertEquals("the row above is still seen where it was", startTop, seenTop("m3"), 1f)
        assertEquals("the new row is seen just beneath its slot", listBottom, seenTop("m4"), 1f)

        assertRowsEaseUpTogether(startTop)

        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
        assertEquals("the shift returns to zero", 0f, harness.entrance.shiftPx, 0f)
        assertEquals("the entering row settles on the bottom edge", listBottom, seenTop("m4") + rowHeightPx(), 1f)
        assertEquals("the row above settles one slot up", startTop - rowHeightPx(), seenTop("m3"), 1f)
    }

    /**
     * Advances through the entrance and checks both rows rise monotonically, as one, through
     * in-between positions. The fifth frame, well inside the 180ms, is recorded as the baseline.
     */
    private fun assertRowsEaseUpTogether(startTop: Float) {
        var lastAboveTop = seenTop("m3")
        var lastEnteringTop = seenTop("m4")
        var sawIntermediate = false
        repeat(20) { frame ->
            composeRule.mainClock.advanceTimeByFrame()
            if (frame == CAPTURED_FRAME) {
                composeRule
                    .onNodeWithTag(LIST_TAG)
                    .captureRoboImage("src/test/snapshots/conversation_tail_entrance_mid_motion.png")
            }
            val aboveTop = seenTop("m3")
            val enteringTop = seenTop("m4")
            assertTrue("the row above must only ever move up", aboveTop <= lastAboveTop + 0.5f)
            assertTrue("the entering row must only ever move up", enteringTop <= lastEnteringTop + 0.5f)
            assertEquals("both rows move as one", rowHeightPx(), enteringTop - aboveTop, 1f)
            if (aboveTop < startTop - 1f && aboveTop > startTop - rowHeightPx() + 1f) sawIntermediate = true
            lastAboveTop = aboveTop
            lastEnteringTop = enteringTop
        }
        assertTrue("the rows must pass through in-between positions", sawIntermediate)
    }

    /** Mutable inputs of the reversed-list harness, driven from the test thread. */
    private class TailHarness {
        var motionEnabled by mutableStateOf(true)
        var owner by mutableStateOf("account-a")
        var rows by mutableStateOf(listOf("m3", "m2", "m1"))
        var claimedId by mutableStateOf<String?>(null)
        var latestId by mutableStateOf("m3")
        var followedId by mutableStateOf<String?>("m3")
        lateinit var listState: LazyListState
        lateinit var entrance: ConversationTailEntrance
    }

    /** Composes a reversed list whose rows carry the entrance motion, resolved from the harness inputs. */
    private fun setTailContent(harness: TailHarness) {
        composeRule.setContent {
            harness.listState = rememberLazyListState()
            harness.entrance =
                rememberConversationTailEntrance(
                    latestItemId = harness.latestId,
                    lastFollowedLatestId = harness.followedId,
                    // Rows are newest first here, so the followed row is adjacent at index 1.
                    previousIsNewestButOne = harness.rows.getOrNull(1) == harness.followedId,
                    followingTail = true,
                    initialTimelineAnchored = true,
                    rowSpacingPx = 0f,
                    motionEnabled = harness.motionEnabled,
                    ownerKey = harness.owner,
                )
            val claimedId = harness.claimedId
            SideEffect { harness.entrance.claimPresentation(claimedId) }
            LazyColumn(
                state = harness.listState,
                reverseLayout = true,
                modifier = Modifier.fillMaxWidth().height(LIST_HEIGHT).testTag(LIST_TAG),
            ) {
                items(harness.rows, key = { it }) { id ->
                    Box(
                        Modifier
                            .conversationTailEntranceMotion(harness.entrance, id) { harness.claimedId }
                            .fillMaxWidth()
                            .height(ROW_HEIGHT)
                            .background(if (id == "m4") Color(0xFF2E7D32) else Color(0xFF37474F))
                            .testTag(id),
                    ) {
                        Text(id, color = Color.White)
                    }
                }
            }
        }
    }

    /** Releasing the send claim settles the coordinated paint, without replay or neighbour overlap. */
    @Test
    fun retiredSendClaimSettlesItsNeighboursWithoutPaintOverlap() {
        val harness = TailHarness()
        setTailContent(harness)
        composeRule.mainClock.autoAdvance = false
        composeRule.waitForIdle()
        appendTailRow(harness, "m4", claimed = true)
        val claimedTop = seenTop("m4")
        repeat(2) { composeRule.mainClock.advanceTimeByFrame() }
        assertTrue("claim release must happen during the real tail shift", harness.entrance.shiftPx > 0f)
        val aboveBeforeRelease = seenTop("m3")
        composeRule.runOnIdle { harness.claimedId = null }
        composeRule.mainClock.advanceTimeByFrame()
        assertTrue("the retirement precedes the natural tail curve end", harness.entrance.shiftPx > 0f)
        assertEquals("the previously claimed row stays in its canonical slot", claimedTop, seenTop("m4"), 1f)
        assertTrue("the row above advances to its final slot", seenTop("m3") < aboveBeforeRelease)
        assertTrue(
            "visible whole rows cannot overlap after the claimed ink retires",
            seenTop("m3") + rowHeightPx() <= seenTop("m4") + 1f,
        )
        composeRule.mainClock.advanceTimeBy(1_000)
        assertEquals(null, harness.entrance.claimedEnteringItemId)

        appendTailRow(harness, "m5", claimed = false)
        repeat(2) { composeRule.mainClock.advanceTimeByFrame() }
        assertTrue("the next entrance must still move", harness.entrance.shiftPx > 0f)
        assertTrue("the old claimed row participates in the next entrance", seenTop("m4") > claimedTop - rowHeightPx())
        assertEquals("the next entering and older row move together", rowHeightPx(), seenTop("m5") - seenTop("m4"), 1f)
    }

    @Test
    fun anActiveOlderSendClaimSharesAnUnrelatedNewRowShift() {
        val harness = TailHarness()
        setTailContent(harness)
        composeRule.mainClock.autoAdvance = false
        composeRule.waitForIdle()
        appendTailRow(harness, "m4", claimed = true)
        composeRule.runOnUiThread {
            // Keep the actual active claim on m4 while a different row becomes newest.
            harness.rows = listOf("m5") + harness.rows
            harness.latestId = "m5"
            Snapshot.sendApplyNotifications()
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.runOnUiThread {
            harness.followedId = "m5"
            harness.listState.requestScrollToItem(0, 0)
            Snapshot.sendApplyNotifications()
        }
        composeRule.mainClock.advanceTimeByFrame()
        assertEquals("m4", harness.claimedId)
        assertEquals("m5", harness.entrance.enteringItemId)
        assertTrue("the unrelated entrance is actually shifting", harness.entrance.shiftPx > 0f)
        repeat(4) {
            assertEquals("older full-width rows keep their gap", rowHeightPx(), seenTop("m4") - seenTop("m3"), 1f)
            assertEquals("active older claim shares incoming shift", rowHeightPx(), seenTop("m5") - seenTop("m4"), 1f)
            composeRule.mainClock.advanceTimeByFrame()
        }
    }

    private fun appendTailRow(
        harness: TailHarness,
        id: String,
        claimed: Boolean,
    ) {
        composeRule.runOnUiThread {
            harness.claimedId = id.takeIf { claimed }
            harness.rows = listOf(id) + harness.rows
            harness.latestId = id
            Snapshot.sendApplyNotifications()
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.runOnIdle {
            harness.followedId = id
            harness.listState.requestScrollToItem(0, 0)
        }
        composeRule.mainClock.advanceTimeByFrame()
    }

    @Test fun liveMotionDisableSettlesAndOwnerReplacementDropsOldEntrance() {
        val harness = TailHarness()
        setTailContent(harness)
        composeRule.mainClock.autoAdvance = false
        composeRule.waitForIdle()
        appendTailRow(harness, "m4", claimed = true)
        assertTrue("motion disable must interrupt the real tail shift", harness.entrance.shiftPx > 0f)
        assertEquals("m4", harness.entrance.claimedEnteringItemId)
        composeRule.runOnIdle { harness.motionEnabled = false }
        composeRule.mainClock.advanceTimeByFrame()
        assertEquals(0f, harness.entrance.shiftPx, 0f)
        assertEquals(null, harness.entrance.enteringItemId)
        assertEquals(null, harness.entrance.claimedEnteringItemId)
        composeRule.runOnIdle {
            harness.followedId = "m4"
            harness.owner = "account-b"
            harness.motionEnabled = true
        }
        composeRule.mainClock.advanceTimeBy(500)
        assertEquals(0f, harness.entrance.shiftPx, 0f)
        assertEquals(null, harness.entrance.enteringItemId)
    }

    /** A row that is not entering never starts the motion, so paging and keyboard tracking stay untouched. */
    @Test
    fun rowsThatAreNotEnteringNeverShift() {
        var rows by mutableStateOf(listOf("m3", "m2", "m1"))
        lateinit var entrance: ConversationTailEntrance

        composeRule.setContent {
            entrance =
                rememberConversationTailEntrance(
                    latestItemId = rows.first(),
                    lastFollowedLatestId = rows.first(),
                    previousIsNewestButOne = true,
                    followingTail = true,
                    initialTimelineAnchored = true,
                    rowSpacingPx = 0f,
                )
            LazyColumn(reverseLayout = true, modifier = Modifier.fillMaxWidth().height(LIST_HEIGHT)) {
                items(rows, key = { it }) { id ->
                    Box(
                        Modifier
                            .conversationTailEntranceMotion(entrance, id)
                            .fillMaxWidth()
                            .height(ROW_HEIGHT)
                            .testTag(id),
                    )
                }
            }
        }
        composeRule.mainClock.autoAdvance = false
        composeRule.waitForIdle()

        composeRule.runOnIdle { rows = listOf("m4") + rows }
        repeat(3) { composeRule.mainClock.advanceTimeByFrame() }

        assertEquals("a row the reader did not follow into does not shift anything", 0f, entrance.shiftPx, 0f)
    }

    private fun arms(
        latest: String?,
        followed: String?,
        previousIsNewestButOne: Boolean = true,
        followingTail: Boolean = true,
        anchored: Boolean = true,
    ): Boolean =
        conversationTailEntranceArms(
            latestItemId = latest,
            lastFollowedLatestId = followed,
            previousIsNewestButOne = previousIsNewestButOne,
            followingTail = followingTail,
            initialTimelineAnchored = anchored,
        )

    /** Where the reader sees the row: its laid-out position plus the drawn shift, even when clipped. */
    private fun seenTop(tag: String): Float =
        composeRule
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .positionInRoot.y

    private fun rowHeightPx(): Float = with(composeRule.density) { ROW_HEIGHT.toPx() }

    private companion object {
        const val LIST_TAG = "tail-entrance-list"
        const val CAPTURED_FRAME = 4
        val LIST_HEIGHT = 300.dp
        val ROW_HEIGHT = 72.dp
    }
}
