package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit coverage for [SendTrace] — the pure formatting/id helper behind the
 * text-send latency trace (issue #913). These pin the two properties the trace
 * relies on: (1) back-to-back sends get distinct, monotonically increasing
 * one-run sequence ids so their phases stay correlatable in logcat, and (2) the
 * formatted line is privacy-safe — only phase names, durations, sequence ids,
 * and the small counts/booleans a caller explicitly passes appear.
 */
class SendTraceTest {
    @Before
    fun resetSequence() {
        SendTrace.resetSequenceForTest()
    }

    @Test
    fun sequencesAreDistinctAndMonotonic() {
        val first = SendTrace.nextSequence()
        val second = SendTrace.nextSequence()
        val third = SendTrace.nextSequence()
        assertEquals("s#1", first)
        assertEquals("s#2", second)
        assertEquals("s#3", third)
        assertNotEquals(first, second)
    }

    @Test
    fun lineWithoutSpanOrContext() {
        assertEquals(
            "s#1 accepted +0ms",
            SendTrace.line("s#1", "accepted", sinceStartMs = 0L),
        )
    }

    @Test
    fun lineWithSpanAndContext() {
        assertEquals(
            "s#4 ffi-return +842ms span=838ms attempt=1 msgIds=1",
            SendTrace.line(
                "s#4",
                "ffi-return",
                sinceStartMs = 842L,
                spanMs = 838L,
                "attempt" to 1,
                "msgIds" to 1,
            ),
        )
    }

    @Test
    fun nullContextValuesAreOmitted() {
        // A best-effort caller may pass a null (e.g. an unavailable count); it
        // must not render as the literal "null" which would be noise.
        assertEquals(
            "s#2 sent-flip +120ms bubble=local",
            SendTrace.line(
                "s#2",
                "sent-flip",
                sinceStartMs = 120L,
                spanMs = null,
                "bubble" to "local",
                "missing" to null,
            ),
        )
    }

    @Test
    fun completionPhaseOnlyLabelsActualLocalFlipAsSentFlip() {
        assertEquals("sent-flip", SendTrace.completionPhase(insertedSentBubble = true))
        assertEquals("send-complete", SendTrace.completionPhase(insertedSentBubble = false))
        assertEquals(
            "s#2 send-complete +480ms bubble=echoed flip=echo-reconcile",
            SendTrace.line(
                "s#2",
                SendTrace.completionPhase(insertedSentBubble = false),
                sinceStartMs = 480L,
                spanMs = null,
                "bubble" to "echoed",
                "flip" to "echo-reconcile",
            ),
        )
    }

    @Test
    fun negativeDurationsAreClampedToZero() {
        // Monotonic clock deltas should never be negative, but a defensive
        // clamp keeps a clock quirk from emitting a confusing "-3ms".
        assertEquals(
            "s#1 span-phase +0ms span=0ms",
            SendTrace.line("s#1", "span-phase", sinceStartMs = -5L, spanMs = -3L),
        )
    }

    @Test
    fun lineNeverContainsSensitiveMarkers() {
        // Guard: nothing the formatter emits for a typical send carries a
        // plaintext body, hex id, npub, or relay URL. Only the caller-supplied
        // privacy-safe context appears verbatim.
        val line =
            SendTrace.line(
                "s#9",
                "commit-lock-acquired",
                sinceStartMs = 3L,
                spanMs = 1L,
                "reply" to false,
            )
        assertTrue(line.startsWith("s#9 commit-lock-acquired"))
        listOf("npub", "wss://", "http", "@", "groupId", "0x").forEach { marker ->
            assertTrue("line must not contain '$marker': $line", !line.contains(marker))
        }
    }

    @Test
    fun sequenceWrapsInsteadOfGoingNegative() {
        // Extremely long sessions must not overflow the counter into a negative id.
        SendTrace.resetSequenceForTest(Long.MAX_VALUE)
        assertEquals("s#1", SendTrace.nextSequence())
    }
}
