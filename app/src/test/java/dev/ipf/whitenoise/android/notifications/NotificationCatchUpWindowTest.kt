package dev.ipf.whitenoise.android.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The catch-up window turns the coordinator's start and settle calls into one cohort generation. */
class NotificationCatchUpWindowTest {
    private var now = 10_000L
    private val window =
        NotificationCatchUpWindow(
            tailMs = 5_000L,
            maxOpenMs = 60_000L,
            clock = { now },
        )

    /** Nothing is current before the first catch-up starts. */
    @Test
    fun startsClosed() {
        assertNull(window.currentGeneration())
    }

    /** A running catch-up owns posts until it settles, then for the tail, then nothing. */
    @Test
    fun cohortCoversTheCatchUpAndItsTail() {
        window.open()
        assertEquals(1L, window.currentGeneration())
        now += 30_000L
        assertEquals(1L, window.currentGeneration())
        window.close()
        now += 5_000L
        assertEquals("posts still in flight belong to the cohort", 1L, window.currentGeneration())
        now += 1L
        assertNull(window.currentGeneration())
    }

    /** A successor that starts inside the tail extends the same cohort; one after the tail starts a new one. */
    @Test
    fun overlappingCatchUpsShareACohort() {
        window.open()
        window.close()
        now += 4_000L
        window.open()
        assertEquals(1L, window.currentGeneration())
        window.close()
        now += 5_001L
        window.open()
        assertEquals(2L, window.currentGeneration())
    }

    /** A catch-up that never settles stops owning posts once the cap passes, so live traffic rings again. */
    @Test
    fun hungCatchUpReleasesPostsAfterTheCap() {
        window.open()
        now += 60_000L
        assertEquals(1L, window.currentGeneration())
        now += 1L
        assertNull(window.currentGeneration())
        window.close()
        assertNull("settling a capped-out catch-up does not revive its cohort", window.currentGeneration())
    }

    /** Settling without a matching start is ignored. */
    @Test
    fun closeWithoutOpenIsIgnored() {
        window.close()
        assertNull(window.currentGeneration())
    }
}
