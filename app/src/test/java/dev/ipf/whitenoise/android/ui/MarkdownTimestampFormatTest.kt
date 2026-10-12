package dev.ipf.whitenoise.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class MarkdownTimestampFormatTest {
    /** Relative amounts switch units at the minute boundary and report past or future direction. */
    @Test
    fun relativeClockCrossesSecondsMinutesAndDirectionWithoutStoringLabels() {
        assertEquals(TimestampRelativeAmount(59, 's', true), timestampRelativeAmount(60, 1))
        assertEquals(TimestampRelativeAmount(1, 'm', true), timestampRelativeAmount(60, 0))
        assertEquals(TimestampRelativeAmount(0, 's', false), timestampRelativeAmount(60, 60))
        assertEquals(TimestampRelativeAmount(1, 's', false), timestampRelativeAmount(60, 61))
        assertEquals(TimestampRelativeAmount(1, 'm', false), timestampRelativeAmount(60, 120))
    }

    /** Distances between the signed 64-bit extremes are computed without overflow. */
    @Test
    fun relativeDistanceBetweenSignedExtremesDoesNotOverflow() {
        val expected =
            BigInteger
                .valueOf(Long.MAX_VALUE)
                .subtract(BigInteger.valueOf(Long.MIN_VALUE))
                .divide(BigInteger.valueOf(31536000))
                .longValueExact()
        assertEquals(
            TimestampRelativeAmount(expected, 'y', true),
            timestampRelativeAmount(Long.MAX_VALUE, Long.MIN_VALUE),
        )
        assertEquals(
            TimestampRelativeAmount(expected, 'y', false),
            timestampRelativeAmount(Long.MIN_VALUE, Long.MAX_VALUE),
        )
        assertEquals(TimestampRelativeAmount(1, 's', true), timestampRelativeAmount(Long.MIN_VALUE + 1, Long.MIN_VALUE))
        assertEquals(
            TimestampRelativeAmount(1, 's', false),
            timestampRelativeAmount(Long.MAX_VALUE - 1, Long.MAX_VALUE),
        )
    }

    /** A negative epoch formats in every absolute style, distinctly per locale and precision. */
    @Test
    fun negativeEpochUsesCurrentLocalCalendarAndAllAbsoluteStyles() {
        val utc = ZoneId.of("UTC")
        for (style in "tTdDfFsS") {
            val label = markdownTimestampAbsolute(-1, style, Locale.US, utc)
            assertFalse(label.contains("<t:"))
            assertTrue(label.isNotBlank())
        }
        assertEquals("12/31/69", markdownTimestampAbsolute(-1, 'd', Locale.US, utc))
        assertNotEquals(
            markdownTimestampAbsolute(-1, 'D', Locale.US, utc),
            markdownTimestampAbsolute(-1, 'D', Locale.FRANCE, utc),
        )
        assertNotEquals(
            markdownTimestampAbsolute(-1, 't', Locale.US, utc),
            markdownTimestampAbsolute(-1, 'T', Locale.US, utc),
        )
        assertNotEquals(
            markdownTimestampAbsolute(-1, 's', Locale.US, utc),
            markdownTimestampAbsolute(-1, 'S', Locale.US, utc),
        )
    }

    /** Labels follow the zone's daylight-saving transition and differ between zones. */
    @Test
    fun timezoneAndDaylightSavingAreResolvedForEachProjection() {
        val seconds = Instant.parse("2026-03-08T09:59:00Z").epochSecond
        val losAngeles = ZoneId.of("America/Los_Angeles")
        assertEquals("1:59 AM", markdownTimestampAbsolute(seconds, 't', Locale.US, losAngeles).replace('\u202f', ' '))
        assertEquals(
            "3:00 AM",
            markdownTimestampAbsolute(seconds + 60, 't', Locale.US, losAngeles).replace('\u202f', ' '),
        )
        assertNotEquals(
            markdownTimestampAbsolute(seconds, 'F', Locale.US, losAngeles),
            markdownTimestampAbsolute(seconds, 'F', Locale.US, ZoneId.of("Asia/Tokyo")),
        )
    }

    /** Each relative label waits only until its own count or unit next changes, in either direction. */
    @Test
    fun relativeChangeWaitsForTheNextLabelBoundary() {
        // Past: 59 s becomes "1 minute" next second, 61 s becomes "2 minutes" at 120 s.
        assertEquals(1, timestampRelativeChangeSeconds(0, 59))
        assertEquals(59, timestampRelativeChangeSeconds(0, 61))
        assertEquals(3600L - 5, timestampRelativeChangeSeconds(0, 2L * 3600 + 5))
        assertEquals(86400L - 3600, timestampRelativeChangeSeconds(0, 3L * 86400 + 3600))
        // Future: "in 1 hour" becomes "in 59 minutes" one second later.
        assertEquals(1, timestampRelativeChangeSeconds(3600, 0))
        assertEquals(2, timestampRelativeChangeSeconds(3601, 0))
        assertEquals(1, timestampRelativeChangeSeconds(60, 0))
        assertEquals(1, timestampRelativeChangeSeconds(Long.MIN_VALUE, Long.MIN_VALUE))
        assertTrue(timestampRelativeChangeSeconds(Long.MAX_VALUE, Long.MIN_VALUE) > 0)
    }

    /** The refresh lands on the soonest label boundary's whole second and never sleeps past the hourly cap. */
    @Test
    fun relativeRefreshFollowsTheSoonestLabelAndIsCapped() {
        assertEquals(1000, timestampRelativeRefreshMillis(listOf(0), 59_000))
        assertEquals(750, timestampRelativeRefreshMillis(listOf(0), 59_250))
        assertEquals(30_000, timestampRelativeRefreshMillis(listOf(0), 150_000))
        assertEquals(1000, timestampRelativeRefreshMillis(listOf(0, 120), 150_000))
        assertEquals(3_600_000, timestampRelativeRefreshMillis(listOf(0), 40L * 86_400_000))
        assertEquals(3_600_000L - 10, timestampRelativeRefreshMillis(listOf(Long.MIN_VALUE), 10))
    }

    /** Instants beyond java.time's calendar range fall back to the exact canonical token. */
    @Test
    fun calendarRangeFailureRetainsExactCanonicalSignedToken() {
        assertEquals("<t:${Long.MIN_VALUE}:F>", markdownTimestampAbsolute(Long.MIN_VALUE, 'F'))
        assertEquals("<t:${Long.MAX_VALUE}:s>", markdownTimestampAbsolute(Long.MAX_VALUE, 's'))
    }
}
