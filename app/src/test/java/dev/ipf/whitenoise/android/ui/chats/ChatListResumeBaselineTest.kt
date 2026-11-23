package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #3780: rows reordered while the app was in the background must accept
 * a tap on the first frame after resume, while live reorders keep the #1828 gate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ChatListResumeBaselineTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val rowHeight = 48.dp
    private val lifecycleOwner = ResumeTestLifecycleOwner()
    // Enough rows to scroll, so a keyed anchor could hide a promoted head above the viewport.
    private val filler = (0 until 27).map { "R$it" }
    private var itemIds by mutableStateOf(order("A", "B", "C"))
    private val openedIds = mutableListOf<String>()
    private lateinit var listState: LazyListState

    private fun order(vararg head: String): List<String> = head.toList() + filler

    private fun setHarness() {
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                listState = rememberLazyListState()
                ChatListHeadReorderMotionHarness(
                    itemIds = itemIds,
                    listState = listState,
                    rowHeight = rowHeight,
                    onOpen = openedIds::add,
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
    }

    /** Compose does not recompose a stopped window, so background changes land with ON_START. */
    private fun resumeWith(order: List<String>) {
        composeRule.runOnUiThread { lifecycleOwner.currentState = Lifecycle.State.CREATED }
        composeRule.runOnUiThread {
            itemIds = order
            lifecycleOwner.currentState = Lifecycle.State.RESUMED
        }
    }

    private fun advanceFrames(count: Int) {
        repeat(count) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.runOnIdle { }
        }
    }

    private fun tap(id: String) {
        composeRule.onNodeWithTag(chatListHeadReorderRowTag(id)).performTouchInput {
            down(center)
            up()
        }
        composeRule.runOnIdle { }
    }

    private fun rowTop(id: String): Float =
        composeRule
            .onNodeWithTag(chatListHeadReorderRowTag(id))
            .fetchSemanticsNode()
            .boundsInRoot
            .top

    private fun tapListAt(y: Float) {
        composeRule.onNodeWithTag(CHAT_LIST_HEAD_REORDER_LIST_TAG).performTouchInput {
            down(Offset(centerX, y))
            up()
        }
        composeRule.runOnIdle { }
    }

    private fun tapListTop() = tapListAt(24f)

    private fun stopAndResume() {
        composeRule.runOnUiThread { lifecycleOwner.currentState = Lifecycle.State.CREATED }
        composeRule.runOnUiThread { lifecycleOwner.currentState = Lifecycle.State.RESUMED }
    }

    private fun assertFlushResumedOrder() {
        // No placement tween or scroll correction is running: every row is in its final slot.
        assertEquals(0f, rowTop("C"), 0.5f)
        assertEquals(48f, rowTop("A"), 0.5f)
        assertEquals(96f, rowTop("B"), 0.5f)
        composeRule.runOnIdle { assertEquals(0, listState.firstVisibleItemIndex) }
    }

    @Test
    fun headPromotedInTheBackgroundIsPlacedAndTappableOnTheFirstResumedFrame() {
        setHarness()

        resumeWith(order("C", "A", "B"))
        advanceFrames(1)

        assertFlushResumedOrder()
        tapListTop()
        composeRule.runOnIdle { assertEquals(listOf("C"), openedIds) }
    }

    @Test
    fun headPromotionComposedWhileStoppedIsTappableOnTheFirstResumedFrame() {
        setHarness()

        // A stopped window keeps recomposing; only its animation clock pauses.
        composeRule.runOnUiThread { lifecycleOwner.currentState = Lifecycle.State.CREATED }
        composeRule.runOnUiThread { itemIds = order("C", "A", "B") }
        advanceFrames(2)
        composeRule.runOnUiThread { lifecycleOwner.currentState = Lifecycle.State.RESUMED }
        advanceFrames(1)

        assertFlushResumedOrder()
        tapListTop()
        composeRule.runOnIdle { assertEquals(listOf("C"), openedIds) }
    }

    @Test
    fun liveHeadMotionInterruptedByTheBackgroundDoesNotHoldInputAfterResume() {
        setHarness()
        composeRule.runOnUiThread { itemIds = order("C", "A", "B") }
        advanceFrames(1)

        composeRule.runOnUiThread { lifecycleOwner.currentState = Lifecycle.State.CREATED }
        advanceFrames(1)
        composeRule.runOnUiThread { lifecycleOwner.currentState = Lifecycle.State.RESUMED }
        advanceFrames(1)

        composeRule.runOnIdle { assertEquals(0, listState.firstVisibleItemIndex) }
        tap("C")
        composeRule.runOnIdle { assertEquals(listOf("C"), openedIds) }
    }

    @Test
    fun placementStillRunningWhenStoppedIsSettledOnTheFirstResumedFrame() {
        setHarness()
        composeRule.runOnUiThread { itemIds = order("A", "C", "B") }
        advanceFrames(1)

        stopAndResume()
        advanceFrames(1)

        assertEquals(48f, rowTop("C"), 0.5f)
        assertEquals(96f, rowTop("B"), 0.5f)
        tapListAt(72f)
        composeRule.runOnIdle { assertEquals(listOf("C"), openedIds) }
    }

    @Test
    fun rowInsertedWhileStoppedIsInPlaceAndTappableOnTheFirstResumedFrame() {
        setHarness()

        composeRule.runOnUiThread { lifecycleOwner.currentState = Lifecycle.State.CREATED }
        composeRule.runOnUiThread { itemIds = order("A", "N", "B", "C") }
        advanceFrames(1)
        composeRule.runOnUiThread { lifecycleOwner.currentState = Lifecycle.State.RESUMED }
        advanceFrames(1)

        assertEquals(48f, rowTop("N"), 0.5f)
        tapListAt(72f)
        composeRule.runOnIdle { assertEquals(listOf("N"), openedIds) }
    }

    @Test
    fun sameHeadReorderInTheBackgroundIsTappableOnTheFirstResumedFrame() {
        setHarness()

        resumeWith(order("A", "C", "B"))
        advanceFrames(1)

        assertEquals(48f, rowTop("C"), 0.5f)
        tap("C")
        composeRule.runOnIdle { assertEquals(listOf("C"), openedIds) }
    }

    @Test
    fun liveReorderAfterTheResumedFrameKeepsTheMotionGate() {
        setHarness()
        resumeWith(order("B", "A", "C"))
        advanceFrames(2)

        composeRule.runOnUiThread { itemIds = order("C", "B", "A") }
        advanceFrames(1)
        // Live motion starts from the snapped slot (index 2), not from a list edge.
        val movingTop = rowTop("C")
        assertTrue(
            "C should be moving up from its resumed slot, was at $movingTop",
            movingTop > 0.5f && movingTop <= 96.5f,
        )
        tap("C")
        composeRule.runOnIdle { assertEquals(emptyList<String>(), openedIds) }

        composeRule.mainClock.advanceTimeBy(CHAT_LIST_HEAD_INPUT_GATE_MILLIS + 500L)
        composeRule.runOnIdle { }
        assertEquals(0f, rowTop("C"), 0.5f)
        tap("C")
        composeRule.runOnIdle { assertEquals(listOf("C"), openedIds) }
    }

    @Test
    fun reorderWithoutABackgroundTripKeepsTheMotionGate() {
        setHarness()

        // ON_START without a preceding ON_STOP (initial registration replay) is not a resume.
        composeRule.runOnUiThread {
            itemIds = order("A", "C", "B")
            lifecycleOwner.currentState = Lifecycle.State.RESUMED
        }
        advanceFrames(1)
        tap("C")
        composeRule.runOnIdle { assertEquals(emptyList<String>(), openedIds) }
    }
}

private class ResumeTestLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }

    override val lifecycle: Lifecycle = registry

    var currentState: Lifecycle.State
        get() = registry.currentState
        set(value) {
            registry.currentState = value
        }
}
