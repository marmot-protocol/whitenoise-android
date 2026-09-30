package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Covers the optional MDK poll deadline passed from the Android composer. */
class PollDeadlineTest {
    /** A timeless poll preserves the native null deadline. */
    @Test
    fun noDeadlineStaysOpen() {
        assertNull(pollDeadlineEpochSeconds(null, 1_700_000_000_000L))
    }

    /** Short and maximum presets are anchored to submission time in Unix seconds. */
    @Test
    fun thirtyDayDeadlineUsesSendTime() {
        assertEquals(1_702_592_000uL, pollDeadlineEpochSeconds(MAX_POLL_DEADLINE_SECONDS, 1_700_000_000_999L))
        assertEquals(1_700_000_300uL, pollDeadlineEpochSeconds(POLL_FIVE_MINUTES_SECONDS, 1_700_000_000_999L))
        val customDuration = 37L * 60L
        assertEquals(1_700_002_220uL, pollDeadlineEpochSeconds(customDuration, 1_700_000_000_999L))
    }

    /** Invalid or overlong choices never reach the native poll API. */
    @Test
    fun invalidDurationsAreRejected() {
        for (duration in listOf(0L, -1L, MAX_POLL_DEADLINE_SECONDS + 1L)) {
            assertThrows(IllegalArgumentException::class.java) {
                pollDeadlineEpochSeconds(duration, 1_700_000_000_000L)
            }
        }
    }
}
