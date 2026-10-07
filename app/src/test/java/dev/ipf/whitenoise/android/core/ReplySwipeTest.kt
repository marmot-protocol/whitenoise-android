package dev.ipf.whitenoise.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplySwipeTest {
    /** Disabled direction never takes ownership, even after a later reversal into an enabled side. */
    @Test fun directionalIntentRejectsDisabledStartsAndVerticalScroll() {
        val disabled =
            dev.ipf.whitenoise.android.ui.common
                .DirectionalSwipeIntent(8f, false, true)
        org.junit.Assert.assertFalse(disabled.move(-30f, 0f))
        org.junit.Assert.assertFalse(disabled.move(130f, 0f))
        assertTrue(disabled.cancelled)
        val vertical =
            dev.ipf.whitenoise.android.ui.common
                .DirectionalSwipeIntent(8f, true, true)
        org.junit.Assert.assertFalse(vertical.move(5f, 20f))
        org.junit.Assert.assertFalse(vertical.move(100f, 0f))
    }

    /** Reversing within the original side retains ownership; crossing the origin cancels permanently. */
    @Test fun directionalIntentDisarmsAndCancelsReversal() {
        val swipe =
            dev.ipf.whitenoise.android.ui.common
                .DirectionalSwipeIntent(8f, true, true)
        org.junit.Assert.assertTrue(swipe.move(100f, 1f))
        assertEquals(1, swipe.direction)
        org.junit.Assert.assertTrue(swipe.move(-70f, 0f))
        assertEquals(30f, swipe.x)
        org.junit.Assert.assertFalse(swipe.move(-80f, 0f))
        assertTrue(swipe.cancelled)
    }

    /** A sufficiently horizontal rightward gesture retains the legacy reply threshold. */
    @Test
    fun rightwardMostlyHorizontalSwipePastThresholdTriggersReply() {
        assertTrue(ReplySwipe.shouldTriggerReply(totalX = 72f, totalY = 12f, threshold = 64f))
    }

    /** Wrong-direction, short and vertical gestures preserve scrolling instead of issuing reply. */
    @Test
    fun leftwardShortOrMostlyVerticalSwipesDoNotTriggerReply() {
        assertFalse(ReplySwipe.shouldTriggerReply(totalX = -90f, totalY = 0f, threshold = 64f))
        assertFalse(ReplySwipe.shouldTriggerReply(totalX = 42f, totalY = 0f, threshold = 64f))
        assertFalse(ReplySwipe.shouldTriggerReply(totalX = 72f, totalY = 80f, threshold = 64f))
    }

    /** Visual reply displacement clamps to its limit and ignores leftward movement. */
    @Test
    fun visualOffsetOnlyFollowsRightwardDragWithinLimit() {
        assertEquals(0f, ReplySwipe.visualOffset(totalX = -20f, maxOffset = 80f))
        assertEquals(36f, ReplySwipe.visualOffset(totalX = 36f, maxOffset = 80f))
        assertEquals(80f, ReplySwipe.visualOffset(totalX = 120f, maxOffset = 80f))
    }

    /** Accumulating diagonal deltas retains vertical intent, preventing a false reply trigger. */
    @Test
    fun gestureAccumulatorPreservesVerticalMovementForTheReplyDecision() {
        val gesture =
            ReplySwipeGesture()
                .dragBy(deltaX = 36f, deltaY = -44f)
                .dragBy(deltaX = 36f, deltaY = -44f)

        assertEquals(72f, gesture.totalX)
        assertEquals(-88f, gesture.totalY)
        assertEquals(0f, gesture.visualOffset(maxOffset = 80f))
        assertFalse(gesture.shouldTriggerReply(threshold = 64f))
    }

    /** Accumulated horizontal motion both renders the offset and commits the reply threshold. */
    @Test
    fun gestureAccumulatorTriggersReplyForMostlyHorizontalRightwardMovement() {
        val gesture =
            ReplySwipeGesture()
                .dragBy(deltaX = 36f, deltaY = 6f)
                .dragBy(deltaX = 36f, deltaY = 6f)

        assertEquals(72f, gesture.visualOffset(maxOffset = 80f))
        assertTrue(gesture.shouldTriggerReply(threshold = 64f))
    }
}
