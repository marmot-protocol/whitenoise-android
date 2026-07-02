package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Covers [withRecordedAtOverride], the display-position pin applied when a
 * confirmed projection replaces an optimistic bubble: a non-null override
 * rewrites only `recordedAt` (so the bubble doesn't jump when the engine's
 * timestamp differs from the optimistic one), while a null override must
 * leave the record untouched.
 */
class RecordedAtOverrideTest {
    @Test
    fun overrideRewritesRecordedAtAndNothingElse() {
        val record = record(recordedAt = 100uL)

        val overridden = record.withRecordedAtOverride(42uL)

        assertEquals(42uL, overridden.recordedAt)
        assertEquals(record.copy(recordedAt = 42uL), overridden)
        // receivedAt in particular must stay the authoritative arrival time.
        assertEquals(100uL, overridden.receivedAt)
    }

    @Test
    fun nullOverrideReturnsTheRecordUnchanged() {
        val record = record(recordedAt = 100uL)

        assertSame(record, record.withRecordedAtOverride(null))
    }

    private fun record(recordedAt: ULong): AppMessageRecordFfi =
        AppMessageRecordFfi(
            messageIdHex = "message-1",
            direction = "sent",
            groupIdHex = "group",
            sender = "alice",
            plaintext = "hello",
            contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList()),
            kind = 9uL,
            tags = emptyList(),
            recordedAt = recordedAt,
            receivedAt = recordedAt,
        )
}
