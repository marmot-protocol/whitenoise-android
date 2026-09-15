package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * The reply glyph is an affordance the drag uncovers: absent while the bubble
 * is at rest, revealed at the bubble's leading edge and vertically centred on
 * it once a swipe starts, and gone again when the bubble springs home.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h800dp-mdpi")
class MessageReplySwipeGlyphTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** No drag in progress means no arrow anywhere in the row. */
    @Test
    fun theGlyphIsAbsentWhileTheBubbleIsAtRest() {
        renderBubble()
        composeRule.onNodeWithTag(glyphTag(), useUnmergedTree = true).assertDoesNotExist()
    }

    /** A held drag reveals the arrow on a square touch target of at least 48.dp. */
    @Test
    fun aHeldDragRevealsTheGlyph() {
        renderBubble()
        dragAndHold(DRAG_PX)

        val glyph = composeRule.onNodeWithTag(glyphTag(), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("the glyph must keep a 48.dp touch target", glyph.width >= TOUCH_TARGET_PX)
        assertEquals("the touch target stays square", glyph.width, glyph.height, CENTRE_TOLERANCE_PX)

        composeRule.onNodeWithTag(SWIPE_TEST_HOST_TAG).performTouchInput { cancel() }
    }

    /** The arrow sits at the bubble's leading edge and shares its vertical centre. */
    @Test
    fun theGlyphAnchorsToTheBubbleLeadingEdge() {
        renderBubble()
        dragAndHold(DRAG_PX)

        val glyph = composeRule.onNodeWithTag(glyphTag(), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val bubble =
            composeRule
                .onNodeWithTag(messageBubbleColumnTestTag(SWIPE_TEST_MESSAGE_ID), useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot

        assertEquals("glyph shares the bubble's vertical centre", bubble.center.y, glyph.center.y, CENTRE_TOLERANCE_PX)
        // The prototype starts the glyph's box at the bubble's leading edge and then slides it
        // forward by the travel allowance as the drag progresses; it never crosses the bubble.
        val travel = with(composeRule.density) { MessageReplySwipeMetrics.IconTravel.toPx() }
        assertTrue(
            "glyph sits at the bubble's leading edge, glyph=$glyph bubble=$bubble",
            glyph.left >= bubble.left - travel && glyph.left <= bubble.left + travel,
        )

        composeRule.onNodeWithTag(SWIPE_TEST_HOST_TAG).performTouchInput { cancel() }
    }

    /** Releasing springs the bubble home and takes the arrow away with it. */
    @Test
    fun theGlyphDisappearsOnceTheBubbleSettles() {
        renderBubble()
        val bubbleText = composeRule.onNode(hasText(SWIPE_TEST_MESSAGE_BODY, substring = true), useUnmergedTree = true)
        val restingLeft = bubbleText.fetchSemanticsNode().boundsInRoot.left

        dragAndHold(DRAG_PX)
        composeRule.onNodeWithTag(glyphTag(), useUnmergedTree = true).assertExists()

        composeRule.onNodeWithTag(SWIPE_TEST_HOST_TAG).performTouchInput { up() }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(glyphTag(), useUnmergedTree = true).assertDoesNotExist()
        assertTrue(
            "the bubble must spring back to rest",
            abs(bubbleText.fetchSemanticsNode().boundsInRoot.left - restingLeft) < CENTRE_TOLERANCE_PX,
        )
    }

    /** An overdrag is rubber-banded: the bubble never travels the full finger distance. */
    @Test
    fun anOverdragTravelsLessThanTheFinger() {
        renderBubble()
        val bubbleText = composeRule.onNode(hasText(SWIPE_TEST_MESSAGE_BODY, substring = true), useUnmergedTree = true)
        val restingLeft = bubbleText.fetchSemanticsNode().boundsInRoot.left

        dragAndHold(OVERDRAG_PX)
        val travelled = bubbleText.fetchSemanticsNode().boundsInRoot.left - restingLeft

        assertTrue("an overdrag still moves the bubble", travelled > THRESHOLD_PX)
        assertTrue("an overdrag must be resisted", travelled < OVERDRAG_PX)
        assertTrue("resistance must not exceed the ceiling", travelled < MAXIMUM_PX)

        composeRule.onNodeWithTag(SWIPE_TEST_HOST_TAG).performTouchInput { cancel() }
    }

    private fun glyphTag(): String = messageReplySwipeGlyphTestTag(SWIPE_TEST_MESSAGE_ID)

    /** Presses on the row and holds a forward drag of [distance] pixels. */
    private fun dragAndHold(distance: Float) {
        composeRule.onNodeWithTag(SWIPE_TEST_HOST_TAG).performTouchInput {
            down(centerLeft)
            moveBy(Offset(distance, 0f))
        }
        composeRule.waitForIdle()
    }

    /** Composes one real controller-backed incoming bubble inside the gesture host. */
    private fun renderBubble() {
        val surface = swipeTestSurface(context, reacted = false, mine = false, media = false)
        composeRule.setContent { SwipeTestBubbleHost(surface = surface) }
        composeRule.waitForIdle()
    }

    private companion object {
        const val DRAG_PX = 40f
        const val OVERDRAG_PX = 220f
        const val THRESHOLD_PX = 64f
        const val MAXIMUM_PX = 96f
        const val CENTRE_TOLERANCE_PX = 2f
        const val TOUCH_TARGET_PX = 48f
    }
}
