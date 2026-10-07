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
    @Test
    fun relativeClockCrossesSecondsMinutesAndDirectionWithoutStoringLabels() {
        assertEquals(TimestampRelativeAmount(59, 's', true), timestampRelativeAmount(60, 1))
        assertEquals(TimestampRelativeAmount(1, 'm', true), timestampRelativeAmount(60, 0))
        assertEquals(TimestampRelativeAmount(0, 's', false), timestampRelativeAmount(60, 60))
        assertEquals(TimestampRelativeAmount(1, 's', false), timestampRelativeAmount(60, 61))
        assertEquals(TimestampRelativeAmount(1, 'm', false), timestampRelativeAmount(60, 120))
    }

    @Test
    fun relativeDistanceBetweenSignedExtremesDoesNotOverflow() {
        val expected =
            BigInteger
                .valueOf(Long.MAX_VALUE)
                .subtract(BigInteger.valueOf(Long.MIN_VALUE))
                .divide(BigInteger.valueOf(31536000))
                .longValueExact()
        assertEquals(TimestampRelativeAmount(expected, 'y', true), timestampRelativeAmount(Long.MAX_VALUE, Long.MIN_VALUE))
        assertEquals(TimestampRelativeAmount(expected, 'y', false), timestampRelativeAmount(Long.MIN_VALUE, Long.MAX_VALUE))
        assertEquals(TimestampRelativeAmount(1, 's', true), timestampRelativeAmount(Long.MIN_VALUE + 1, Long.MIN_VALUE))
        assertEquals(TimestampRelativeAmount(1, 's', false), timestampRelativeAmount(Long.MAX_VALUE - 1, Long.MAX_VALUE))
    }

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
        assertNotEquals(markdownTimestampAbsolute(-1, 't', Locale.US, utc), markdownTimestampAbsolute(-1, 'T', Locale.US, utc))
        assertNotEquals(markdownTimestampAbsolute(-1, 's', Locale.US, utc), markdownTimestampAbsolute(-1, 'S', Locale.US, utc))
    }

    @Test
    fun timezoneAndDaylightSavingAreResolvedForEachProjection() {
        val seconds = Instant.parse("2026-03-08T09:59:00Z").epochSecond
        val losAngeles = ZoneId.of("America/Los_Angeles")
        assertEquals("1:59 AM", markdownTimestampAbsolute(seconds, 't', Locale.US, losAngeles).replace('\u202f', ' '))
        assertEquals("3:00 AM", markdownTimestampAbsolute(seconds + 60, 't', Locale.US, losAngeles).replace('\u202f', ' '))
        assertNotEquals(
            markdownTimestampAbsolute(seconds, 'F', Locale.US, losAngeles),
            markdownTimestampAbsolute(seconds, 'F', Locale.US, ZoneId.of("Asia/Tokyo")),
        )
    }

    @Test
    fun calendarRangeFailureRetainsExactCanonicalSignedToken() {
        assertEquals("<t:${Long.MIN_VALUE}:F>", markdownTimestampAbsolute(Long.MIN_VALUE, 'F'))
        assertEquals("<t:${Long.MAX_VALUE}:s>", markdownTimestampAbsolute(Long.MAX_VALUE, 's'))
    }
}
