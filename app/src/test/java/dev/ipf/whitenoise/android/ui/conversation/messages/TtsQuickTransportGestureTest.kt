package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The detector, driven through real pointer input.
 *
 * Every assertion here is about what the gesture DOES with pointers, which is
 * the half that cannot be reasoned about: which events are consumed, when the
 * commit fires, and what the list underneath does meanwhile. What the committed
 * gesture then means is a separate pure decision and is covered by
 * [TtsQuickTransportActionTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, qualifiers = "w411dp-h891dp-420dpi")
class TtsQuickTransportGestureTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun twoFingersDraggedDownFireOnce() {
        var swipes = 0
        composeRule.setContent { Target(onSwipe = { swipes++ }) }

        composeRule.onNodeWithTag(TARGET).performTouchInput {
            twoFingerDrag(dy = COMMITTING_TRAVEL)
        }
        composeRule.waitForIdle()

        assertEquals(1, swipes)
    }

    @Test
    fun aDragThatKeepsGoingStillFiresOnlyOnce() {
        // The commit is a command, not a continuous control: dragging further
        // must not repeat it, or a long two-finger drag would toggle playback
        // several times and land wherever the parity fell.
        var swipes = 0
        composeRule.setContent { Target(onSwipe = { swipes++ }) }

        composeRule.onNodeWithTag(TARGET).performTouchInput {
            down(0, Offset(centerX - SPREAD, TOP))
            down(1, Offset(centerX + SPREAD, TOP))
            repeat(4) { step ->
                val y = TOP + COMMITTING_TRAVEL * (step + 1)
                moveTo(0, Offset(centerX - SPREAD, y))
                moveTo(1, Offset(centerX + SPREAD, y))
            }
            up(0)
            up(1)
        }
        composeRule.waitForIdle()

        assertEquals(1, swipes)
    }

    @Test
    fun oneFingerIsAScrollAndNeverFires() {
        var swipes = 0
        composeRule.setContent { Target(onSwipe = { swipes++ }) }

        // Several steps, deliberately: a single move would leave the detector
        // no travel to measure whatever its rules said, and the assertion would
        // hold by the shape of the input rather than by the rule.
        composeRule.onNodeWithTag(TARGET).performTouchInput {
            down(0, Offset(centerX, TOP))
            repeat(4) { step ->
                moveTo(0, Offset(centerX, TOP + COMMITTING_TRAVEL * (step + 1)))
            }
            up(0)
        }
        composeRule.waitForIdle()

        assertEquals(0, swipes)
    }

    @Test
    fun twoFingersDraggedUpNeverFire() {
        var swipes = 0
        composeRule.setContent { Target(onSwipe = { swipes++ }) }

        composeRule.onNodeWithTag(TARGET).performTouchInput {
            twoFingerDrag(dy = -COMMITTING_TRAVEL, from = TOP + COMMITTING_TRAVEL * 2)
        }
        composeRule.waitForIdle()

        assertEquals(0, swipes)
    }

    @Test
    fun aWobbleShorterThanTheCommitDistanceNeverFires() {
        var swipes = 0
        composeRule.setContent { Target(onSwipe = { swipes++ }) }

        composeRule.onNodeWithTag(TARGET).performTouchInput {
            twoFingerDrag(dy = 1f)
        }
        composeRule.waitForIdle()

        assertEquals(0, swipes)
    }

    @Test
    fun onlyOneFingerReachingTheDistanceNeverFires() {
        // Two fingers on the screen with one of them anchored is a stretch or a
        // stray thumb, not a command.
        var swipes = 0
        composeRule.setContent { Target(onSwipe = { swipes++ }) }

        composeRule.onNodeWithTag(TARGET).performTouchInput {
            down(0, Offset(centerX - SPREAD, TOP))
            down(1, Offset(centerX + SPREAD, TOP))
            moveTo(0, Offset(centerX - SPREAD, TOP + COMMITTING_TRAVEL))
            up(0)
            up(1)
        }
        composeRule.waitForIdle()

        assertEquals(0, swipes)
    }

    @Test
    fun aDisabledRowNeverFires() {
        var swipes = 0
        composeRule.setContent { Target(enabled = false, onSwipe = { swipes++ }) }

        composeRule.onNodeWithTag(TARGET).performTouchInput {
            twoFingerDrag(dy = COMMITTING_TRAVEL)
        }
        composeRule.waitForIdle()

        assertEquals(0, swipes)
    }

    @Test
    fun everyTwoFingerEventIsClaimedFromTheSecondFingerDown() {
        // The pointers are claimed before the drag has committed to anything.
        // Claiming only at the commit lets whatever is underneath move a few
        // pixels and then stop dead, which reads as the app twitching rather
        // than obeying.
        //
        // Asserted against consumption directly rather than against a list that
        // fails to scroll: a two-finger drag does not scroll a LazyColumn in
        // this test environment even when nothing claims it, so "the list did
        // not move" would have held whatever this detector did.
        var swipes = 0
        val unclaimed = mutableListOf<Int>()
        composeRule.setContent {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .testTag(TARGET)
                    .twoFingerSwipeDown(enabled = true, onSwipe = { swipes++ })
                    .recordUnclaimedTwoFingerChanges(unclaimed),
            ) { Text("message") }
        }

        composeRule.onNodeWithTag(TARGET).performTouchInput { creepThenCommit() }
        composeRule.waitForIdle()

        assertEquals(1, swipes)
        assertTrue("expected the recorder to see the gesture at all", unclaimed.isNotEmpty())
        assertEquals(
            "every two-finger change must already be claimed, commit or not",
            0,
            unclaimed.sum(),
        )
    }

    @Test
    fun aRowThatDoesNotClaimTheGestureLeavesThePointersAlone() {
        // The witness for the assertion above, and the reason an ordinary
        // scroll survives: nothing is consumed unless this detector is on.
        val unclaimed = mutableListOf<Int>()
        composeRule.setContent {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .testTag(TARGET)
                    .twoFingerSwipeDown(enabled = false, onSwipe = {})
                    .recordUnclaimedTwoFingerChanges(unclaimed),
            ) { Text("message") }
        }

        composeRule.onNodeWithTag(TARGET).performTouchInput { creepThenCommit() }
        composeRule.waitForIdle()

        assertTrue("expected unclaimed two-finger changes", unclaimed.sum() > 0)
    }

    @Test
    fun turningTheGestureOffPartWayThroughDoesNotCancelASiblingGesture() {
        // The detector must stay MOUNTED whether or not it is listening.
        // Dropping a pointer input out of the chain rebuilds the pointer-input
        // subtree and cancels whatever a sibling was still resolving - and
        // long-press-drag to select turns selection mode on partway through
        // exactly one such drag, so a structurally conditional detector cancels
        // the very gesture that disabled it.
        var listening by mutableStateOf(true)
        var siblingMoves = 0
        composeRule.setContent {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .testTag(TARGET)
                    .twoFingerSwipeDown(enabled = listening, onSwipe = {})
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                            while (true) {
                                val event = awaitPointerEvent(pass = PointerEventPass.Main)
                                if (event.changes.none { it.pressed }) break
                                siblingMoves++
                            }
                        }
                    },
            ) { Text("message") }
        }

        composeRule.onNodeWithTag(TARGET).performTouchInput {
            down(0, Offset(centerX, TOP))
            moveTo(0, Offset(centerX, TOP + 10f))
        }
        composeRule.waitForIdle()
        val beforeFlip = siblingMoves

        listening = false
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(TARGET).performTouchInput {
            moveTo(0, Offset(centerX, TOP + 20f))
            moveTo(0, Offset(centerX, TOP + 30f))
            up(0)
        }
        composeRule.waitForIdle()

        assertTrue("expected the sibling to see the first half of the drag", beforeFlip > 0)
        assertTrue(
            "the sibling gesture must survive the detector being switched off mid-drag",
            siblingMoves > beforeFlip,
        )
    }

    @Test
    fun oneFingerStillScrollsTheListUnderneath() {
        // The witness for the assertion above: the row consumes a two-finger
        // drag and nothing else, so an ordinary scroll is untouched.
        lateinit var listState: LazyListState
        composeRule.setContent {
            listState = rememberLazyListState()
            LazyColumn(state = listState, modifier = Modifier.testTag(LIST)) {
                items((0 until 40).toList()) { index ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .then(if (index == 0) Modifier.testTag(TARGET) else Modifier)
                            .twoFingerSwipeDown(enabled = index == 0, onSwipe = {}),
                    ) { Text("row $index") }
                }
            }
        }

        composeRule.onNodeWithTag(LIST).performTouchInput {
            down(0, Offset(centerX, TOP + COMMITTING_TRAVEL * 2))
            moveTo(0, Offset(centerX, TOP))
            up(0)
        }
        composeRule.waitForIdle()

        assertTrue(
            "expected an ordinary one-finger drag to still scroll the list",
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0,
        )
    }

    @Composable
    private fun Target(
        enabled: Boolean = true,
        onSwipe: () -> Unit,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(240.dp)
                .testTag(TARGET)
                .twoFingerSwipeDown(enabled = enabled, onSwipe = onSwipe),
        ) { Text("message") }
    }

    private companion object {
        const val TARGET = "quick-transport-target"
        const val LIST = "quick-transport-list"

        /** Comfortably past two multiples of touch slop at this density. */
        const val COMMITTING_TRAVEL = 120f
        const val TOP = 20f
        const val SPREAD = 30f
        const val centerX = 120f
    }
}

private fun androidx.compose.ui.test.TouchInjectionScope.twoFingerDrag(
    dy: Float,
    from: Float = 20f,
) {
    val left = 90f
    val right = 150f
    down(0, Offset(left, from))
    down(1, Offset(right, from))
    moveTo(0, Offset(left, from + dy))
    moveTo(1, Offset(right, from + dy))
    up(0)
    up(1)
}

/** Records how many pressed changes each two-finger event still carries unconsumed. */
private fun Modifier.recordUnclaimedTwoFingerChanges(into: MutableList<Int>): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
            while (true) {
                val event = awaitPointerEvent(pass = PointerEventPass.Main)
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) break
                if (pressed.size >= 2) into += pressed.count { !it.isConsumed }
            }
        }
    }

/**
 * Two fingers creeping down in steps too small to commit, then one step that
 * crosses the distance. The uncommitted steps are the interesting part.
 */
private fun androidx.compose.ui.test.TouchInjectionScope.creepThenCommit() {
    val left = 90f
    val right = 150f
    val top = 20f
    down(0, Offset(left, top))
    down(1, Offset(right, top))
    repeat(4) { step ->
        val y = top + 8f * (step + 1)
        moveTo(0, Offset(left, y))
        moveTo(1, Offset(right, y))
    }
    moveTo(0, Offset(left, top + 120f))
    moveTo(1, Offset(right, top + 120f))
    up(0)
    up(1)
}
