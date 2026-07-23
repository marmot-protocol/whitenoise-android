package dev.ipf.whitenoise.android.audio.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsAutoReadScriptTest {
    @Test
    fun announcesSendersOnlyWhenTheSpeakerChanges() {
        val script =
            ttsAutoReadScript(
                listOf(
                    entry("alice", "Alice", "First."),
                    entry("ALICE", "Alice", "Second."),
                    entry("bob", "Bob", "Third."),
                ),
            )

        assertEquals("Alice: First.\nSecond.\nBob: Third.", script)
    }

    @Test
    fun skipsBlankTextsWithoutBreakingSpeakerRuns() {
        val script =
            ttsAutoReadScript(
                listOf(
                    entry("alice", "Alice", "Hello."),
                    entry("bob", "Bob", "   "),
                    entry("alice", "Alice", "Still me."),
                ),
            )

        // Bob's message never gets spoken, so it must not break Alice's run:
        // nothing was heard between her two lines, and re-announcing her
        // would imply a turn that never happened.
        assertEquals("Alice: Hello.\nStill me.", script)
    }

    @Test
    fun boundsTheBacklogAtTheCeiling() {
        val entries = (1..80).map { entry("alice", "Alice", "Message $it.") }

        val script = ttsAutoReadScript(entries)

        assertEquals(TTS_AUTO_READ_MAX_MESSAGES, script.split("\n").size)
    }

    private fun entry(
        key: String,
        display: String,
        text: String,
    ) = TtsSpeakableEntry(senderKey = key, senderDisplayName = display, text = text)
}
