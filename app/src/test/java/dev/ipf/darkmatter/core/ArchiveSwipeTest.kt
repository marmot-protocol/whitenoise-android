package dev.ipf.darkmatter.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the swipe-to-archive axis-lock math (#296). Mirrors
 * [ReplySwipeTest] — the gesture decision is a pure function so it can be
 * exercised without a Compose harness.
 *
 * Constants used here match the composable's tuning in `DarkMatterApp.kt`:
 * ratio = 2x, minLead = 24dp (≈63px at xxhdpi 2.625), slop = 8dp.
 */
class ArchiveSwipeTest {
    private val ratio = 2f
    private val minLeadPx = 63f
    private val slopPx = 21f

    @Test
    fun clearlyHorizontalDragEngagesSwipe() {
        // Mostly-sideways drag: 120px across, 10px down.
        assertTrue(ArchiveSwipe.shouldEngageSwipe(dx = 120f, dy = 10f, ratio = ratio, minLeadPx = minLeadPx))
    }

    @Test
    fun verticalDominantDragDoesNotEngageSwipe() {
        // A scroll with a small sideways component must NOT archive.
        assertFalse(ArchiveSwipe.shouldEngageSwipe(dx = 30f, dy = 120f, ratio = ratio, minLeadPx = minLeadPx))
        // Equal travel on both axes is ambiguous — refuse it.
        assertFalse(ArchiveSwipe.shouldEngageSwipe(dx = 80f, dy = 80f, ratio = ratio, minLeadPx = minLeadPx))
        // Diagonal that satisfies neither the ratio nor the lead.
        assertFalse(ArchiveSwipe.shouldEngageSwipe(dx = 60f, dy = 50f, ratio = ratio, minLeadPx = minLeadPx))
    }

    @Test
    fun ratioSatisfiedButLeadTooSmallDoesNotEngage() {
        // Near-origin: 4px vs 1px satisfies the 2x ratio but the absolute
        // lead (3px) is far below minLeadPx, so it's plainly jitter.
        assertFalse(ArchiveSwipe.shouldEngageSwipe(dx = 4f, dy = 1f, ratio = ratio, minLeadPx = minLeadPx))
    }

    @Test
    fun leadSatisfiedButRatioTooSmallDoesNotEngage() {
        // dx=140, dy=72: lead = 68 >= 63 (lead OK) but ratio fails
        // because 140 < 72*2 = 144. Both conditions are required.
        assertFalse(ArchiveSwipe.shouldEngageSwipe(dx = 140f, dy = 72f, ratio = ratio, minLeadPx = minLeadPx))
    }

    @Test
    fun bothRatioAndLeadSatisfiedEngages() {
        // dx=160, dy=40 → ratio 160 >= 80 ✓, lead 120 >= 63 ✓
        assertTrue(ArchiveSwipe.shouldEngageSwipe(dx = 160f, dy = 40f, ratio = ratio, minLeadPx = minLeadPx))
    }

    @Test
    fun engageIsDirectionAgnostic() {
        // RTL / leftward swipe (negative dx) decides identically — only
        // magnitude matters for the dominance test.
        assertTrue(ArchiveSwipe.shouldEngageSwipe(dx = -160f, dy = 40f, ratio = ratio, minLeadPx = minLeadPx))
        assertFalse(ArchiveSwipe.shouldEngageSwipe(dx = 30f, dy = -120f, ratio = ratio, minLeadPx = minLeadPx))
    }

    @Test
    fun axisDecidedOnlyAfterSlopCleared() {
        assertFalse(ArchiveSwipe.axisDecided(dx = 5f, dy = 5f, slopPx = slopPx))
        assertTrue(ArchiveSwipe.axisDecided(dx = 0f, dy = 30f, slopPx = slopPx))
        assertTrue(ArchiveSwipe.axisDecided(dx = 30f, dy = 0f, slopPx = slopPx))
    }

    @Test
    fun clearVerticalScrollLocksOutSwipe() {
        // Mostly-downward scroll: 10px across, 120px down — lock the swipe out.
        assertTrue(ArchiveSwipe.shouldLockOutSwipe(dx = 10f, dy = 120f, ratio = ratio, minLeadPx = minLeadPx))
    }

    @Test
    fun horizontalDominantDragDoesNotLockOut() {
        // A deliberate sideways swipe must NOT be locked out as a scroll.
        assertFalse(ArchiveSwipe.shouldLockOutSwipe(dx = 120f, dy = 10f, ratio = ratio, minLeadPx = minLeadPx))
        // Equal travel on both axes is ambiguous — do not lock out.
        assertFalse(ArchiveSwipe.shouldLockOutSwipe(dx = 80f, dy = 80f, ratio = ratio, minLeadPx = minLeadPx))
    }

    @Test
    fun lockOutRequiresBothRatioAndLead() {
        // dy=72, dx=140: vertical lead = -68 (horizontal actually leads),
        // neither lock-out condition holds.
        assertFalse(ArchiveSwipe.shouldLockOutSwipe(dx = 140f, dy = 72f, ratio = ratio, minLeadPx = minLeadPx))
        // dy=72, dx=8: ratio 72 >= 8*2=16 ✓, lead 64 >= 63 ✓ → lock out.
        assertTrue(ArchiveSwipe.shouldLockOutSwipe(dx = 8f, dy = 72f, ratio = ratio, minLeadPx = minLeadPx))
        // dy=70, dx=40: ratio 70 < 40*2=80 fails → do NOT lock out.
        assertFalse(ArchiveSwipe.shouldLockOutSwipe(dx = 40f, dy = 70f, ratio = ratio, minLeadPx = minLeadPx))
    }

    @Test
    fun lockOutIsDirectionAgnostic() {
        // Upward scroll (negative dy) locks out identically — only
        // magnitude matters.
        assertTrue(ArchiveSwipe.shouldLockOutSwipe(dx = 10f, dy = -120f, ratio = ratio, minLeadPx = minLeadPx))
        // Leftward (RTL) horizontal swipe is not a scroll.
        assertFalse(ArchiveSwipe.shouldLockOutSwipe(dx = -120f, dy = 10f, ratio = ratio, minLeadPx = minLeadPx))
    }

    @Test
    fun incrementalHorizontalDragIsNotLockedOutBeforeReachingLead() {
        // Regression for #296 review: a normal horizontal swipe builds up
        // incrementally and crosses the small axis slop (21px) long before
        // it accumulates the 24dp (~63px) horizontal lead. At each of these
        // intermediate, slop-cleared frames the swipe must STAY enabled —
        // i.e. it must NOT be locked out — otherwise the deliberate swipe is
        // killed for the rest of the gesture and archive becomes unreachable.
        val incrementalHorizontalFrames =
            listOf(
                25f to 0f, // just past slop, no vertical component
                30f to 1f,
                40f to 2f,
                55f to 3f, // still below the 63px lead
                70f to 4f, // now past the lead — clearly a swipe
            )
        for ((dx, dy) in incrementalHorizontalFrames) {
            assertTrue(
                "incremental horizontal frame ($dx, $dy) must not lock out the swipe",
                !ArchiveSwipe.shouldLockOutSwipe(dx = dx, dy = dy, ratio = ratio, minLeadPx = minLeadPx),
            )
        }
    }
}
