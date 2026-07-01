package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for the pure coalescing decision behind issue #843: a burst of
 * media-bearing timeline batches must collapse into a single `listMedia`
 * full-scan refresh, while an isolated media batch still refreshes promptly and
 * unrelated text/reaction bursts can't starve the media cache.
 */
class CoalescedMediaRefreshTest {
    @Test
    fun nothingPendingNeverFlushes() {
        assertFalse(
            shouldFlushCoalescedMediaRefresh(coalescedBatches = 0, nextUpdateTouchesMedia = false),
        )
        assertFalse(
            shouldFlushCoalescedMediaRefresh(coalescedBatches = 0, nextUpdateTouchesMedia = true),
        )
    }

    @Test
    fun isolatedMediaBatchFlushesWhenBurstQuiesces() {
        assertTrue(
            shouldFlushCoalescedMediaRefresh(coalescedBatches = 1, nextUpdateTouchesMedia = false),
        )
    }

    @Test
    fun nonMediaBoundaryFlushesPendingMediaRefresh() {
        assertTrue(
            shouldFlushCoalescedMediaRefresh(coalescedBatches = 1, nextUpdateTouchesMedia = false, cap = 16),
        )
    }

    @Test
    fun defersWhileMediaBurstStillDraining() {
        assertFalse(
            shouldFlushCoalescedMediaRefresh(coalescedBatches = 1, nextUpdateTouchesMedia = true, cap = 16),
        )
        assertFalse(
            shouldFlushCoalescedMediaRefresh(coalescedBatches = 15, nextUpdateTouchesMedia = true, cap = 16),
        )
    }

    @Test
    fun capForcesFlushEvenWhenMediaBurstNeverDrains() {
        assertTrue(
            shouldFlushCoalescedMediaRefresh(coalescedBatches = 16, nextUpdateTouchesMedia = true, cap = 16),
        )
        assertTrue(
            shouldFlushCoalescedMediaRefresh(coalescedBatches = 20, nextUpdateTouchesMedia = true, cap = 16),
        )
    }

    @Test
    fun capOfOneFlushesEveryMediaBatch() {
        // Degenerate cap: never defers, matching pre-#843 per-batch behavior.
        assertTrue(
            shouldFlushCoalescedMediaRefresh(coalescedBatches = 1, nextUpdateTouchesMedia = true, cap = 1),
        )
    }
}
