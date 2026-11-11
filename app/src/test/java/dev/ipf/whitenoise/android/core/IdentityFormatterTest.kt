package dev.ipf.whitenoise.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

class IdentityFormatterTest {
    @Test
    fun shortNeverReturnsStringLongerThanInput() {
        // Regression for #377: with the default 8/4 split, an ellipsis is 3
        // characters, so any abbreviated form is `prefix + 3 + suffix = 15`
        // chars. Inputs of length 14 or 15 used to expand instead of shrink
        // because the guard counted the ellipsis as a single char.
        for (length in 0..40) {
            val input = "a".repeat(length)
            val shortened = IdentityFormatter.short(input)
            assertTrue(
                "short($length-char input) returned ${shortened.length} chars: $shortened",
                shortened.length <= input.length,
            )
        }
    }

    @Test
    fun shortReturnsInputUnchangedWhenAbbreviationWouldNotShorten() {
        // 8 + 3 (ellipsis) + 4 = 15. Inputs of length 15 or less must round-trip.
        assertEquals("a".repeat(15), IdentityFormatter.short("a".repeat(15)))
        assertEquals("a".repeat(14), IdentityFormatter.short("a".repeat(14)))
        assertEquals("a".repeat(13), IdentityFormatter.short("a".repeat(13)))
    }

    @Test
    fun shortAbbreviatesInputsLongerThanPrefixSuffixAndEllipsis() {
        // 16-char input is the first length where abbreviation is a real win.
        val input = "abcdefghIJKLMNOP"
        assertEquals("abcdefgh...MNOP", IdentityFormatter.short(input))
    }

    @Test
    fun farFutureTimestampUsesExplicitLabel() {
        val tomorrow = (FIXED_NOW.epochSecond + 86_400L).toULong()

        assertEquals("future", relativeTimeAtFixedNow(tomorrow))
    }

    @Test
    fun slightlyAheadTimestampReadsAsNowNotFuture() {
        // Clock skew shouldn't render the literal "future".
        val skewedAhead = (FIXED_NOW.epochSecond + 5L).toULong()

        assertEquals("now", relativeTimeAtFixedNow(skewedAhead))
    }

    /** Initials take the leading letter only. */
    @Test
    fun initialsTakeTheLeadingLetterOnly() {
        // The prototype's monogram is a single glyph, whatever the word count.
        assertEquals("A", IdentityFormatter.initials("alice bobson"))
        assertEquals("X", IdentityFormatter.initials("Xavier"))
    }

    @Test
    fun initialsDropEmojiWordInFavorOfLetter() {
        // #427 supersedes the old "render the emoji whole" behavior: a letter
        // always wins over an emoji/symbol grapheme, since an emoji alone in the
        // avatar circle clips or shows as tofu. The #112 concern (never emit a
        // lone surrogate half) still holds — the emoji is dropped, not split.
        val grinningFace = String(Character.toChars(0x1F600))

        assertEquals("B", IdentityFormatter.initials("$grinningFace bob"))
        assertEquals("A", IdentityFormatter.initials("Alice $grinningFace"))
        assertEquals("A", IdentityFormatter.initials("$grinningFace Alice"))
    }

    @Test
    fun initialsForEmojiOnlyNameRenderFirstGraphemeWhole() {
        // No letters anywhere → fall back to the first emoji grapheme, taken
        // whole (not a split surrogate). #427.
        val grinningFace = String(Character.toChars(0x1F600))
        val fire = String(Character.toChars(0x1F525))

        assertEquals(grinningFace, IdentityFormatter.initials("$grinningFace$fire"))
    }

    /** Initials single word with trailing emoji uses letters. */
    @Test
    fun initialsSingleWordWithTrailingEmojiUsesLetters() {
        // A single word yields its first letter; the trailing emoji is never
        // reached (#427).
        val fire = String(Character.toChars(0x1F525))

        assertEquals("B", IdentityFormatter.initials("Bob$fire"))
    }

    @Test
    fun initialsDropZwjEmojiWordInFavorOfLetter() {
        // A ZWJ emoji sequence (family) is a multi-codepoint grapheme; whether
        // or not the break iterator groups it, the letter word still wins. #427.
        val family = "👨‍👩‍👧"

        assertEquals("F", IdentityFormatter.initials("$family Family"))
    }

    @Test
    fun relativeTimeDoesNotCrashOnOutOfRangeTimestamps() {
        // #468: untrusted epoch values must not throw DateTimeException into the
        // render path. ULong.MAX_VALUE wraps to -1L and a high-bit value wraps to
        // Long.MIN_VALUE; both clamp to epoch 0, which is well over a year before
        // FIXED_NOW, so the ladder renders the two-digit-year date.
        assertEquals("1/1/70", relativeTimeAtFixedNow(ULong.MAX_VALUE))
        assertEquals("1/1/70", relativeTimeAtFixedNow(0x8000000000000000uL))
        // A positive value far past year 9999 clamps and still formats without throwing.
        assertTrue(relativeTimeAtFixedNow(999_999_999_999uL).isNotBlank())
    }

    /** Initials take two non bmp code points from one word. */
    @Test
    fun initialsTakeTwoNonBmpCodePointsFromOneWord() {
        // Single-word name made entirely of non-BMP code points: the initial
        // must arrive whole. Pre-fix this would split a surrogate pair.
        val mathBoldX = String(Character.toChars(0x1D54F))
        val mathBoldA = String(Character.toChars(0x1D400))
        val word = mathBoldX + mathBoldA + "vier"
        val expected = mathBoldX.uppercase()

        assertEquals(expected, IdentityFormatter.initials(word))
    }

    @Test
    fun initialsFallBackForBlankInput() {
        assertEquals("DM", IdentityFormatter.initials(""))
        assertEquals("DM", IdentityFormatter.initials("   "))
    }

    @Test
    fun relativeTimeShowsCompactHoursWithinTheFirstDay() {
        // Past an hour but inside 24h: compact "Nh", no clock time (#848).
        val twoHoursAgo = (FIXED_NOW.epochSecond - 2 * 3_600L).toULong()

        assertEquals("2h", relativeTimeAtFixedNow(twoHoursAgo))
    }

    @Test
    fun relativeTimeShowsCompactHoursForSameDateAcrossDstFallBack() {
        val zone = ZoneId.of("America/New_York")
        val now = Instant.parse("2025-11-03T04:30:00Z") // 2025-11-02 23:30:00-05:00
        val message = now.minusSeconds(86_400L) // 2025-11-02 00:30:00-04:00

        assertEquals(
            "24h",
            IdentityFormatter.relativeTime(
                message.epochSecond.toULong(),
                RelativeTimeCopy.Default,
                Locale.US,
                now = now,
                zone = zone,
            ),
        )
    }

    @Test
    fun relativeTimeShowsYesterdayForPreviousDateAcrossDstFallBack() {
        val zone = ZoneId.of("America/New_York")
        val now = Instant.parse("2025-11-03T04:30:00Z") // 2025-11-02 23:30:00-05:00
        val message = Instant.parse("2025-11-02T03:30:00Z") // 2025-11-01 23:30:00-04:00

        assertEquals(
            "yesterday",
            IdentityFormatter.relativeTime(
                message.epochSecond.toULong(),
                RelativeTimeCopy.Default,
                Locale.US,
                now = now,
                zone = zone,
            ),
        )
    }

    @Test
    fun relativeTimePassesCountToPluralCallback() {
        // The unit callbacks must receive the integer count so a real
        // getQuantityString-backed callback can pick the correct plural form.
        val fortyFiveMinutesAgo = (FIXED_NOW.epochSecond - (45 * 60L)).toULong()
        val copy =
            RelativeTimeCopy(
                future = "FUT",
                now = "NOW",
                yesterday = "YDAY",
                minutes = { count -> "min=$count" },
                hours = { count -> "hr=$count" },
            )

        assertEquals("min=45", relativeTimeAtFixedNow(fortyFiveMinutesAgo, copy))
    }

    @Test
    fun relativeTimeShowsDateWithoutTimeForOlderInstants() {
        // 8 days ago is past the weekday window: locale date, no year, no time.
        val zone = ZoneId.of("UTC")
        val now = Instant.parse("2025-06-30T12:00:00Z")
        val message = Instant.parse("2025-06-14T12:00:00Z")

        assertEquals(
            "Jun 14",
            IdentityFormatter.relativeTime(
                message.epochSecond.toULong(),
                RelativeTimeCopy.Default,
                Locale.US,
                now = now,
                zone = zone,
            ),
        )
    }

    @Test
    fun relativeTimeShowsWeekdayWithinThePastWeek() {
        // Fixed "now" is Monday 30 June 2025, so three days earlier is a Friday.
        val threeDaysAgo = FIXED_NOW.minusSeconds(3 * 86_400L)

        assertEquals("Fri", relativeTimeAtFixedNow(threeDaysAgo.epochSecond.toULong()))
    }

    @Test
    fun relativeTimeShowsYesterdayForThePreviousDay() {
        val twentySixHoursAgo = FIXED_NOW.minusSeconds(26 * 3_600L)

        assertEquals("yesterday", relativeTimeAtFixedNow(twentySixHoursAgo.epochSecond.toULong()))
    }

    @Test
    fun relativeTimeShowsTwoDigitYearPastAYear() {
        val fourHundredDaysAgo = FIXED_NOW.minusSeconds(400 * 86_400L)

        assertEquals("5/26/24", relativeTimeAtFixedNow(fourHundredDaysAgo.epochSecond.toULong()))
    }

    @Test
    fun relativeTimeUsesLocaleDateOrderingForCjkNoYearDates() {
        val zone = ZoneId.of("UTC")
        val now = Instant.parse("2025-06-30T12:00:00Z")
        val message = Instant.parse("2025-06-14T12:00:00Z")

        assertEquals(
            "6月14日",
            IdentityFormatter.relativeTime(
                message.epochSecond.toULong(),
                RelativeTimeCopy.Default,
                Locale.CHINA,
                now = now,
                zone = zone,
            ),
        )
    }

    @Test
    fun stripYearFromLocalizedPatternPreservesDateOrder() {
        assertEquals("MMM d", IdentityFormatter.stripYearFromLocalizedDatePattern("MMM d, y"))
        assertEquals("d MMM", IdentityFormatter.stripYearFromLocalizedDatePattern("d MMM y"))
        assertEquals("M月d日", IdentityFormatter.stripYearFromLocalizedDatePattern("y年M月d日"))
        assertEquals("d MMM", IdentityFormatter.stripYearFromLocalizedDatePattern("d MMM 'de' y"))
    }

    // ---- clockTime 12/24-hour system-preference coercion --------------------

    @Test
    fun coerceClockPatternRewritesHourAndDayPeriodTokens() {
        assertEquals("HH:mm", IdentityFormatter.coerceClockPattern("h:mm a", use24Hour = true))
        assertEquals("HH:mm", IdentityFormatter.coerceClockPattern("a h:mm", use24Hour = true))
        assertEquals("h:mm a", IdentityFormatter.coerceClockPattern("HH:mm", use24Hour = false))
        // Already matching patterns stay stable.
        assertEquals("HH:mm", IdentityFormatter.coerceClockPattern("HH:mm", use24Hour = true))
        assertEquals("h:mm a", IdentityFormatter.coerceClockPattern("h:mm a", use24Hour = false))
        // Quoted literals are never rewritten.
        assertEquals("HH'h'mm", IdentityFormatter.coerceClockPattern("hh'h'mm a", use24Hour = true))
    }

    @Test
    fun clockTimeHonorsAForcedClockSystemAgainstTheLocaleDefault() {
        val epoch = utcEpoch(hour = 15, minute = 28)
        val utc = ZoneId.of("UTC")
        // en_US defaults to 12-hour: forcing 24-hour must drop the day period.
        assertEquals("15:28", IdentityFormatter.clockTime(epoch, Locale.US, utc, force24Hour = true))
        // de_DE defaults to 24-hour: forcing 12-hour must gain a day period.
        val german12 = IdentityFormatter.clockTime(epoch, Locale.GERMANY, utc, force24Hour = false)
        assertTrue("expected a 12-hour German rendering, got $german12", german12.startsWith("3:28"))
        assertTrue("expected a day-period marker, got $german12", german12.length > "3:28".length)
        // Null keeps the locale default untouched.
        val localeDefault = IdentityFormatter.clockTime(epoch, Locale.US, utc)
        assertTrue("expected the 12-hour US default, got $localeDefault", localeDefault.contains("3:28"))
    }

    /** Message bubble clock portion honors the forced clock system. */
    @Test
    fun messageBubbleClockPortionHonorsTheForcedClockSystem() {
        // Older than an hour, so the footer shows a clock time — the portion the
        // 12/24-hour preference governs.
        val epoch = utcEpoch(hour = 15, minute = 28)
        val utc = ZoneId.of("UTC")
        assertEquals(
            "15:28",
            IdentityFormatter.messageBubbleTime(epoch, locale = Locale.US, zone = utc, force24Hour = true),
        )
        val us12 =
            IdentityFormatter.messageBubbleTime(
                epoch,
                locale = Locale.GERMANY,
                zone = utc,
                force24Hour = false,
            )
        assertTrue("expected a 12-hour rendering, got $us12", us12.startsWith("3:28"))
    }

    /** Epoch seconds for a UTC civil time. */
    private fun utcEpoch(
        hour: Int,
        minute: Int,
    ): ULong =
        Instant
            .parse("2026-07-23T00:00:00Z")
            .plus(hour.toLong(), ChronoUnit.HOURS)
            .plus(minute.toLong(), ChronoUnit.MINUTES)
            .epochSecond
            .toULong()

    // ---- messageBubbleTime (bubble footer timestamps, #1513) ----------------

    /** Message bubble time empty for unset sentinel. */
    @Test
    fun messageBubbleTimeEmptyForUnsetSentinel() {
        assertEquals("", IdentityFormatter.messageBubbleTime(0uL))
    }

    /** Message bubble time shows now during the first minute. */
    @Test
    fun messageBubbleTimeShowsNowWithinFirstMinute() {
        val now = Instant.parse("2025-06-30T15:00:00Z")
        val sentAt = now.minusSeconds(59L)

        assertEquals("now", IdentityFormatter.messageBubbleTime(sentAt.epochSecond.toULong(), now = now))
    }

    /** Small peer-clock skew keeps the friendly now label. */
    @Test
    fun messageBubbleTimeTreatsSlightFutureSkewAsNow() {
        val now = Instant.parse("2025-06-30T15:00:00Z")
        val sentAt = now.plusSeconds(60L)

        assertEquals("now", IdentityFormatter.messageBubbleTime(sentAt.epochSecond.toULong(), now = now))
    }

    /** Minute copy remains localized and plural-aware throughout the first hour. */
    @Test
    fun messageBubbleTimeShowsLocalizedMinutesWithinFirstHour() {
        val now = Instant.parse("2025-06-30T15:00:00Z")
        val copy =
            RelativeTimeCopy(
                future = "future",
                now = "now",
                yesterday = "yesterday",
                minutes = { count -> "minutes=$count" },
                hours = { count -> "hours=$count" },
            )

        assertEquals(
            "minutes=1",
            IdentityFormatter.messageBubbleTime(now.minusSeconds(60L).epochSecond.toULong(), copy = copy, now = now),
        )
        assertEquals(
            "minutes=59",
            IdentityFormatter.messageBubbleTime(
                now.minusSeconds(3_599L).epochSecond.toULong(),
                copy = copy,
                now = now,
            ),
        )
    }

    /** Message bubble time shows clock time at one hour boundary. */
    @Test
    fun messageBubbleTimeShowsClockTimeAtOneHourBoundary() {
        val zone = ZoneId.of("UTC")
        val now = Instant.parse("2025-06-30T15:00:00Z")
        val sentAt = now.minusSeconds(3_600L)
        assertEquals(
            shortClock(sentAt, zone),
            IdentityFormatter.messageBubbleTime(
                sentAt.epochSecond.toULong(),
                locale = Locale.US,
                now = now,
                zone = zone,
            ),
        )
    }

    /** Future timestamps beyond the skew tolerance use explicit copy. */
    @Test
    fun messageBubbleTimeUsesFutureLabelBeyondSkewTolerance() {
        val now = Instant.parse("2025-06-30T15:00:00Z")
        val sentAt = now.plusSeconds(61L)

        assertEquals("future", IdentityFormatter.messageBubbleTime(sentAt.epochSecond.toULong(), now = now))
    }

    /** Untrusted high-bit epochs stay clamped and render without crashing. */
    @Test
    fun messageBubbleTimeSafelyFormatsExtremeTimestamp() {
        val now = Instant.parse("2025-06-30T15:00:00Z")

        assertTrue(IdentityFormatter.messageBubbleTime(ULong.MAX_VALUE, now = now).isNotBlank())
    }

    /** Formats a short clock label for the test locale. */
    private fun shortClock(
        instant: Instant,
        zone: ZoneId,
    ): String =
        DateTimeFormatter
            .ofLocalizedTime(FormatStyle.SHORT)
            .withLocale(Locale.US)
            .format(instant.atZone(zone))

    /** Formats against a fixed UTC Monday noon so day-boundary expectations never depend on the runner clock. */
    private fun relativeTimeAtFixedNow(
        epochSeconds: ULong,
        copy: RelativeTimeCopy = RelativeTimeCopy.Default,
    ): String = IdentityFormatter.relativeTime(epochSeconds, copy, Locale.US, now = FIXED_NOW, zone = ZoneOffset.UTC)

    private companion object {
        val FIXED_NOW: Instant = Instant.parse("2025-06-30T12:00:00Z")
    }

    @Test
    fun localDateIsNullForUnrepresentableReceivedStamps() {
        val kiritimati = ZoneId.of("Pacific/Kiritimati")
        val samoaWest = ZoneId.of("Etc/GMT+12")
        assertEquals(java.time.LocalDate.of(1970, 1, 1), IdentityFormatter.localDate(0uL, ZoneOffset.UTC))
        assertEquals(java.time.LocalDate.of(1969, 12, 31), IdentityFormatter.localDate(0uL, samoaWest))
        assertEquals(java.time.LocalDate.of(2026, 6, 6), IdentityFormatter.localDate(1_780_787_662uL, ZoneOffset.UTC))
        assertEquals(java.time.LocalDate.of(10000, 1, 1), IdentityFormatter.localDate(253_402_300_799uL, kiritimati))
        val invalid =
            listOf(
                253_402_300_800uL,
                40_000_000_000_000_000uL,
                Instant.MAX.epochSecond.toULong(),
                Long.MAX_VALUE.toULong(),
                ULong.MAX_VALUE,
            )
        for (stamp in invalid) {
            assertEquals(null, IdentityFormatter.localDate(stamp, kiritimati))
            assertEquals(java.time.LocalDate.of(10000, 1, 1), IdentityFormatter.clampedLocalDate(stamp, kiritimati))
            val utc = IdentityFormatter.clampedLocalDate(stamp, ZoneOffset.UTC)
            assertEquals(java.time.LocalDate.of(9999, 12, 31), utc)
        }
    }
}
