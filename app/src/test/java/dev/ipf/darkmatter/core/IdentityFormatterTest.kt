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

    @Test
    fun initialsSingleWordWithTrailingEmojiUsesLetters() {
        // A single word keeps the two-letter monogram from its letters; the
        // trailing emoji is simply never reached (#427). Deliberately "BO", not
        // "B" — single-word names always yield up to two letters, matching the
        // existing "Xavier"-style behavior.
        val fire = String(Character.toChars(0x1F525))

        assertEquals("BO", IdentityFormatter.initials("Bob$fire"))
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
        // Long.MIN_VALUE; both clamp to a safe instant instead of crashing.
        val zone = ZoneId.systemDefault()
        val epochZeroFormatted =
            DateTimeFormatter
                .ofPattern("d MMM", Locale.US)
                .withZone(zone)
                .format(Instant.ofEpochSecond(0)) + " " +
                DateTimeFormatter
                    .ofLocalizedTime(FormatStyle.SHORT)
                    .withLocale(Locale.US)
                    .withZone(zone)
                    .format(Instant.ofEpochSecond(0))

        assertEquals(epochZeroFormatted, IdentityFormatter.relativeTime(ULong.MAX_VALUE, RelativeTimeCopy.Default, Locale.US))
        assertEquals(
            epochZeroFormatted,
            IdentityFormatter.relativeTime(0x8000000000000000uL, RelativeTimeCopy.Default, Locale.US),
        )
        // A positive value far past year 9999 clamps and still formats without throwing.
        val farFuture = IdentityFormatter.relativeTime(999_999_999_999uL, RelativeTimeCopy.Default, Locale.US)
        assertTrue(farFuture.isNotBlank())
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
    fun relativeTimeShowsClockTimePastAnHour() {
        val instant = Instant.now().minusSeconds(7_200L)
        val zone = ZoneId.systemDefault()
        val time =
            DateTimeFormatter
                .ofLocalizedTime(FormatStyle.SHORT)
                .withLocale(Locale.US)
                .withZone(zone)
                .format(instant)
        val expected =
            if (instant.atZone(zone).toLocalDate() == Instant.now().atZone(zone).toLocalDate()) {
                time
            } else {
                DateTimeFormatter
                    .ofPattern("d MMM", Locale.US)
                    .withZone(zone)
                    .format(instant) + " " + time
            }

        assertEquals(expected, IdentityFormatter.relativeTime(instant.epochSecond.toULong(), RelativeTimeCopy.Default, Locale.US))
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
    fun relativeTimeShowsDateAndTimeForOlderInstants() {
        val eightDaysAgo = Instant.now().minusSeconds(8 * 86_400L)
        val zone = ZoneId.systemDefault()
        val expected =
            DateTimeFormatter
                .ofPattern("d MMM", Locale.US)
                .withZone(zone)
                .format(eightDaysAgo) + " " +
                DateTimeFormatter
                    .ofLocalizedTime(FormatStyle.SHORT)
                    .withLocale(Locale.US)
                    .withZone(zone)
                    .format(eightDaysAgo)

        assertEquals(expected, IdentityFormatter.relativeTime(eightDaysAgo.epochSecond.toULong(), RelativeTimeCopy.Default, Locale.US))
    }
}
