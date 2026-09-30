package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.whitenoise.android.state.MAX_POLL_DEADLINE_SECONDS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Keeps invalid custom durations away from the MDK poll creation boundary. */
class PollCustomDurationTest {
    /** Every offered unit accepts a value that is not a preset. */
    @Test fun convertsEachUnit() {
        val expected = listOf(37L, 37L * 60L, 37L * 3_600L, 17L * 86_400L)
        PollDurationUnit.entries.zip(expected).forEach { (unit, seconds) ->
            val selection = custom("${seconds / unit.seconds}", unit)
            assertEquals(seconds, validatePollDeadlineSelection(selection).durationSeconds)
        }
    }

    /** The editor includes the one-second minimum and the exact thirty-day maximum. */
    @Test fun acceptsBoundsAndUnicodeDecimalDigits() {
        assertEquals(1L, validatePollDeadlineSelection(custom("1", PollDurationUnit.SECONDS)).durationSeconds)
        val thirtyDays = validatePollDeadlineSelection(custom("30", PollDurationUnit.DAYS))
        assertEquals(MAX_POLL_DEADLINE_SECONDS, thirtyDays.durationSeconds)
        assertEquals(60L, validatePollDeadlineSelection(custom("١", PollDurationUnit.MINUTES)).durationSeconds)
    }

    /** Blank, signed, fractional, malformed and zero input must show a recoverable error. */
    @Test fun rejectsInvalidIntegers() {
        assertIssue(" ", PollDeadlineIssue.REQUIRED)
        for (value in listOf("0", "-1", "+1", "1.5", "1m", "1 0")) {
            assertIssue(value, PollDeadlineIssue.INVALID)
        }
    }

    /** Unit conversion rejects the next value and huge pasted input without overflow. */
    @Test fun rejectsOverLimitAndOverflow() {
        assertIssue("31", PollDeadlineIssue.TOO_LONG, PollDurationUnit.DAYS)
        assertIssue("2592001", PollDeadlineIssue.TOO_LONG, PollDurationUnit.SECONDS)
        assertIssue("999999999999999999999999999999", PollDeadlineIssue.TOO_LONG)
    }

    /** Switching away from the custom editor retains its draft and selected unit. */
    @Test fun presetAndCustomDraftRemainIndependent() {
        val custom = custom("25", PollDurationUnit.HOURS).copy(presetSeconds = 300L)
        val preset = custom.copy(customSelected = false)
        assertEquals(300L, validatePollDeadlineSelection(preset).durationSeconds)
        assertEquals("25", preset.customValue)
        assertEquals(PollDurationUnit.HOURS, preset.customUnit)
        assertEquals(25L * 3_600L, validatePollDeadlineSelection(preset.copy(customSelected = true)).durationSeconds)
        assertNull(validatePollDeadlineSelection(preset.copy(presetSeconds = null)).durationSeconds)
    }

    /** Checks that malformed input has no duration that could reach poll submission. */
    private fun assertIssue(
        value: String,
        expected: PollDeadlineIssue,
        unit: PollDurationUnit = PollDurationUnit.SECONDS,
    ) {
        val result = validatePollDeadlineSelection(custom(value, unit))
        assertNull(result.durationSeconds)
        assertEquals(expected, result.issue)
    }

    /** Builds the selected editor state so conversion tests exercise the submit boundary. */
    private fun custom(
        value: String,
        unit: PollDurationUnit,
    ) = PollDeadlineSelection(
        customSelected = true,
        customValue = value,
        customUnit = unit,
    )
}
