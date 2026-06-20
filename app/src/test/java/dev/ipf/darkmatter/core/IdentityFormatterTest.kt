package dev.ipf.darkmatter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
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
                "short(${length}-char input) returned ${shortened.length} chars: $shortened",
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
        val tomorrow = (Instant.now().epochSecond + 86_400L).toULong()

        assertEquals("future", IdentityFormatter.relativeTime(tomorrow))
    }

    @Test
    fun slightlyAheadTimestampReadsAsNowNotFuture() {
        // Clock skew shouldn't render the literal "future".
        val skewedAhead = (Instant.now().epochSecond + 5L).toULong()

        assertEquals("now", IdentityFormatter.relativeTime(skewedAhead))
    }

    @Test
    fun initialsTakeLeadingCodePointFromEachWord() {
        // Latin smoke test: the existing two-word path still works.
        assertEquals("AB", IdentityFormatter.initials("alice bobson"))
    }

    @Test
    fun initialsTakeNonBmpEmojiWhole() {
        // Two-word name whose first word leads with a non-BMP emoji: the
        // emoji must arrive whole rather than as a lone surrogate half.
        val grinningFace = String(Character.toChars(0x1F600))
        val expected = "${grinningFace.uppercase()}B"

        assertEquals(expected, IdentityFormatter.initials("$grinningFace bob"))
    }

    @Test
    fun initialsTakeTwoNonBmpCodePointsFromOneWord() {
        // Single-word name made entirely of non-BMP code points: both initials
        // must arrive whole. Pre-fix this would split a surrogate pair.
        val mathBoldX = String(Character.toChars(0x1D54F))
        val mathBoldA = String(Character.toChars(0x1D400))
        val word = mathBoldX + mathBoldA + "vier"
        val expected = (mathBoldX + mathBoldA).uppercase()

        assertEquals(expected, IdentityFormatter.initials(word))
    }

    @Test
    fun initialsFallBackForBlankInput() {
        assertEquals("DM", IdentityFormatter.initials(""))
        assertEquals("DM", IdentityFormatter.initials("   "))
    }

    @Test
    fun relativeTimeUsesInjectedCopyForUnits() {
        val twoHoursAgo = (Instant.now().epochSecond - 7_200L).toULong()
        val copy =
            RelativeTimeCopy(
                future = "FUT",
                now = "NOW",
                minutes = { count -> "$count-min" },
                hours = { count -> "$count-hr" },
                days = { count -> "$count-day" },
            )

        assertEquals("2-hr", IdentityFormatter.relativeTime(twoHoursAgo, copy, Locale.US))
    }

    @Test
    fun relativeTimePassesCountToPluralCallback() {
        // The unit callbacks must receive the integer count so a real
        // getQuantityString-backed callback can pick the correct plural form.
        val fortyFiveMinutesAgo = (Instant.now().epochSecond - (45 * 60L)).toULong()
        val copy =
            RelativeTimeCopy(
                future = "FUT",
                now = "NOW",
                minutes = { count -> "min=$count" },
                hours = { count -> "hr=$count" },
                days = { count -> "day=$count" },
            )

        assertEquals("min=45", IdentityFormatter.relativeTime(fortyFiveMinutesAgo, copy, Locale.US))
    }

    @Test
    fun relativeTimeFallsThroughToLocalizedDateForOlderInstants() {
        val eightDaysAgo = (Instant.now().epochSecond - (8 * 86_400L)).toULong()
        val expected =
            DateTimeFormatter
                .ofLocalizedDate(FormatStyle.MEDIUM)
                .withLocale(Locale.US)
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochSecond(eightDaysAgo.toLong()))

        assertEquals(expected, IdentityFormatter.relativeTime(eightDaysAgo, RelativeTimeCopy.Default, Locale.US))
    }
}
