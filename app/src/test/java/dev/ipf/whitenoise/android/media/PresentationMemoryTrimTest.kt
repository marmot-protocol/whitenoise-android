package dev.ipf.whitenoise.android.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Supported Android visibility thresholds and older pressure delivery share bounded retention targets. */
class PresentationMemoryTrimTest {
    /** Modern Android handles hidden/background signals, including future intermediate levels. */
    @Test
    fun modernVisibilitySignalsDoNotDependOnRemovedForegroundPressureCallbacks() {
        for (sdk in listOf(34, 35, 36, 37)) {
            for (level in listOf(0, 5, 10, 15, 19)) assertNull(presentationMemoryTrim(level, sdk))
            for (level in listOf(20, 25, 39)) {
                assertEquals(PresentationMemoryTrim.WARM, presentationMemoryTrim(level, sdk))
            }
            for (level in listOf(40, 60, 80, 100)) {
                assertEquals(PresentationMemoryTrim.EMPTY, presentationMemoryTrim(level, sdk))
            }
        }
    }

    /** Older Android can request foreground pressure relief without changing normal insertion budgets. */
    @Test
    fun olderPressureLevelsAreSupportedWithoutRepeatedHalving() {
        assertNull(presentationMemoryTrim(4, 33))
        for (level in listOf(5, 10, 14, 20, 39)) {
            val trim = requireNotNull(presentationMemoryTrim(level, 33))
            assertEquals(50L, trim.targetBytes(100))
        }
        for (level in listOf(15, 19, 40, 80)) {
            assertEquals(0L, requireNotNull(presentationMemoryTrim(level, 33)).targetBytes(100))
        }
    }
}
