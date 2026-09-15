package dev.ipf.whitenoise.android.ui.conversation.messages

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** Revision metadata cannot crash the reader or replace malformed timestamps with an invented time. */
class EditHistoryTimestampTest {
    /** Native unsigned overflow has no representable date. */
    @Test fun unsignedMaximumDoesNotFormat() {
        assertEquals("", editHistoryRevisionTime(ULong.MAX_VALUE, Locale.GERMANY, ZoneId.of("UTC")))
    }

    /** An Instant can exist while its localized calendar date cannot. */
    @Test fun instantMaximumAndZoneOverflowDoNotFormat() {
        assertEquals("", editHistoryRevisionTime(Instant.MAX.epochSecond.toULong(), Locale.US, ZoneId.of("UTC")))
        assertEquals("", editHistoryRevisionTime(Instant.MAX.epochSecond.toULong(), Locale.US, ZoneId.of("+18:00")))
    }

    /** Exact accepted epoch seconds retain locale punctuation, seconds and the selected timezone. */
    @Test fun validRevisionPreservesExactLocalizedDateAndZone() {
        assertEquals(
            "15.01.2027, 08:00:00 (UTC)",
            editHistoryRevisionTime(1_800_000_000uL, Locale.GERMANY, ZoneId.of("UTC")),
        )
        assertEquals(
            "15.01.2027, 17:00:00 (Asia/Tokyo)",
            editHistoryRevisionTime(1_800_000_000uL, Locale.GERMANY, ZoneId.of("Asia/Tokyo")),
        )
    }
}
