package dev.ipf.whitenoise.android.audio.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TtsConversationDestinationTest {
    private val source = TtsConversationSource("account-a", "group-a", sessionId = 7L)
    private val passage = TtsPassage("message-a", sentenceIndex = 2, timelineAt = 42uL)

    @Test
    fun matchingSpeakingAndPausedStatesResolveTheConversationDestination() {
        val speaking = activeState(paused = false)
        val paused = activeState(paused = true)
        val expected = TtsConversationDestination("account-a", "group-a", 7L, passage)

        assertEquals(expected, ttsConversationDestination(source, speaking))
        assertEquals(expected, ttsConversationDestination(source, paused))
    }

    @Test
    fun terminalMismatchedAndUnaddressableStatesFailClosed() {
        assertNull(ttsConversationDestination(source, TtsState.Idle(sessionId = 7L, passage = passage)))
        assertNull(ttsConversationDestination(source, activeState(paused = false, sessionId = 8L)))
        assertNull(
            ttsConversationDestination(
                source,
                activeState(paused = false, passage = passage.copy(messageIdHex = "")),
            ),
        )
        assertNull(ttsConversationDestination(null, activeState(paused = false)))
    }

    private fun activeState(
        paused: Boolean,
        sessionId: Long = 7L,
        passage: TtsPassage = this.passage,
    ): TtsState =
        if (paused) {
            TtsState.Paused(
                sessionId = sessionId,
                chunkIndex = 0,
                chunkCount = 1,
                messageIndex = 0,
                messageCount = 1,
                sentenceIndexWithinMessage = passage.sentenceIndex,
                sentenceCountWithinMessage = 3,
                messagePreview = "Preview",
                passage = passage,
            )
        } else {
            TtsState.Speaking(
                sessionId = sessionId,
                chunkIndex = 0,
                chunkCount = 1,
                messageIndex = 0,
                messageCount = 1,
                sentenceIndexWithinMessage = passage.sentenceIndex,
                sentenceCountWithinMessage = 3,
                messagePreview = "Preview",
                passage = passage,
            )
        }
}
