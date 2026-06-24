package dev.ipf.darkmatter.core

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
    fun streamStartIsStreamSignalingWithKindAndStreamId() {
        val style =
            MessageDebugClassifier.debugStyle(
                message(
                    id = "ss",
                    plaintext = "",
                    kind = 1200uL,
                    tags = listOf(MessageProjector.streamTag("abc123")),
                ),
            )

        assertEquals(MessageDebugCategory.StreamSignaling, style.category)
        assertFalse(style.isUserVisibleBubble)
        assertTrue(style.kindLabel.contains("1200"))
        assertTrue(style.detailText.contains("abc123"))
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
    fun tagsSummaryIsNoneWhenEmptyAndMultiLineOtherwise() {
        val empty = MessageDebugClassifier.debugStyle(message(id = "m", plaintext = "hi", tags = emptyList()))
        val tagged =
            MessageDebugClassifier.debugStyle(
                message(
                    id = "m",
                    plaintext = "",
                    kind = 1200uL,
                    tags =
                        listOf(
                            MessageProjector.streamTag("abc123"),
                            MessageTagFfi(listOf("stream-start", "start")),
                        ),
                ),
            )

        assertEquals("tags: (none)", empty.tagsSummary)
        assertEquals("tags:\nstream abc123\nstream-start start", tagged.tagsSummary)
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

    private fun message(
        id: String,
        direction: String = "received",
        sender: String = "alice",
        plaintext: String = "hello",
        kind: ULong = 9uL,
        tags: List<MessageTagFfi> = emptyList(),
        at: UInt = 1u,
    ) = AppMessageRecordFfi(
        messageIdHex = id,
        direction = direction,
        groupIdHex = "group",
        sender = sender,
        plaintext = plaintext,
        contentTokens = MarkdownDocumentFfi(blocks = emptyList()),
        kind = kind,
        tags = tags,
        recordedAt = at.toULong(),
        receivedAt = at.toULong(),
    )
}
