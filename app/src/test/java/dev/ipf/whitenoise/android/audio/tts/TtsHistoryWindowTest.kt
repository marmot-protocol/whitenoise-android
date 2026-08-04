package dev.ipf.whitenoise.android.audio.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsHistoryWindowTest {
    @Test
    fun olderMergePrependsInCanonicalOrder() {
        val merged =
            TtsHistoryWindow.merge(
                existing = window("c", "d"),
                incoming = window("a", "b"),
                direction = TtsHistoryDirection.Older,
                targetMessageIdHex = "b",
            )

        assertEquals(listOf("a", "b", "c", "d"), merged.map { it.messageIdHex })
    }

    @Test
    fun newerMergeAppendsInCanonicalOrder() {
        val merged =
            TtsHistoryWindow.merge(
                existing = window("a", "b"),
                incoming = window("c", "d"),
                direction = TtsHistoryDirection.Newer,
                targetMessageIdHex = "c",
            )

        assertEquals(listOf("a", "b", "c", "d"), merged.map { it.messageIdHex })
    }

    @Test
    fun incomingDuplicatesOfQueuedMessagesAreDropped() {
        val merged =
            TtsHistoryWindow.merge(
                existing = window("b", "c"),
                incoming = window("a", "b", "c"),
                direction = TtsHistoryDirection.Older,
                targetMessageIdHex = "a",
            )

        assertEquals(listOf("a", "b", "c"), merged.map { it.messageIdHex })
    }

    @Test
    fun duplicatesWithinOnePageAndBlankIdsAreDropped() {
        val merged =
            TtsHistoryWindow.merge(
                existing = window("c"),
                incoming = window("a", "a", "") + window("b"),
                direction = TtsHistoryDirection.Older,
                targetMessageIdHex = "b",
            )

        assertEquals(listOf("a", "b", "c"), merged.map { it.messageIdHex })
    }

    @Test
    fun olderOverflowEvictsTheNewestTail() {
        val merged =
            TtsHistoryWindow.merge(
                existing = window("c", "d", "e", "f"),
                incoming = window("a", "b"),
                direction = TtsHistoryDirection.Older,
                targetMessageIdHex = "b",
                maxMessages = 4,
            )

        assertEquals(listOf("a", "b", "c", "d"), merged.map { it.messageIdHex })
    }

    @Test
    fun newerOverflowEvictsTheOldestHead() {
        val merged =
            TtsHistoryWindow.merge(
                existing = window("a", "b", "c", "d"),
                incoming = window("e", "f"),
                direction = TtsHistoryDirection.Newer,
                targetMessageIdHex = "e",
                maxMessages = 4,
            )

        assertEquals(listOf("c", "d", "e", "f"), merged.map { it.messageIdHex })
    }

    @Test
    fun evictionNeverDropsTheNavigationTarget() {
        // A cap smaller than the incoming page must widen, not cut the target.
        val olderMerged =
            TtsHistoryWindow.merge(
                existing = window("x", "y"),
                incoming = window("a", "b", "c", "d"),
                direction = TtsHistoryDirection.Older,
                targetMessageIdHex = "d",
                maxMessages = 2,
            )
        assertEquals(listOf("a", "b", "c", "d"), olderMerged.map { it.messageIdHex })

        val newerMerged =
            TtsHistoryWindow.merge(
                existing = window("x", "y"),
                incoming = window("a", "b", "c", "d"),
                direction = TtsHistoryDirection.Newer,
                targetMessageIdHex = "a",
                maxMessages = 2,
            )
        assertEquals(listOf("a", "b", "c", "d"), newerMerged.map { it.messageIdHex })
    }

    @Test
    fun windowWithinCapIsNeverTrimmed() {
        val merged =
            TtsHistoryWindow.merge(
                existing = window("b", "c"),
                incoming = window("a"),
                direction = TtsHistoryDirection.Older,
                targetMessageIdHex = "a",
                maxMessages = 3,
            )

        assertEquals(listOf("a", "b", "c"), merged.map { it.messageIdHex })
    }

    private fun window(vararg ids: String): List<TtsQueuedMessage> =
        ids.map { id ->
            TtsQueuedMessage(
                senderKey = "sender-$id",
                senderDisplayName = "Sender $id",
                preview = "Message $id.",
                chunks = listOf(TtsChunk(text = "Message $id.", index = 0, sentenceIndex = 0)),
                messageIdHex = id,
            )
        }
}
