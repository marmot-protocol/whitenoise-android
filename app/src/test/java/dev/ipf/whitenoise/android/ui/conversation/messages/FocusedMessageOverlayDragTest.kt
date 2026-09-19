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

    /**
     * The anchor is a window coordinate and the frame starts below the top inset.
     *
     * Without converting between them the stack rests a status bar too low, which is the whole
     * difference between the lifted message sitting on its bubble and sitting just below it.
     */
    @Test
    fun theRestingPlaceConvertsTheAnchorOutOfWindowCoordinates() {
        val withoutInset = focusedStackRestingOffset(FRAME, STACK, anchorCenterPx = 640)

        val withInset = focusedStackRestingOffset(FRAME, STACK, anchorCenterPx = 640, topInsetPx = 60)

        assertEquals("the inset must move the stack up by exactly itself", withoutInset - 60f, withInset, 0f)
    }

    /** A lift with no anchor centres on the frame, so there is no window coordinate to convert. */
    @Test
    fun anUnanchoredLiftIgnoresTheInset() {
        assertEquals(
            focusedStackRestingOffset(FRAME, STACK, anchorCenterPx = null),
            focusedStackRestingOffset(FRAME, STACK, anchorCenterPx = null, topInsetPx = 60),
            0f,
        )
    }

    /** The resting place is measured from the preview inside the stack, not the stack's middle. */
    @Test
    fun theRestingPlaceIsMeasuredFromThePreview() {
        // An anchor high enough that neither placement is pushed against the frame's edge.
        val byStackCentre = focusedStackRestingOffset(FRAME, STACK, anchorCenterPx = 300)

        val byPreview =
            focusedStackRestingOffset(FRAME, STACK, anchorCenterPx = 300, previewCenterInStackPx = 40)

        assertEquals(
            "placing by the preview must shift by the difference",
            byStackCentre + (STACK / 2 - 40),
            byPreview,
            0f,
        )
    }

    /** A lift that draws no preview falls back to the stack's middle. */
    @Test
    fun aLiftWithNoPreviewFallsBackToTheStackMiddle() {
        assertEquals(
            focusedStackRestingOffset(FRAME, STACK, anchorCenterPx = 640, previewCenterInStackPx = STACK / 2),
            focusedStackRestingOffset(FRAME, STACK, anchorCenterPx = 640),
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

    /**
     * The lifted message opens on the bubble it came from, so nothing appears to jump.
     *
     * The stack is a short reaction rail, the lifted message, then a tall menu, so placing it by its
     * own middle put the message far above its bubble — 538 px with a full menu — and the message
     * visibly teleported as the overlay took over.
     */
    @Test
    fun theLiftedMessageOpensOnTheBubbleItCameFrom() {
        render(anchorTop = 250, anchorBottom = 330)

        val preview = composeRule.onNodeWithTag(PREVIEW_TAG).fetchSemanticsNode().boundsInRoot
        val previewCentre = (preview.top + preview.bottom) / 2

        assertEquals("the lifted message must open on its bubble", 290f, previewCentre, 8f)
    }

    /**
     * A bubble too low for the rest of the stack to fit beneath it keeps the stack in the frame.
     *
     * The lifted message cannot reach that bubble without hanging the menu off the bottom, so the
     * clamp wins and the message opens above where it came from.
     */
    @Test
    fun aBubbleTooLowToReachKeepsTheStackInTheFrame() {
        render(anchorTop = 600, anchorBottom = 680)

        val preview = composeRule.onNodeWithTag(PREVIEW_TAG).fetchSemanticsNode().boundsInRoot
        val stack = composeRule.onNodeWithTag(MESSAGE_ACTION_MENU_TEST_TAG).fetchSemanticsNode().boundsInRoot

        assertTrue("the lifted message opens above a bubble it cannot reach", (preview.top + preview.bottom) / 2 < 640f)
        assertTrue("and the stack stays inside the frame", stack.bottom <= FRAME)
    }

    /**
     * A stack that fills its frame cannot reach the anchor, and this pins that it is a known gap.
     *
     * With every action showing there is no slack to place the stack with, so the lifted message
     * stays where the clamp puts it. #1857 shortens the menu, which is what closes this.
     */
    @Test
    fun aFrameFillingStackCannotReachItsAnchor() {
        render(anchorTop = 600, anchorBottom = 680, actionCount = FRAME_FILLING_ACTIONS)

        val preview = composeRule.onNodeWithTag(PREVIEW_TAG).fetchSemanticsNode().boundsInRoot

        assertTrue("a frame-filling stack is pinned at the top", stackTop() == 0f)
        assertTrue("so its lifted message cannot reach the anchor", (preview.top + preview.bottom) / 2 < 640f)
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
        actionCount: Int = REALISTIC_ACTIONS,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                FocusedMessageActions(
                    sourceBounds = IntRect(0, anchorTop, 360, anchorBottom),
                    touchY = anchorTop.toFloat(),
                    mine = true,
                    actions =
                        List(actionCount) { index ->
                            FocusedMessageAction(
                                label = if (index == 0) "Reply" else "Action " + index,
                                supportingLabel = null,
                                enabled = true,
                                destructive = false,
                                icon = {},
                                onClick = {},
                            )
                        },
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
        const val REALISTIC_ACTIONS = 6
        const val FRAME_FILLING_ACTIONS = 12
    }
}
