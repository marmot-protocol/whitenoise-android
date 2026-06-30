package dev.ipf.whitenoise.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class StickyDayRibbonTest {
    @Test
    fun stickyDayRibbonRequiresActualScrollableContent() {
        assertEquals(false, shouldShowStickyDayRibbon(isScrollInProgress = true, canScrollContent = false, label = "Today"))
        assertEquals(true, shouldShowStickyDayRibbon(isScrollInProgress = true, canScrollContent = true, label = "Today"))
        assertEquals(false, shouldShowStickyDayRibbon(isScrollInProgress = false, canScrollContent = true, label = "Today"))
        assertEquals(false, shouldShowStickyDayRibbon(isScrollInProgress = true, canScrollContent = true, label = ""))
    }
}
