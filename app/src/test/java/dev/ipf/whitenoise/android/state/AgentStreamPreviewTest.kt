package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MessageTagFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentStreamPreviewTest {
    @Test
    fun streamFinalDisplayPosition_preservesDisplayedTimestampWhenFinalIsEarlier() {
        val preview = timelineMessage(id = "stream:reply", recordedAt = 101uL, timelineOrder = 8uL)
        val final = appMessage(id = "final", recordedAt = 99uL, streamId = "reply", final = true)

        assertEquals(
            StreamFinalDisplayPosition(recordedAt = 101uL, timelineOrder = 8uL),
            streamFinalDisplayPosition(final, preview),
        )
    }

    @Test
    fun streamFinalDisplayPosition_preservesDisplayedTimestampWhenFinalIsLater() {
        val preview = timelineMessage(id = "stream:reply", recordedAt = 101uL, timelineOrder = 8uL)
        val final = appMessage(id = "final", recordedAt = 103uL, streamId = "reply", final = true)

        assertEquals(
            StreamFinalDisplayPosition(recordedAt = 101uL, timelineOrder = 8uL),
            streamFinalDisplayPosition(final, preview),
        )
    }

    @Test
    fun streamFinalDisplayPosition_ignoresMismatchedPreview() {
        val preview = timelineMessage(id = "stream:other", recordedAt = 101uL, timelineOrder = 8uL)
        val final = appMessage(id = "final", recordedAt = 99uL, streamId = "reply", final = true)

        assertNull(streamFinalDisplayPosition(final, preview))
    }

    @Test
    fun appendCappedAgentStreamPreview_keepsShortTranscript() {
        val text = StringBuilder()

        appendCappedAgentStreamPreview(text, "hello ", maxChars = 16)
        appendCappedAgentStreamPreview(text, "world", maxChars = 16)

        assertEquals("hello world", text.toString())
    }

    @Test
    fun appendCappedAgentStreamPreview_keepsTailWhenTranscriptExceedsCap() {
        val text = StringBuilder("abcdef")

        appendCappedAgentStreamPreview(text, "ghijkl", maxChars = 8)

        assertEquals("efghijkl", text.toString())
    }

    @Test
    fun appendCappedAgentStreamPreview_keepsTailOfOversizedSingleChunk() {
        val text = StringBuilder("old")

        appendCappedAgentStreamPreview(text, "0123456789", maxChars = 4)

        assertEquals("6789", text.toString())
    }

    private fun timelineMessage(
        id: String,
        recordedAt: ULong,
        timelineOrder: ULong,
    ): TimelineMessage =
        TimelineMessage(
            id = id,
            record = appMessage(id = id, recordedAt = recordedAt, streamId = id.removePrefix("stream:"), final = false),
            status = MessageStatus.Streaming,
            timelineOrder = timelineOrder,
        )

    private fun appMessage(
        id: String,
        recordedAt: ULong,
        streamId: String,
        final: Boolean,
    ): AppMessageRecordFfi =
        AppMessageRecordFfi(
            messageIdHex = id,
            direction = "received",
            groupIdHex = "group",
            sender = "agent",
            plaintext = "answer",
            contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList()),
            kind = if (final) 9uL else 1200uL,
            tags =
                buildList {
                    add(MessageTagFfi(listOf("stream", streamId)))
                    if (final) add(MessageTagFfi(listOf("stream-start", "start")))
                },
            recordedAt = recordedAt,
            receivedAt = recordedAt,
        )
}
