package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone
import kotlin.math.abs

/**
 * Swipe-to-reply must translate the bubble or media surface and its attached
 * reaction summary as one visual unit while the stationary full-width row
 * keeps gesture ownership: equal horizontal deltas during the drag, a shared
 * return to rest on release or cancellation, and no accidental row motion for
 * a non-reacted control.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h800dp-mdpi")
class MessageBubbleSwipeReactionTranslationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val originalTimeZone = TimeZone.getDefault()

    /** Deterministic timestamp layout regardless of the machine's zone. */
    @Before
    fun pinTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    /** Restores the machine's zone after each test. */
    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(originalTimeZone)
    }

    /** During a held drag the bubble text and the reaction chip share one translation. */
    @Test
    fun incomingReactedTextTranslatesBubbleAndChipTogether() {
        assertSharedSwipeTranslation(mine = false)
    }

    /** Outgoing reacted messages translate their chip with the bubble as well. */
    @Test
    fun outgoingReactedTextTranslatesBubbleAndChipTogether() {
        assertSharedSwipeTranslation(mine = true)
    }

    /** A reacted media message translates its file card and chip together. */
    @Test
    fun reactedMediaTranslatesCardAndChipTogether() {
        assertSharedSwipeTranslation(mine = false, media = true)
    }

    /** RTL keeps the reaction summary attached to the translated bubble. */
    @Test
    fun rtlReactedTextTranslatesBubbleAndChipTogether() {
        assertSharedSwipeTranslation(mine = false, rtl = true)
    }

    /** A sub-threshold release drives onDragEnd and settles bubble and chip together. */
    @Test
    fun subThresholdReleaseReturnsBubbleAndChipToRestTogether() {
        renderBubble(reacted = true, mine = false, media = false, rtl = false)
        val host = composeRule.onNodeWithTag(SWIPE_TEST_HOST_TAG)
        val visual = composeRule.onNode(hasText(SWIPE_TEST_MESSAGE_BODY, substring = true), useUnmergedTree = true)
        val chip = composeRule.onNode(hasText(SWIPE_TEST_REACTION_EMOJI, substring = true), useUnmergedTree = true)
        val visualAtRest = visual.left()
        val chipAtRest = chip.left()

        host.performTouchInput {
            down(centerLeft)
            moveBy(Offset(RELEASE_DRAG_PX, 0f))
        }
        composeRule.waitForIdle()
        val visualDelta = visual.left() - visualAtRest
        assertTrue("swipe should translate the bubble", abs(visualDelta) > MIN_TRANSLATION_PX)
        assertEquals("chip shares the drag", visualDelta, chip.left() - chipAtRest, POSITION_TOLERANCE_PX)

        host.performTouchInput { up() }
        composeRule.waitForIdle()
        assertEquals("bubble settles after release", visualAtRest, visual.left(), POSITION_TOLERANCE_PX)
        assertEquals("chip settles with the bubble", chipAtRest, chip.left(), POSITION_TOLERANCE_PX)
    }

    /**
     * A non-reacted control measured on the PRODUCTION gesture row (not the
     * harness box): during a drag the bubble translates while the real row —
     * whose left edge carries the sender avatar slot — stays at rest, so an
     * offset accidentally applied to the row itself fails here.
     */
    @Test
    fun swipeMovesTheBubbleButNeverTheGestureRow() {
        renderBubble(reacted = false, mine = false, media = false, rtl = false)
        val host = composeRule.onNodeWithTag(SWIPE_TEST_HOST_TAG)
        val gestureRow =
            composeRule.onNodeWithTag(messageBubbleRowTestTag(SWIPE_TEST_MESSAGE_ID), useUnmergedTree = true)
        val bubbleText =
            composeRule.onNode(hasText(SWIPE_TEST_MESSAGE_BODY, substring = true), useUnmergedTree = true)
        val rowBoundsAtRest = gestureRow.fetchSemanticsNode().boundsInRoot
        val bubbleAtRest = bubbleText.left()

        host.performTouchInput {
            down(centerLeft)
            moveBy(Offset(DRAG_PX, 0f))
        }
        composeRule.waitForIdle()

        assertTrue("bubble should translate", abs(bubbleText.left() - bubbleAtRest) > MIN_TRANSLATION_PX)
        val rowDuringDrag = gestureRow.fetchSemanticsNode().boundsInRoot
        assertEquals("gesture row must stay at rest", rowBoundsAtRest.left, rowDuringDrag.left, POSITION_TOLERANCE_PX)
        assertEquals("gesture row must not stretch", rowBoundsAtRest.right, rowDuringDrag.right, POSITION_TOLERANCE_PX)

        host.performTouchInput { cancel() }
        composeRule.waitForIdle()
        assertEquals("bubble returns to rest", bubbleAtRest, bubbleText.left(), POSITION_TOLERANCE_PX)
    }

    /** Holds a drag, asserts equal bubble/chip deltas, and verifies the shared return to rest. */
    private fun assertSharedSwipeTranslation(
        mine: Boolean,
        media: Boolean = false,
        rtl: Boolean = false,
    ) {
        renderBubble(reacted = true, mine = mine, media = media, rtl = rtl)
        val host = composeRule.onNodeWithTag(SWIPE_TEST_HOST_TAG)
        val visualText = if (media) SWIPE_TEST_FILE_NAME else SWIPE_TEST_MESSAGE_BODY
        val visual = composeRule.onNode(hasText(visualText, substring = true), useUnmergedTree = true)
        val chip =
            composeRule.onNode(hasText(SWIPE_TEST_REACTION_EMOJI, substring = true), useUnmergedTree = true)
        val visualAtRest = visual.left()
        val chipAtRest = chip.left()

        host.performTouchInput {
            down(centerLeft)
            moveBy(Offset(DRAG_PX, 0f))
        }
        composeRule.waitForIdle()

        val visualDelta = visual.left() - visualAtRest
        val chipDelta = chip.left() - chipAtRest
        assertTrue("swipe should translate the bubble", abs(visualDelta) > MIN_TRANSLATION_PX)
        assertEquals("chip must share the bubble's translation", visualDelta, chipDelta, POSITION_TOLERANCE_PX)

        host.performTouchInput { cancel() }
        composeRule.waitForIdle()
        assertEquals("bubble returns to rest", visualAtRest, visual.left(), POSITION_TOLERANCE_PX)
        assertEquals("chip returns to rest with the bubble", chipAtRest, chip.left(), POSITION_TOLERANCE_PX)
    }

    /** The node's current left edge in root coordinates. */
    private fun SemanticsNodeInteraction.left(): Float = fetchSemanticsNode().boundsInRoot.left

    /** Composes one real controller-backed bubble inside the stationary gesture host. */
    private fun renderBubble(
        reacted: Boolean,
        mine: Boolean,
        media: Boolean,
        rtl: Boolean,
    ) {
        val surface = swipeTestSurface(context, reacted = reacted, mine = mine, media = media)
        composeRule.setContent {
            SwipeTestBubbleHost(surface = surface, rtl = rtl)
        }
        composeRule.waitForIdle()
    }

    private companion object {
        const val DRAG_PX = 90f
        const val RELEASE_DRAG_PX = 48f
        const val MIN_TRANSLATION_PX = 8f
        const val POSITION_TOLERANCE_PX = 1.5f
    }
}
