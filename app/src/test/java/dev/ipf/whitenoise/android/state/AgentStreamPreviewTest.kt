package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelineReactionSummaryFfi
import dev.ipf.whitenoise.android.core.TimelineProjector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentStreamPreviewTest {
    @Test
    fun durableStreamDisplayPositions_sortsFinalAfterLinkedPromptOnReload() {
        val prompt = timelineRecord(id = "prompt", recordedAt = 101uL)
        val start =
            timelineRecord(
                id = "start",
                recordedAt = 98uL,
                kind = 1200uL,
                tags =
                    listOf(
                        MessageTagFfi(listOf("stream", "reply")),
                        MessageTagFfi(listOf("parent", "prompt")),
                    ),
            )
        val final =
            timelineRecord(
                id = "final",
                recordedAt = 99uL,
                tags =
                    listOf(
                        MessageTagFfi(listOf("stream", "reply")),
                        MessageTagFfi(listOf("stream-start", "start")),
                    ),
            )

        // Authoritative pages may list the skewed stream records anywhere.
        // Position derivation must not depend on page iteration order.
        val promptPosition = StreamFinalDisplayPosition(recordedAt = 101uL, timelineOrder = 8uL)
        listOf(
            listOf(final, start, prompt),
            listOf(prompt, final, start),
            listOf(start, prompt, final),
        ).forEach { page ->
            val positions = durableStreamDisplayPositions(page)
            val startPosition =
                anchoredStreamDisplayPosition(
                    position = positions.getValue("start"),
                    parentRecordedAt = promptPosition.recordedAt,
                    parentTimelineOrder = promptPosition.timelineOrder,
                )
            val finalPosition =
                anchoredStreamDisplayPosition(
                    position = positions.getValue("final"),
                    parentRecordedAt = startPosition.recordedAt,
                    parentTimelineOrder = startPosition.timelineOrder,
                )
            val sorted =
                listOf(
                    timelineMessage(prompt, promptPosition),
                    timelineMessage(start, startPosition),
                    timelineMessage(final, finalPosition),
                ).sortedWith(::compareTimelineMessages)

            assertEquals(listOf("prompt", "start", "final"), sorted.map { it.record.messageIdHex })
            assertEquals(
                StreamFinalDisplayPosition(
                    recordedAt = 98uL,
                    timelineOrder = 1uL,
                    afterMessageId = "prompt",
                ),
                positions["start"],
            )
            assertEquals(
                StreamFinalDisplayPosition(
                    recordedAt = 99uL,
                    timelineOrder = 1uL,
                    afterMessageId = "start",
                ),
                positions["final"],
            )
            assertEquals(9uL, startPosition.timelineOrder)
            assertEquals(10uL, finalPosition.timelineOrder)
        }
    }

    @Test
    fun durableStreamDisplayPositions_reanchorsAfterOptimisticPromptOverride() {
        val prompt = timelineRecord(id = "prompt", recordedAt = 101uL)
        val start =
            timelineRecord(
                id = "start",
                recordedAt = 105uL,
                kind = 1200uL,
                tags =
                    listOf(
                        MessageTagFfi(listOf("stream", "reply")),
                        MessageTagFfi(listOf("parent", "prompt")),
                    ),
            )
        val final =
            timelineRecord(
                id = "final",
                recordedAt = 104uL,
                tags =
                    listOf(
                        MessageTagFfi(listOf("stream", "reply")),
                        MessageTagFfi(listOf("stream-start", "start")),
                    ),
            )

        val positions = durableStreamDisplayPositions(listOf(final, prompt, start))
        val promptPosition = StreamFinalDisplayPosition(recordedAt = 110uL, timelineOrder = 8uL)
        val startPosition =
            positions["start"]
                ?.let {
                    resolvedDurableStreamDisplayPosition(
                        candidate = it,
                        parentRecordedAt = promptPosition.recordedAt,
                        parentTimelineOrder = promptPosition.timelineOrder,
                    )
                }
                ?: StreamFinalDisplayPosition(recordedAt = start.timelineAt, timelineOrder = 0uL)
        val finalPosition =
            positions["final"]
                ?.let {
                    resolvedDurableStreamDisplayPosition(
                        candidate = it,
                        parentRecordedAt = startPosition.recordedAt,
                        parentTimelineOrder = startPosition.timelineOrder,
                    )
                }
                ?: StreamFinalDisplayPosition(recordedAt = final.timelineAt, timelineOrder = 0uL)
        val sorted =
            listOf(
                timelineMessage(prompt, promptPosition),
                timelineMessage(start, startPosition),
                timelineMessage(final, finalPosition),
            ).sortedWith(::compareTimelineMessages)

        assertEquals(listOf("prompt", "start", "final"), sorted.map { it.record.messageIdHex })
        assertEquals(
            StreamFinalDisplayPosition(recordedAt = 110uL, timelineOrder = 9uL, afterMessageId = "prompt"),
            startPosition,
        )
        assertEquals(
            StreamFinalDisplayPosition(recordedAt = 110uL, timelineOrder = 10uL, afterMessageId = "start"),
            finalPosition,
        )
    }

    @Test
    fun durableStreamDisplayPositions_keepsFinalAfterNaturallyLaterStart() {
        val prompt = timelineRecord(id = "prompt", recordedAt = 101uL)
        val start =
            timelineRecord(
                id = "start",
                recordedAt = 105uL,
                kind = 1200uL,
                tags =
                    listOf(
                        MessageTagFfi(listOf("stream", "reply")),
                        MessageTagFfi(listOf("parent", "prompt")),
                    ),
            )
        val final =
            timelineRecord(
                id = "final",
                recordedAt = 99uL,
                tags =
                    listOf(
                        MessageTagFfi(listOf("stream", "reply")),
                        MessageTagFfi(listOf("stream-start", "start")),
                    ),
            )

        val positions = durableStreamDisplayPositions(listOf(final, prompt, start))
        val finalPosition =
            anchoredStreamDisplayPosition(
                position = positions.getValue("final"),
                parentRecordedAt = start.timelineAt,
                parentTimelineOrder = 0uL,
            )
        val sorted =
            listOf(
                timelineMessage(prompt),
                timelineMessage(start),
                timelineMessage(final, finalPosition),
            ).sortedWith(::compareTimelineMessages)

        assertTrue(positions.getValue("start").recordedAt > prompt.timelineAt)
        assertEquals("prompt", positions.getValue("start").afterMessageId)
        assertEquals("start", positions.getValue("final").afterMessageId)
        assertEquals(listOf("prompt", "start", "final"), sorted.map { it.record.messageIdHex })
    }

    @Test
    fun durableStreamDisplayPositions_doesNotInferPromptWithoutParentTag() {
        val start =
            timelineRecord(
                id = "start",
                recordedAt = 98uL,
                kind = 1200uL,
                tags = listOf(MessageTagFfi(listOf("stream", "reply"))),
            )
        val final =
            timelineRecord(
                id = "final",
                recordedAt = 99uL,
                tags =
                    listOf(
                        MessageTagFfi(listOf("stream", "reply")),
                        MessageTagFfi(listOf("stream-start", "start")),
                    ),
            )

        assertTrue(durableStreamDisplayPositions(listOf(final, start)).isEmpty())
    }

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

    private fun timelineRecord(
        id: String,
        recordedAt: ULong,
        kind: ULong = 9uL,
        tags: List<MessageTagFfi> = emptyList(),
    ): TimelineMessageRecordFfi =
        TimelineMessageRecordFfi(
            messageIdHex = id,
            sourceMessageIdHex = null,
            direction = "received",
            groupIdHex = "group",
            sender = "agent",
            plaintext = "answer",
            contentTokens =
                MarkdownDocumentFfi(
                    truncated = false,
                    blocks = emptyList(),
                    blankLinesBefore = ByteArray(0),
                ),
            kind = kind,
            tags = tags,
            timelineAt = recordedAt,
            receivedAt = recordedAt,
            replyToMessageIdHex = null,
            replyPreview = null,
            mediaJson = null,
            media = emptyList(),
            agentTextStreamJson = null,
            groupSystem = null,
            reactions = TimelineReactionSummaryFfi(byEmoji = emptyList(), userReactions = emptyList()),
            deleted = false,
            deletedByMessageIdHex = null,
            invalidationStatus = null,
            sourceEpoch = null,
            retentionSeconds = null,
            retentionExpiresAt = null,
        )

    private fun timelineMessage(
        record: TimelineMessageRecordFfi,
        position: StreamFinalDisplayPosition? = null,
    ): TimelineMessage =
        TimelineMessage(
            id = "msg:${record.messageIdHex}",
            record =
                TimelineProjector
                    .toAppMessageRecord(record)
                    .copy(recordedAt = position?.recordedAt ?: record.timelineAt),
            status = MessageStatus.Received,
            projected = record,
            timelineOrder = position?.timelineOrder ?: 0uL,
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
            contentTokens =
                MarkdownDocumentFfi(
                    truncated = false,
                    blocks = emptyList(),
                    blankLinesBefore = ByteArray(0),
                ),
            kind = if (final) 9uL else 1200uL,
            tags =
                buildList {
                    add(MessageTagFfi(listOf("stream", streamId)))
                    if (final) add(MessageTagFfi(listOf("stream-start", "start")))
                },
            sourceEpoch = null,
            retentionSeconds = null,
            retentionExpiresAt = null,
            recordedAt = recordedAt,
            receivedAt = recordedAt,
        )
}
