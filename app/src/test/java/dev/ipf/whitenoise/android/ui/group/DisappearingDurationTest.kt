package dev.ipf.whitenoise.android.ui.group

import dev.ipf.whitenoise.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisappearingDurationTest {
    @Test
    fun presetListIncludesThreeMonthsAboveFourWeeks() {
        val presets = disappearingPresetSecs.filter { it > 0 }
        val fourWeeksIndex = presets.indexOf(2_419_200L)
        val threeMonthsIndex = presets.indexOf(7_776_000L)
        assertTrue(fourWeeksIndex >= 0)
        assertEquals(fourWeeksIndex - 1, threeMonthsIndex)
    }

    @Test
    fun customSecondsUsesFixedMonthAndYearLengths() {
        assertEquals(2_592_000L, disappearingCustomSeconds(1, monthUnitIndex()))
        assertEquals(31_104_000L, disappearingCustomSeconds(12, monthUnitIndex()))
        assertEquals(31_536_000L, disappearingCustomSeconds(1, yearUnitIndex()))
        assertEquals(315_360_000L, disappearingCustomSeconds(10, yearUnitIndex()))
    }

    @Test
    fun customSecondsMultiplicationIsOverflowSafe() {
        val overflowValue = Long.MAX_VALUE / DISAPPEARING_SECONDS_PER_YEAR + 1
        val overflow =
            runCatching {
                disappearingCustomSecondsFromParts(overflowValue, DISAPPEARING_SECONDS_PER_YEAR)
            }.exceptionOrNull()
        assertTrue(overflow is ArithmeticException)
    }

    @Test
    fun pickerStatePrefersYearsThenMonthsForLongDurations() {
        assertEquals(
            DisappearingCustomPickerState(yearUnitIndex(), 10),
            disappearingCustomPickerStateForSeconds(315_360_000L),
        )
        assertEquals(
            DisappearingCustomPickerState(monthUnitIndex(), 12),
            disappearingCustomPickerStateForSeconds(31_104_000L),
        )
        assertEquals(
            DisappearingCustomPickerState(yearUnitIndex(), 1),
            disappearingCustomPickerStateForSeconds(31_536_000L),
        )
    }

    @Test
    fun pickerStateDoesNotCollapseMonthsIntoDays() {
        assertEquals(
            DisappearingCustomPickerState(monthUnitIndex(), 3),
            disappearingCustomPickerStateForSeconds(7_776_000L),
        )
    }

    @Test
    fun clampingAppliesWhenSwitchingToSmallerUnitCap() {
        assertEquals(4, clampDisappearingCustomValue(10, weekUnitIndex()))
        assertEquals(12, clampDisappearingCustomValue(20, monthUnitIndex()))
        assertEquals(10, clampDisappearingCustomValue(15, yearUnitIndex()))
    }

    @Test
    fun labelSpecUsesSingularMonthAndYear() {
        assertEquals(DisappearingLabelSpec.Months(1), disappearingLabelSpec(2_592_000L))
        assertEquals(DisappearingLabelSpec.Years(1), disappearingLabelSpec(31_536_000L))
    }

    @Test
    fun customUnitOrderEndsWithMonthsAndYears() {
        assertEquals(R.string.disappearing_unit_seconds, disappearingCustomUnits.first().labelRes)
        assertEquals(
            R.string.disappearing_unit_months,
            disappearingCustomUnits[disappearingCustomUnits.size - 2].labelRes,
        )
        assertEquals(R.string.disappearing_unit_years, disappearingCustomUnits.last().labelRes)
    }

    @Test
    fun labelSpecUsesPresetAndPluralMonthsAndYears() {
        assertEquals(DisappearingLabelSpec.Preset(R.string.disappearing_90_days), disappearingLabelSpec(7_776_000L))
        assertEquals(DisappearingLabelSpec.Years(1), disappearingLabelSpec(31_536_000L))
        assertEquals(DisappearingLabelSpec.Months(12), disappearingLabelSpec(31_104_000L))
        assertEquals(DisappearingLabelSpec.Years(10), disappearingLabelSpec(315_360_000L))
        assertEquals(DisappearingLabelSpec.Months(1), disappearingLabelSpec(2_592_000L))
    }

    @Test
    fun labelSpecFallsBackToSmallerUnitsForNonRoundValues() {
        assertEquals(DisappearingLabelSpec.Weeks(5), disappearingLabelSpec(3_024_000L))
        assertEquals(DisappearingLabelSpec.Days(2), disappearingLabelSpec(172_800L))
    }

    @Test
    fun labelSpecFallsBackBeforePluralCountOverflowsInt() {
        val yearCount = Int.MAX_VALUE.toLong() + 1L
        val seconds = Math.multiplyExact(yearCount, DISAPPEARING_SECONDS_PER_YEAR)

        assertEquals(DisappearingLabelSpec.Days(yearCount * 365L), disappearingLabelSpec(seconds))
    }

    @Test
    fun pickerStateUsesMinMonthAndYearBoundaries() {
        assertEquals(
            DisappearingCustomPickerState(monthUnitIndex(), 1),
            disappearingCustomPickerStateForSeconds(2_592_000L),
        )
        assertEquals(
            DisappearingCustomPickerState(yearUnitIndex(), 1),
            disappearingCustomPickerStateForSeconds(31_536_000L),
        )
    }

    @Test
    fun pickerStatePreservesOutOfCapExactDuration() {
        val fifteenYears = 15L * DISAPPEARING_SECONDS_PER_YEAR
        assertEquals(
            DisappearingCustomPickerState(yearUnitIndex(), 15),
            disappearingCustomPickerStateForSeconds(fifteenYears),
        )
    }

    @Test
    fun pickerStateDoesNotOverflowWhenSecondsExceedIntMax() {
        val absurdSeconds = Int.MAX_VALUE.toLong() + 50L
        assertEquals(
            DisappearingCustomPickerState(0, 59),
            disappearingCustomPickerStateForSeconds(absurdSeconds),
        )
    }

    private fun monthUnitIndex(): Int = disappearingCustomUnits.indexOfFirst { it.seconds == 2_592_000L }

    private fun yearUnitIndex(): Int = disappearingCustomUnits.indexOfFirst { it.seconds == 31_536_000L }

    private fun weekUnitIndex(): Int = disappearingCustomUnits.indexOfFirst { it.seconds == 604_800L }
}
