package dev.ipf.whitenoise.android.ui.conversation.messages

import dev.ipf.whitenoise.android.core.ReplySwipeGesture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reply-swipe rubber band: a drag tracks the finger up to the arming
 * threshold, then compresses so the bubble approaches but never reaches the
 * maximum, and the glyph's reveal follows the distance actually travelled.
 */
class MessageReplySwipeMetricsTest {
    private val threshold = 64f
    private val maximum = 96f

    /** Below the threshold the bubble follows the finger one-for-one. */
    @Test
    fun dragUpToTheThresholdIsUnresisted() {
        assertEquals(0f, resistedReplySwipeDistance(0f, threshold, maximum), TOLERANCE)
        assertEquals(30f, resistedReplySwipeDistance(30f, threshold, maximum), TOLERANCE)
        assertEquals(threshold, resistedReplySwipeDistance(threshold, threshold, maximum), TOLERANCE)
    }

    /** A backwards drag never moves the bubble. */
    @Test
    fun negativeDragStaysAtRest() {
        assertEquals(0f, resistedReplySwipeDistance(-120f, threshold, maximum), TOLERANCE)
    }

    /** Past the threshold the overdrag is compressed by the prototype's formula. */
    @Test
    fun overdragIsCompressedTowardTheMaximum() {
        // 64 + 32 * 64 / (64 + 64) = 80
        assertEquals(80f, resistedReplySwipeDistance(128f, threshold, maximum), TOLERANCE)
        // 64 + 32 * 192 / (192 + 64) = 88
        assertEquals(88f, resistedReplySwipeDistance(256f, threshold, maximum), TOLERANCE)
    }

    /** The maximum is an asymptote: an enormous drag still falls short of it. */
    @Test
    fun theMaximumIsNeverReached() {
        val huge = resistedReplySwipeDistance(100_000f, threshold, maximum)
        assertTrue("resisted distance must stay under the maximum", huge < maximum)
        assertTrue("a huge drag should sit close to the maximum", huge > maximum - 1f)
    }

    /** A degenerate threshold cannot divide by zero; it pins straight to the maximum. */
    @Test
    fun degenerateThresholdsPinToTheMaximum() {
        assertEquals(maximum, resistedReplySwipeDistance(10f, 0f, maximum), TOLERANCE)
        assertEquals(threshold, resistedReplySwipeDistance(500f, threshold, threshold), TOLERANCE)
    }

    /** Reveal progress is the travelled fraction of the threshold, clamped at 1. */
    @Test
    fun progressSaturatesOnceArmed() {
        assertEquals(0f, replySwipeProgress(0f, threshold), TOLERANCE)
        assertEquals(0.5f, replySwipeProgress(32f, threshold), TOLERANCE)
        assertEquals(1f, replySwipeProgress(threshold, threshold), TOLERANCE)
        assertEquals(1f, replySwipeProgress(90f, threshold), TOLERANCE)
        assertEquals(0f, replySwipeProgress(90f, 0f), TOLERANCE)
    }

    /** The first sliver of travel is held transparent so a nudge never flashes the arrow. */
    @Test
    fun theGlyphStaysHiddenBelowTheRevealFloor() {
        assertEquals(0f, replySwipeIconAlpha(0.04f), TOLERANCE)
        assertEquals(MessageReplySwipeMetrics.ICON_REVEAL_START, replySwipeIconAlpha(0.05f), TOLERANCE)
        assertEquals(1f, replySwipeIconAlpha(1f), TOLERANCE)
    }

    /** The glyph grows linearly to its ready scale across a full reveal. */
    @Test
    fun theGlyphGrowsToItsReadyScale() {
        assertEquals(1f, replySwipeIconScale(0f), TOLERANCE)
        assertEquals(1.1f, replySwipeIconScale(0.5f), TOLERANCE)
        assertEquals(MessageReplySwipeMetrics.ICON_READY_SCALE, replySwipeIconScale(1f), TOLERANCE)
    }

    /** Only a mostly-horizontal forward drag reports distance, and it is never clamped. */
    @Test
    fun forwardDistanceIgnoresVerticalAndBackwardGestures() {
        assertEquals(240f, ReplySwipeGesture(totalX = 240f, totalY = 10f).forwardReplySwipeDistance(), TOLERANCE)
        assertEquals(0f, ReplySwipeGesture(totalX = -240f, totalY = 0f).forwardReplySwipeDistance(), TOLERANCE)
        assertEquals(0f, ReplySwipeGesture(totalX = 40f, totalY = 90f).forwardReplySwipeDistance(), TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 0.001f
    }
}
