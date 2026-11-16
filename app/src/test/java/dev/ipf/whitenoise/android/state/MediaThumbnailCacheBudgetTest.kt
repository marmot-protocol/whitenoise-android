package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Test

/** The decoded-thumbnail budget follows device RAM within its floor and ceiling. */
class MediaThumbnailCacheBudgetTest {
    /** A sixteenth of RAM between the floor and the ceiling. */
    @Test
    fun midRangeDevicesGetASixteenthOfTheirMemory() {
        assertEquals(128L * MIB, mediaThumbnailCacheBudget(2L * GIB, lowRam = false))
    }

    /** Small devices keep the former fixed budget, and large ones stop at the ceiling. */
    @Test
    fun budgetStaysWithinItsFloorAndCeiling() {
        assertEquals(MEDIA_THUMBNAIL_CACHE_MIN_BYTES, mediaThumbnailCacheBudget(512L * MIB, lowRam = false))
        assertEquals(MEDIA_THUMBNAIL_CACHE_MAX_BYTES, mediaThumbnailCacheBudget(8L * GIB, lowRam = false))
    }

    /** A device Android reports as low-RAM keeps the floor whatever its total memory. */
    @Test
    fun lowRamDevicesKeepTheFloor() {
        assertEquals(MEDIA_THUMBNAIL_CACHE_MIN_BYTES, mediaThumbnailCacheBudget(4L * GIB, lowRam = true))
    }

    private companion object {
        const val MIB = 1024L * 1024L
        const val GIB = 1024L * MIB
    }
}
