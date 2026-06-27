package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MessageTagFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageEditsTest {
    private val empty = MarkdownDocumentFfi(truncated = false, blocks = emptyList())

    private fun chat(
        id: String,
        sender: String,
        text: String,
        at: ULong,
    ) = AppMessageRecordFfi(
        messageIdHex = id,
        direction = "received",
        groupIdHex = "g",
        sender = sender,
        plaintext = text,
        contentTokens = empty,
        kind = 9uL,
        tags = emptyList(),
        recordedAt = at,
        receivedAt = at,
    )

    private fun edit(
        id: String,
        sender: String,
        target: String,
        text: String,
        at: ULong,
    ) = AppMessageRecordFfi(
        messageIdHex = id,
        direction = "received",
        groupIdHex = "g",
        sender = sender,
        plaintext = text,
        contentTokens = empty,
        kind = 1009uL,
        tags = listOf(MessageTagFfi(values = listOf("e", target))),
        recordedAt = at,
        receivedAt = at,
    )

    @Test
    fun emptyTimelineProducesEmptyMap() {
        assertTrue(aggregateEdits(emptyList()).isEmpty())
    }

    @Test
    fun singleEditPicksUpLatestText() {
        val records =
            listOf(
                chat(id = "orig", sender = "alice", text = "hello", at = 1uL),
                edit(id = "e1", sender = "alice", target = "orig", text = "hello world", at = 2uL),
            )
        val state = aggregateEdits(records)["orig"]
        assertEquals("hello world", state?.latestText)
        assertEquals(1, state?.count)
        assertEquals(2uL, state?.versions?.single()?.recordedAt)
    }

    @Test
    fun multipleEditsOrderChronologically() {
        val records =
            listOf(
                chat(id = "orig", sender = "alice", text = "v0", at = 1uL),
                edit(id = "e2", sender = "alice", target = "orig", text = "v2", at = 30uL),
                edit(id = "e1", sender = "alice", target = "orig", text = "v1", at = 20uL),
            )
        val versions = aggregateEdits(records)["orig"]?.versions.orEmpty()
        assertEquals(listOf("v1", "v2"), versions.map { it.text })
        assertEquals("v2", aggregateEdits(records)["orig"]?.latestText)
    }

    @Test
    fun equalTimestampEditsUseMessageIdTieBreak() {
        val records =
            listOf(
                chat(id = "orig", sender = "alice", text = "v0", at = 1uL),
                edit(id = "edit-b", sender = "alice", target = "orig", text = "b", at = 20uL),
                edit(id = "edit-a", sender = "alice", target = "orig", text = "a", at = 20uL),
            )
        val state = aggregateEdits(records)["orig"]

        assertEquals(listOf("a", "b"), state?.versions?.map { it.text })
        assertEquals("b", state?.latestText)
    }

    @Test
    fun editsBySomeoneElseAreSilentlyDropped() {
        val records =
            listOf(
                chat(id = "orig", sender = "alice", text = "alice writes", at = 1uL),
                edit(id = "e1", sender = "bob", target = "orig", text = "bob tries", at = 2uL),
            )
        assertNull(aggregateEdits(records)["orig"])
    }

    @Test
    fun authorshipMatchIsCaseInsensitiveOnHexPubkey() {
        val records =
            listOf(
                chat(id = "orig", sender = "ABCDEF", text = "x", at = 1uL),
                edit(id = "e1", sender = "abcdef", target = "orig", text = "y", at = 2uL),
            )
        assertEquals("y", aggregateEdits(records)["orig"]?.latestText)
    }

    @Test
    fun blankReplacementContentIsDropped() {
        val records =
            listOf(
                chat(id = "orig", sender = "alice", text = "kept", at = 1uL),
                edit(id = "e1", sender = "alice", target = "orig", text = "  ", at = 2uL),
            )
        assertNull(aggregateEdits(records)["orig"])
    }

    @Test
    fun editsWhoseTargetIsNotInTheTimelineAreSkipped() {
        val records = listOf(edit(id = "e1", sender = "alice", target = "missing", text = "x", at = 1uL))
        assertTrue(aggregateEdits(records).isEmpty())
    }

    @Test
    fun multipleTargetsAggregateIndependently() {
        val records =
            listOf(
                chat(id = "a", sender = "alice", text = "a0", at = 1uL),
                chat(id = "b", sender = "bob", text = "b0", at = 2uL),
                edit(id = "e1", sender = "alice", target = "a", text = "a1", at = 3uL),
                edit(id = "e2", sender = "bob", target = "b", text = "b1", at = 4uL),
            )
        val all = aggregateEdits(records)
        assertEquals("a1", all["a"]?.latestText)
        assertEquals("b1", all["b"]?.latestText)
        assertEquals(2, all.size)
    }
}
