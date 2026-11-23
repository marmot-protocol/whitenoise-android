package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MessageTagFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageDebugStyleTest {
    @Test
    fun chatRecordIsUserVisibleBubble() {
        val style = MessageDebugClassifier.debugStyle(message(id = "m1", plaintext = "hi", kind = 9uL))

        assertEquals(MessageDebugCategory.UserVisible, style.category)
        assertTrue(style.isUserVisibleBubble)
    }

    @Test
    fun reactionIsControl() {
        val style =
            MessageDebugClassifier.debugStyle(
                reaction(id = "r1", sender = "alice", target = "m1", emoji = "👍", at = 1u),
            )

        assertEquals(MessageDebugCategory.Control, style.category)
        assertFalse(style.isUserVisibleBubble)
        assertTrue(style.kindLabel.contains("7"))
    }

    @Test
    fun groupSystemIsGroupSystem() {
        val style = MessageDebugClassifier.debugStyle(message(id = "gs", plaintext = "{}", kind = 1210uL))

        assertEquals(MessageDebugCategory.GroupSystem, style.category)
        assertFalse(style.isUserVisibleBubble)
        assertTrue(style.kindLabel.contains("1210"))
    }

    @Test
    fun groupSystemRowStaysSummaryEvenWithDebugOn() {
        // #857: a kind-1210 state-change row must render its one-line summary
        // (GroupSystem) regardless of the streaming-debug toggle — never the raw
        // MLS dump as the default. The dump is reachable per-row behind a tap.
        val record = message(id = "gs", plaintext = "{}", kind = 1210uL)

        assertEquals(TimelineRowKind.GroupSystem, timelineRowKind(record, streamingDebugEnabled = false))
        assertEquals(TimelineRowKind.GroupSystem, timelineRowKind(record, streamingDebugEnabled = true))
    }

    @Test
    fun pollRemainsInteractiveBubbleWithStreamingDebugEnabled() {
        val record = message(id = "poll", plaintext = "{\"private-envelope\":true}", kind = 1068uL)
        assertEquals(TimelineRowKind.Bubble, timelineRowKind(record, streamingDebugEnabled = false))
        assertEquals(TimelineRowKind.Bubble, timelineRowKind(record, streamingDebugEnabled = true))
    }

    @Test
    fun reactionBecomesDebugRowOnlyWhenDebugOn() {
        val record = reaction(id = "r1", sender = "alice", target = "m1", emoji = "👍", at = 1u)

        assertEquals(TimelineRowKind.Bubble, timelineRowKind(record, streamingDebugEnabled = false))
        assertEquals(TimelineRowKind.DebugRow, timelineRowKind(record, streamingDebugEnabled = true))
    }

    @Test
    fun agentOperationUsesDedicatedRowRegardlessOfDebugMode() {
        val record =
            message(
                id = "tool",
                plaintext = """{"event_type":"tool_call","status":"started","text":"Searching"}""",
                kind = 1202uL,
            )

        assertEquals(MessageDebugCategory.AgentChrome, MessageDebugClassifier.debugStyle(record).category)
        assertEquals(TimelineRowKind.AgentOperation, timelineRowKind(record, streamingDebugEnabled = false))
        assertEquals(TimelineRowKind.AgentOperation, timelineRowKind(record, streamingDebugEnabled = true))
    }

    @Test
    fun chatRowIsBubbleRegardlessOfDebug() {
        val record = message(id = "m1", plaintext = "hi", kind = 9uL)

        assertEquals(TimelineRowKind.Bubble, timelineRowKind(record, streamingDebugEnabled = false))
        assertEquals(TimelineRowKind.Bubble, timelineRowKind(record, streamingDebugEnabled = true))
    }

    /** The tag summary reads "(none)" for an untagged record and lists one tag per line otherwise. */
    @Test
    fun tagsSummaryIsNoneWhenEmptyAndMultiLineOtherwise() {
        val empty = MessageDebugClassifier.debugStyle(message(id = "m", plaintext = "hi", tags = emptyList()))
        val tagged =
            MessageDebugClassifier.debugStyle(
                message(
                    id = "m",
                    plaintext = "",
                    tags =
                        listOf(
                            MessageTagFfi(listOf("e", "abc123")),
                            MessageTagFfi(listOf("q", "start")),
                        ),
                ),
            )

        assertEquals("tags: (none)", empty.tagsSummary)
        assertEquals("tags:\ne abc123\nq start", tagged.tagsSummary)
    }

    private fun reaction(
        id: String,
        sender: String,
        target: String,
        emoji: String,
        at: UInt,
    ) = message(
        id = id,
        sender = sender,
        plaintext = emoji,
        kind = 7uL,
        tags = listOf(MessageProjector.eventTag(target)),
        at = at,
    )

    /** Builds a message record with the given id, direction, sender, body, kind, tags and timestamp. */
    private fun message(
        id: String,
        direction: String = "received",
        sender: String = "alice",
        plaintext: String = "hello",
        kind: ULong = 9uL,
        tags: List<MessageTagFfi> = emptyList(),
        at: UInt = 1u,
    ) = AppMessageRecordFfi(
        invalidated = false,
        messageIdHex = id,
        direction = direction,
        groupIdHex = "group",
        sender = sender,
        plaintext = plaintext,
        contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList(), blankLinesBefore = ByteArray(0)),
        kind = kind,
        tags = tags,
        sourceEpoch = null,
        retentionSeconds = null,
        retentionExpiresAt = null,
        recordedAt = at.toULong(),
        receivedAt = at.toULong(),
        sticker = null,
    )
}
