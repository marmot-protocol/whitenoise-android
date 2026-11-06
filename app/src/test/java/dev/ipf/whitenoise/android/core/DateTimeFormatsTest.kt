package dev.ipf.whitenoise.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.FormatStyle
import java.util.Locale

/** Date and time format choices (#3602): stored values, regional defaults and pattern rewriting. */
class DateTimeFormatsTest {
    @Test
    fun storedPreferencesRoundTripAndUnknownValuesFallBackToAutomatic() {
        TimeFormatPreference.entries.forEach { assertEquals(it, TimeFormatPreference.fromPreference(it.preferenceValue)) }
        DateFormatPreference.entries.forEach { assertEquals(it, DateFormatPreference.fromPreference(it.preferenceValue)) }
        assertEquals(TimeFormatPreference.Automatic, TimeFormatPreference.fromPreference(null))
        assertEquals(DateFormatPreference.Automatic, DateFormatPreference.fromPreference("bogus"))
    }

    @Test
    fun automaticTimeFollowsAndroidSwitchOnlyWhenItIsSet() {
        assertNull(TimeFormatPreference.Automatic.use24Hour(null))
        assertEquals(true, TimeFormatPreference.Automatic.use24Hour(true))
        assertEquals(false, TimeFormatPreference.TwelveHour.use24Hour(true))
        assertEquals(true, TimeFormatPreference.TwentyFourHour.use24Hour(false))
    }

    @Test
    fun appLanguageWithoutRegionTakesThePhoneRegion() {
        assertEquals(Locale.UK, DateTimeFormatState.regionalLocale(Locale.ENGLISH, "GB"))
        // An app language that names its own region keeps it.
        assertEquals(Locale.US, DateTimeFormatState.regionalLocale(Locale.US, "GB"))
        assertEquals(Locale.ENGLISH, DateTimeFormatState.regionalLocale(Locale.ENGLISH, null))
        // A malformed platform region never breaks formatting.
        assertEquals(Locale.ENGLISH, DateTimeFormatState.regionalLocale(Locale.ENGLISH, "!!"))
    }

    @Test
    fun twentyFourHourDropsOnlyTheDayPeriodAndOneSeparator() {
        assertEquals("HH:mm", IdentityFormatter.coerceClockPattern("Bh:mm", use24Hour = true))
        assertEquals("HH:mm", IdentityFormatter.coerceClockPattern("a h:mm", use24Hour = true))
        assertEquals("HH:mm z", IdentityFormatter.coerceClockPattern("h:mm a z", use24Hour = true))
        // Newer CLDR data puts a narrow no-break space before the day period.
        assertEquals("HH:mm", IdentityFormatter.coerceClockPattern("h:mm\u202Fa", use24Hour = true))
    }

    @Test
    fun clockRewriteKeepsTheLocaleGlueBetweenDateAndTime() {
        // zh-Hant glues the day period to the hour; the date must not run into the time.
        assertEquals(
            "y/M/d HH:mm",
            IdentityFormatter.displayPattern(Locale.TAIWAN, DateTimeDisplay(use24Hour = true), FormatStyle.SHORT, FormatStyle.SHORT),
        )
        assertEquals(
            "MMM d, y, HH:mm",
            IdentityFormatter.displayPattern(Locale.US, DateTimeDisplay(use24Hour = true), FormatStyle.MEDIUM, FormatStyle.SHORT),
        )
    }

    @Test
    fun numericDateOrderReplacesOnlyTheDatePart() {
        val display = DateTimeDisplay(dateFormat = DateFormatPreference.DayMonthYearDot)
        assertEquals(
            "dd.MM.yyyy, HH:mm",
            IdentityFormatter.displayPattern(Locale.UK, display, FormatStyle.SHORT, FormatStyle.SHORT),
        )
        assertEquals("dd.MM.yyyy", IdentityFormatter.displayPattern(Locale.UK, display, FormatStyle.SHORT, null))
    }

    @Test
    fun withoutYearDropsTheYearFromLocalizedAndNumericDates() {
        val localized =
            IdentityFormatter.displayPattern(
                Locale.UK,
                DateTimeDisplay.LocaleDefault,
                FormatStyle.MEDIUM,
                FormatStyle.SHORT,
                withoutYear = true,
            )
        assertFalse(localized, 'y' in localized)
        assertTrue(localized, localized.endsWith(", HH:mm"))
        assertEquals(
            "MM-dd, HH:mm",
            IdentityFormatter.displayPattern(
                Locale.UK,
                DateTimeDisplay(dateFormat = DateFormatPreference.YearMonthDay),
                FormatStyle.MEDIUM,
                FormatStyle.SHORT,
                withoutYear = true,
            ),
        )
    }

    @Test
    fun explicitChoicesFormatTheExampleMoment() {
        val instant = Instant.parse("2026-12-31T15:04:00Z")
        val display = DateTimeDisplay(use24Hour = true, dateFormat = DateFormatPreference.YearMonthDay)
        assertEquals(
            "2026-12-31, 15:04",
            IdentityFormatter.dateTime(instant, Locale.US, ZoneOffset.UTC, display, FormatStyle.SHORT, FormatStyle.SHORT),
        )
    }

    @Test
    fun currentYearCheckRejectsUnrepresentableStamps() {
        val now = Instant.parse("2026-06-30T12:00:00Z")
        assertTrue(IdentityFormatter.isCurrentYear(Instant.parse("2026-01-02T00:00:00Z").epochSecond.toULong(), ZoneOffset.UTC, now))
        assertFalse(IdentityFormatter.isCurrentYear(Instant.parse("2025-12-31T00:00:00Z").epochSecond.toULong(), ZoneOffset.UTC, now))
        assertFalse(IdentityFormatter.isCurrentYear(ULong.MAX_VALUE, ZoneOffset.UTC, now))
    }
}
