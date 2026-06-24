package dev.ipf.darkmatter.core

import dev.ipf.marmotkit.AgentStreamUpdateFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the live-agent-stream → streaming-debug-row formatting (#315, iOS
 * parity). The blocking adversarial finding on PR #699 was that the Status /
 * Progress / Record variants were dropped entirely; these assertions pin that
 * every live variant now produces a visible, non-empty debug row.
 */
class StreamDebugEventFormatterTest {
    @Test
    fun statusEventIsVisibleWithSeqAndStatus() {
        val event =
            StreamDebugEventFormatter.of(
                AgentStreamUpdateFfi.Status(seq = 7uL, status = "thinking"),
            )
        assertEquals("status", event.eventKind)
        assertEquals("seq=7 thinking", event.detail)
    }

    @Test
    fun progressEventIsVisibleWithSeqAndSummary() {
        val event =
            StreamDebugEventFormatter.of(
                AgentStreamUpdateFfi.Progress(seq = 12uL, text = "  running tool calls  "),
            )
        assertEquals("progress", event.eventKind)
        assertEquals("seq=12 running tool calls", event.detail)
    }

    @Test
    fun recordEventIsVisibleWithRecordTypeAndSummary() {
        val event =
            StreamDebugEventFormatter.of(
                AgentStreamUpdateFfi.Record(seq = 3uL, recordType = 2.toUByte(), text = "checkpoint"),
            )
        assertEquals("record(2)", event.eventKind)
        assertEquals("checkpoint", event.detail)
    }

    @Test
    fun chunkEventIsVisible() {
        val event =
            StreamDebugEventFormatter.of(
                AgentStreamUpdateFfi.Chunk(seq = 1uL, text = "hello"),
            )
        assertEquals("chunk", event.eventKind)
        assertEquals("hello", event.detail)
    }

    @Test
    fun finishedEventReportsChunkAndLengthMetadata() {
        val event =
            StreamDebugEventFormatter.of(
                AgentStreamUpdateFfi.Finished(
                    text = "final answer",
                    transcriptHashHex = "ab".repeat(32),
                    chunkCount = 9uL,
                ),
            )
        assertEquals("finished", event.eventKind)
        assertEquals("chunks=9 textLen=12B hashLen=64", event.detail)
    }

    @Test
    fun failedEventSurfacesMessage() {
        val event =
            StreamDebugEventFormatter.of(
                AgentStreamUpdateFfi.Failed(message = "stream reset by peer"),
            )
        assertEquals("failed", event.eventKind)
        assertEquals("stream reset by peer", event.detail)
    }

    @Test
    fun blankPayloadSummarizesToEmptyPlaceholder() {
        assertEquals("(empty)", StreamDebugEventFormatter.textSummary("   \n\t "))
    }

    @Test
    fun longPayloadIsTruncatedWithLengthTag() {
        val long = "x".repeat(200)
        val summary = StreamDebugEventFormatter.textSummary(long)
        assertTrue("truncated prefix kept", summary.startsWith("x".repeat(StreamDebugEventFormatter.SUMMARY_MAX)))
        assertTrue("reports original length", summary.endsWith("(200 chars)"))
        assertTrue("uses ellipsis", summary.contains("…"))
    }
}
