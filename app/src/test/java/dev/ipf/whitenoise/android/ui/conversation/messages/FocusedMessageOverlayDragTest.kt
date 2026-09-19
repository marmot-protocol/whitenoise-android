package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where the lifted stack may sit, and how it gets there.
 *
 * #2606 reported the lifted message stopping short of the top. It could not move at all: the stack
 * was centred on its bubble by a popup sized to its own content, and the scrolling it had covered
 * only overflow it did not have. The geometry below is the contract that replaced that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class FocusedMessageOverlayDragTest {
    @get:Rule val composeRule = createComposeRule()

    /** A stack shorter than its frame starts centred on the message it lifted. */
    @Test
    fun theStackRestsBesideTheMessageItLifted() {
        assertEquals(534f, focusedStackRestingOffset(FRAME, STACK, anchorCenterPx = 640), 0f)
    }

    /** Resting never leaves the frame, however close to an edge the lifted message was. */
    @Test
    fun theRestingPlaceStaysInsideTheFrame() {
        assertEquals(0f, focusedStackRestingOffset(FRAME, STACK, anchorCenterPx = 20), 0f)
        assertEquals(
            (FRAME - STACK).toFloat(),
            focusedStackRestingOffset(FRAME, STACK, anchorCenterPx = 770),
            0f,
        )
    }

    /** A lift that reported neither a bubble nor a touch point centres on the frame. */
    @Test
    fun aLiftWithNoAnchorCentresOnTheFrame() {
        assertEquals(284f, focusedStackRestingOffset(FRAME, STACK, anchorCenterPx = null), 0f)
    }

    /** A short stack reaches both edges of the frame — the top included, which is what #2606 asked for. */
    @Test
    fun aShortStackCanTravelToBothFrameEdges() {
        val range = focusedStackTravelRange(FRAME, STACK)

        assertEquals(0f, range.start, 0f)
        assertEquals((FRAME - STACK).toFloat(), range.endInclusive, 0f)
    }

    /** A stack that exactly fills its frame has nowhere to travel and keeps its scrolling. */
    @Test
    fun aStackFillingItsFrameHasNowhereToTravel() {
        val range = focusedStackTravelRange(FRAME, FRAME)

        assertEquals(0f, range.start, 0f)
        assertEquals(0f, range.endInclusive, 0f)
    }

    /** A stack taller than its frame travels the other way, by exactly the part that does not fit. */
    @Test
    fun aTallStackTravelsByItsOverflow() {
        val range = focusedStackTravelRange(FRAME, FRAME + 120)

        assertEquals(-120f, range.start, 0f)
        assertEquals(0f, range.endInclusive, 0f)
    }

    /** Dragging the lifted message upward carries the whole stack with it. */
    @Test
    fun draggingTheLiftedMessageMovesTheStack() {
        render(anchorTop = 600, anchorBottom = 680)
        val before = stackTop()

        composeRule.onNodeWithTag(PREVIEW_TAG).performTouchInput { swipeUp() }
        composeRule.waitForIdle()

        assertTrue("the stack must rise, was $before now ${stackTop()}", stackTop() < before)
    }

    /**
     * A fling reaches the frame's top edge and stops on it, rather than short of it or past it.
     *
     * Settling exactly on the bound is the whole claim: landing short would be the #2606 report
     * again, and overshooting would put the lifted message behind the status bar.
     */
    @Test
    fun aFlingSettlesOnTheFrameTopEdge() {
        render(anchorTop = 600, anchorBottom = 680)
        val before = stackTop()

        repeat(3) {
            composeRule.onNodeWithTag(PREVIEW_TAG).performTouchInput {
                swipeUp(startY = bottom, endY = top, durationMillis = 50)
            }
            composeRule.waitForIdle()
        }

        assertTrue("the stack must have risen from " + before, stackTop() < before)
        assertEquals("the stack must settle on the frame's top edge", 0f, stackTop(), 0.5f)
    }

    /** A lifted message anchored high can be pulled down, and stops inside the frame's bottom edge. */
    @Test
    fun theStackCanBePulledDownToTheFrameBottomEdge() {
        render(anchorTop = 40, anchorBottom = 120)
        val before = stackTop()

        repeat(3) {
            composeRule.onNodeWithTag(PREVIEW_TAG).performTouchInput {
                swipeDown(startY = top, endY = bottom, durationMillis = 50)
            }
            composeRule.waitForIdle()
        }

        assertTrue("the stack must have descended from " + before, stackTop() > before)
        assertTrue("the stack must stay in the frame, bottom was " + stackBottom(), stackBottom() <= FRAME)
    }

    /**
     * A second drag moves the stack as the first one did.
     *
     * The preview carries the dismiss tap inside the drag in the modifier chain, and a tap detector
     * claims the press it sees. If it claimed the press for good, the first gesture after the
     * overlay opened would drag and every later one would be dead — which is what the overlay looked
     * like on device.
     */
    @Test
    fun theStackStillDragsOnASecondGesture() {
        render(anchorTop = 600, anchorBottom = 680)
        val resting = stackTop()

        composeRule.onNodeWithTag(PREVIEW_TAG).performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        val afterFirst = stackTop()
        composeRule.onNodeWithTag(PREVIEW_TAG).performTouchInput { swipeDown() }
        composeRule.waitForIdle()

        assertTrue("the first drag must move the stack, rested at " + resting, afterFirst < resting)
        assertTrue("the second drag must move it back, was " + afterFirst, stackTop() > afterFirst)
    }

    /** A drag still works after a gesture the tap detector handled. */
    @Test
    fun theStackStillDragsAfterATapIsHandled() {
        var dismissals = 0
        render(anchorTop = 600, anchorBottom = 680, onDismiss = { dismissals += 1 })
        val resting = stackTop()

        composeRule.onNodeWithTag(PREVIEW_TAG).performTouchInput { click() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(PREVIEW_TAG).performTouchInput { swipeUp() }
        composeRule.waitForIdle()

        assertEquals("the tap must still dismiss", 1, dismissals)
        assertTrue("the drag after a tap must move the stack, rested at " + resting, stackTop() < resting)
    }

    private fun stackTop(): Float =
        composeRule
            .onNodeWithTag(MESSAGE_ACTION_MENU_TEST_TAG)
            .fetchSemanticsNode()
            .boundsInRoot.top

    private fun stackBottom(): Float =
        composeRule
            .onNodeWithTag(MESSAGE_ACTION_MENU_TEST_TAG)
            .fetchSemanticsNode()
            .boundsInRoot.bottom

    private fun render(
        anchorTop: Int,
        anchorBottom: Int,
        onDismiss: () -> Unit = {},
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                FocusedMessageActions(
                    sourceBounds = IntRect(0, anchorTop, 360, anchorBottom),
                    touchY = anchorTop.toFloat(),
                    mine = true,
                    actions =
                        listOf(
                            FocusedMessageAction(
                                label = "Reply",
                                supportingLabel = null,
                                enabled = true,
                                destructive = false,
                                icon = {},
                                onClick = {},
                            ),
                        ),
                    quickReactions = listOf("👍"),
                    canReact = true,
                    selectedReactions = emptySet(),
                    previewDescription = "Lifted message",
                    previewReady = true,
                    preview = {
                        Box(Modifier.size(200.dp, 60.dp).background(MaterialTheme.colorScheme.surface)) {
                            Text("The lifted message")
                        }
                    },
                    onReact = {},
                    onMoreReactions = {},
                    onDismiss = onDismiss,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private companion object {
        const val PREVIEW_TAG = "message-actions-preview"
        const val FRAME = 780
        const val STACK = 212
    }
}
