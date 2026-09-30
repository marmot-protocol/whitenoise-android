package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A flick is one gesture from the finger landing to the list coming to rest. The drag interaction
 * stops at release, before Compose has started the fling, so the settle wait must not mistake that
 * gap for the list being at rest: settling early hands history re-anchoring a stale release anchor
 * and lets it snap a coasting transcript back to it (#2727).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
internal class ConversationDragInteractionSettleTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** The gesture settles once, and only after the fling has carried the list past its release point. */
    @Test
    fun flickSettlesAfterTheFlingComesToRest() {
        val fixture = mountList()
        fixture.flick()
        composeRule.mainClock.advanceTimeBy(EARLY_SAMPLE_MILLIS)
        val whileCoasting = fixture.position()
        assertEquals("the list must still be coasting shortly after release", 0, fixture.settled.size)
        assertTrue("the flick must move the list", whileCoasting > 0f)

        composeRule.mainClock.advanceTimeBy(FLING_BUDGET_MILLIS)
        val atRest = fixture.position()
        assertTrue("the fling must carry on past the release point ($whileCoasting -> $atRest)", atRest > whileCoasting)
        assertEquals("the gesture settles exactly once", listOf(atRest), fixture.settled)
        assertEquals(1, fixture.started)
    }

    /** A catch flick during the fling restarts the gesture instead of settling the first one early. */
    @Test
    fun catchFlickRestartsTheGestureWithoutAnEarlySettle() {
        val fixture = mountList()
        fixture.flick()
        composeRule.mainClock.advanceTimeBy(EARLY_SAMPLE_MILLIS)
        fixture.flick()
        composeRule.mainClock.advanceTimeBy(EARLY_SAMPLE_MILLIS)
        assertEquals("nothing settles while the second flick is still coasting", 0, fixture.settled.size)
        assertEquals(2, fixture.started)
        composeRule.mainClock.advanceTimeBy(FLING_BUDGET_MILLIS)
        assertEquals(1, fixture.settled.size)
    }

    /** Mounts a tall list wired to the production drag-interaction collector. */
    private fun mountList(): ListFixture {
        val fixture = ListFixture()
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            val listState = rememberLazyListState()
            SideEffect { fixture.listState = listState }
            LaunchedEffect(listState) {
                listState.interactionSource.interactions.collectConversationDragInteractions(
                    onStarted = { fixture.started++ },
                    awaitScrollSettled = {
                        snapshotFlow { listState.isScrollInProgress }.filter { !it }.first()
                    },
                    onSettled = { fixture.settled += fixture.position() },
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.size(WIDTH_DP.dp, HEIGHT_DP.dp).testTag(LIST_TAG),
            ) {
                items(List(ROW_COUNT) { it }, key = { it }) { index ->
                    Text("Message $index", Modifier.fillMaxWidth().height(ROW_HEIGHT_DP.dp))
                }
            }
        }
        composeRule.waitForIdle()
        return fixture
    }

    /** One mounted list plus the interaction bookkeeping the assertions read. */
    private inner class ListFixture {
        lateinit var listState: LazyListState
        var started = 0
        val settled = mutableListOf<Float>()

        /** Flicks upward fast enough to fling the list toward higher indices. */
        fun flick() {
            composeRule.onNodeWithTag(LIST_TAG).performTouchInput {
                val start = Offset(center.x, FLICK_START_Y_DP.dp.toPx())
                swipe(start = start, end = start - Offset(0f, FLICK_TRAVEL_DP.dp.toPx()), durationMillis = FLICK_MILLIS)
            }
        }

        /** Scroll distance in rows plus fractional offset, readable from any thread. */
        fun position(): Float = listState.firstVisibleItemIndex + listState.firstVisibleItemScrollOffset / ROW_HEIGHT_PX
    }

    private companion object {
        const val LIST_TAG = "list"
        const val ROW_COUNT = 400
        const val ROW_HEIGHT_DP = 40
        const val ROW_HEIGHT_PX = 40f
        const val WIDTH_DP = 360
        const val HEIGHT_DP = 600
        const val FLICK_START_Y_DP = 450
        const val FLICK_TRAVEL_DP = 200
        const val FLICK_MILLIS = 60L
        const val EARLY_SAMPLE_MILLIS = 100L
        const val FLING_BUDGET_MILLIS = 5_000L
    }
}
