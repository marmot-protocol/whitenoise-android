package dev.ipf.whitenoise.android.audio.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsSpeakableEntryTest {
    @Test
    fun filtersBlankEntriesWithoutLosingMessageMetadata() {
        val entries =
            listOf(
                entry("alice", "Alice", "Hello."),
                entry("bob", "Bob", "   "),
                entry("alice", "Alice", "Still me."),
            )

        assertEquals(
            listOf(
                entry("alice", "Alice", "Hello."),
                entry("alice", "Alice", "Still me."),
            ),
            boundedSpeakableEntries(entries),
        )
    }

    @Test
    fun boundsTheBacklogAtTheMessageCeilingAfterRemovingBlanks() {
        val entries =
            buildList {
                add(TtsSpeakableEntry("blank", "Blank", "   "))
                addAll(
                    List(TTS_AUTO_READ_MAX_MESSAGES + 5) { index ->
                        TtsSpeakableEntry("$index", "Sender $index", "Message $index")
                    },
                )
            }

        val bounded = boundedSpeakableEntries(entries)

        assertEquals(TTS_AUTO_READ_MAX_MESSAGES, bounded.size)
        assertEquals("Message 0", bounded.first().text)
        assertEquals("Message ${TTS_AUTO_READ_MAX_MESSAGES - 1}", bounded.last().text)
    }

    private fun entry(
        key: String,
        display: String,
        text: String,
    ) = TtsSpeakableEntry(senderKey = key, senderDisplayName = display, text = text)
}
