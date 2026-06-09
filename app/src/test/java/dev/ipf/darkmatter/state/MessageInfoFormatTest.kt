package dev.ipf.darkmatter.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class MessageInfoFormatTest {
    // ---- formatExactTimestamp ----------------------------------------------

    @Test
    fun formatTimestamp_emptyForUnsetSentinel() {
        // FFI "no timestamp" arrives as 0; the sheet hides the row rather
        // than rendering a 1970 epoch date.
        assertEquals("", formatExactTimestamp(0uL, UTC, US))
    }

    @Test
    fun formatTimestamp_emptyForULongAboveInstantMax() {
        // Above Instant.MAX would throw DateTimeException at runtime; the
        // guard returns the same empty-string sentinel as the unset case.
        val justPastMax = Instant.MAX.epochSecond.toULong() + 1uL
        assertEquals("", formatExactTimestamp(justPastMax, UTC, US))
        assertEquals("", formatExactTimestamp(ULong.MAX_VALUE, UTC, US))
    }

    @Test
    fun formatTimestamp_rendersAbsoluteValueInGivenZone() {
        // 1780787662 → 2026-06-06 23:14:22 UTC. The exact JDK locale string
        // drifts between releases (comma placement, AM/PM suffix), so we
        // assert on stable substrings rather than a byte-equal string.
        val text = formatExactTimestamp(1780787662uL, UTC, US)
        assertTrue("got: $text", text.contains("June"))
        assertTrue("got: $text", text.contains("2026"))
        assertTrue("got: $text", text.contains("14:22"))
    }

    @Test
    fun formatTimestamp_zoneShiftsRendering() {
        val recordedAt = 1780787662uL
        val utc = formatExactTimestamp(recordedAt, UTC, US)
        val tokyo = formatExactTimestamp(recordedAt, ZoneId.of("Asia/Tokyo"), US)
        // 23:14 UTC → 08:14 next-day Tokyo (UTC+9). Same minutes, different
        // calendar day. That difference is the contract we care about.
        assertTrue("utc=$utc tokyo=$tokyo", utc != tokyo)
        assertTrue("got tokyo=$tokyo", tokyo.contains("June 7"))
        assertTrue("got utc=$utc", utc.contains("June 6"))
    }

    @Test
    fun formatTimestamp_includesTimezone() {
        // The doc contract promises a zone-qualified absolute time so the
        // value is unambiguous when the user travels or has a skewed device
        // clock. Bare "11:14:22 PM" without zone would be ambiguous.
        val text = formatExactTimestamp(1780787662uL, UTC, US)
        assertTrue("got: $text", text.contains("UTC") || text.contains("Coordinated Universal Time"))
    }

    @Test
    fun formatTimestamp_localeChangesMonthName() {
        val recordedAt = 1780787662uL
        val englishMonth = formatExactTimestamp(recordedAt, UTC, US)
        val germanMonth = formatExactTimestamp(recordedAt, UTC, Locale.GERMAN)
        assertTrue("english=$englishMonth", englishMonth.contains("June"))
        assertTrue("german=$germanMonth", germanMonth.contains("Juni"))
    }

    // ---- shortHex -----------------------------------------------------------

    @Test
    fun shortHex_blankReturnsBlank() {
        assertEquals("", shortHex(""))
    }

    @Test
    fun shortHex_shortInputReturnsUnchanged() {
        assertEquals("abc", shortHex("abc", head = 8, tail = 4))
    }

    @Test
    fun shortHex_boundaryLengthReturnsUnchanged() {
        // head + tail = 12; input length 12 → no truncation.
        val twelve = "abcdef012345"
        assertEquals(twelve, shortHex(twelve, head = 8, tail = 4))
    }

    @Test
    fun shortHex_longInputTruncatesWithEllipsis() {
        val pubkey = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
        // head=8, tail=4 → "abcdef01…6789"
        assertEquals("abcdef01…6789", shortHex(pubkey, head = 8, tail = 4))
    }

    @Test
    fun shortHex_customHeadAndTail() {
        assertEquals("ab…ef", shortHex("abcdef", head = 2, tail = 2))
    }

    // ---- labelFor -----------------------------------------------------------

    // ---- absDelta -----------------------------------------------------------

    @Test
    fun absDelta_zeroWhenEqual() {
        assertEquals(0uL, absDelta(100uL, 100uL))
        assertEquals(0uL, absDelta(0uL, 0uL))
    }

    @Test
    fun absDelta_symmetric() {
        // a-b and b-a must agree — order-independence is the contract.
        assertEquals(absDelta(100uL, 90uL), absDelta(90uL, 100uL))
    }

    @Test
    fun absDelta_doesNotUnderflow() {
        // ULong subtraction would otherwise wrap to a huge positive number.
        assertEquals(7uL, absDelta(3uL, 10uL))
    }

    // ---- shouldShowOriginalTimestamp ---------------------------------------

    @Test
    fun shouldShowOriginal_falseWhenEitherUnset() {
        // Caller falls back to the other field when one side is the FFI
        // "unset" sentinel; showing a second row would be misleading.
        assertFalse(shouldShowOriginalTimestamp(recordedAtSeconds = 0uL, receivedAtSeconds = 1_000uL))
        assertFalse(shouldShowOriginalTimestamp(recordedAtSeconds = 1_000uL, receivedAtSeconds = 0uL))
        assertFalse(shouldShowOriginalTimestamp(recordedAtSeconds = 0uL, receivedAtSeconds = 0uL))
    }

    @Test
    fun shouldShowOriginal_falseWhenWithinDefaultSkewTolerance() {
        // Inside 5s — clock-skew noise, not a real disagreement.
        assertFalse(shouldShowOriginalTimestamp(recordedAtSeconds = 1_000uL, receivedAtSeconds = 1_000uL))
        assertFalse(shouldShowOriginalTimestamp(recordedAtSeconds = 1_000uL, receivedAtSeconds = 1_005uL))
        assertFalse(shouldShowOriginalTimestamp(recordedAtSeconds = 1_005uL, receivedAtSeconds = 1_000uL))
    }

    @Test
    fun shouldShowOriginal_trueJustAboveThreshold() {
        // 6s apart — strictly greater than the default 5s threshold.
        assertTrue(shouldShowOriginalTimestamp(recordedAtSeconds = 1_000uL, receivedAtSeconds = 1_006uL))
        assertTrue(shouldShowOriginalTimestamp(recordedAtSeconds = 1_006uL, receivedAtSeconds = 1_000uL))
    }

    @Test
    fun shouldShowOriginal_honorsCustomThreshold() {
        // With a 0s threshold, *any* divergence triggers the row.
        assertTrue(shouldShowOriginalTimestamp(1_000uL, 1_001uL, thresholdSeconds = 0uL))
        // Exactly-equal still suppresses the row (>, not >=).
        assertFalse(shouldShowOriginalTimestamp(1_000uL, 1_000uL, thresholdSeconds = 0uL))
    }

    // ---- labelFor -----------------------------------------------------------

    @Test
    fun labelFor_mapsEveryEnumVariant() {
        val labels =
            MessageStatusLabels(
                pending = "p",
                sent = "s",
                received = "r",
                failed = "f",
                streaming = "stream",
            )
        assertEquals("p", labelFor(MessageStatus.Pending, labels))
        assertEquals("s", labelFor(MessageStatus.Sent, labels))
        assertEquals("r", labelFor(MessageStatus.Received, labels))
        assertEquals("f", labelFor(MessageStatus.Failed, labels))
        assertEquals("stream", labelFor(MessageStatus.Streaming, labels))
    }

    private companion object {
        val UTC: ZoneId = ZoneId.of("UTC")
        val US: Locale = Locale.US
    }
}
