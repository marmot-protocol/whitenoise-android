package dev.ipf.whitenoise.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplySwipeTest {
    @Test
    fun rightwardMostlyHorizontalSwipePastThresholdTriggersReply() {
        assertTrue(ReplySwipe.shouldTriggerReply(totalX = 72f, totalY = 12f, threshold = 64f))
    }

    @Test
    fun leftwardShortOrMostlyVerticalSwipesDoNotTriggerReply() {
        assertFalse(ReplySwipe.shouldTriggerReply(totalX = -90f, totalY = 0f, threshold = 64f))
        assertFalse(ReplySwipe.shouldTriggerReply(totalX = 42f, totalY = 0f, threshold = 64f))
        assertFalse(ReplySwipe.shouldTriggerReply(totalX = 72f, totalY = 80f, threshold = 64f))
    }

    @Test
    fun visualOffsetOnlyFollowsRightwardDragWithinLimit() {
        assertEquals(0f, ReplySwipe.visualOffset(totalX = -20f, maxOffset = 80f))
        assertEquals(36f, ReplySwipe.visualOffset(totalX = 36f, maxOffset = 80f))
        assertEquals(80f, ReplySwipe.visualOffset(totalX = 120f, maxOffset = 80f))
    }

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
