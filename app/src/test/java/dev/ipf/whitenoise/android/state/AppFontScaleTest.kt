package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Test

class AppFontScaleTest {
    // ---- preference round-trip ----------------------------------------------

    @Test
    fun fromPreference_roundTripsEveryStep() {
        AppFontScale.entries.forEach { scale ->
            assertEquals(scale, AppFontScale.fromPreference(scale.preferenceValue))
        }
    }

    @Test
    fun fromPreference_defaultsToDefaultForNullOrUnknown() {
        assertEquals(AppFontScale.Default, AppFontScale.fromPreference(null))
        assertEquals(AppFontScale.Default, AppFontScale.fromPreference("garbage"))
        assertEquals(AppFontScale.Default, AppFontScale.fromPreference(""))
    }

    // ---- scale mapping -------------------------------------------------------

    @Test
    fun factors_matchTheIssueTable() {
        assertEquals(0.85f, AppFontScale.Small.factor)
        assertEquals(1f, AppFontScale.Default.factor)
        assertEquals(1.15f, AppFontScale.Large.factor)
        assertEquals(1.3f, AppFontScale.ExtraLarge.factor)
    }

    @Test
    fun stepsAreOrderedSmallestToLargest() {
        assertEquals(
            AppFontScale.entries.sortedBy { it.factor },
            AppFontScale.entries.toList(),
        )
    }
}
