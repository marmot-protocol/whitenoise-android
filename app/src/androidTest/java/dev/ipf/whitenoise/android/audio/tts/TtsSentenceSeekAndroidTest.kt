package dev.ipf.whitenoise.android.audio.tts

import android.speech.tts.TextToSpeech
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Physical-runtime guard for the queue semantics used by message double tap. */
@RunWith(AndroidJUnit4::class)
class TtsSentenceSeekAndroidTest {
    @Test
    fun seekKeepsSessionAndRequeuesOnlyFromTappedSentence() {
        val submitted = mutableListOf<TtsChunk>()
        val queue =
            TtsPlaybackQueue(
                stopEngine = {},
                enqueue = { chunk, _ ->
                    submitted += chunk
                    TextToSpeech.SUCCESS
                },
            )
        queue.start(
            listOf(
                TtsQueuedMessage(
                    senderKey = "alice",
                    senderDisplayName = "Alice",
                    preview = "First. Second. Third.",
                    chunks =
                        listOf(
                            TtsChunk("First.", 0, 0),
                            TtsChunk("Second.", 1, 1),
                            TtsChunk("Third.", 2, 2),
                        ),
                    messageIdHex = "message",
                ),
            ),
        )
        val sessionId = queue.state.value.sessionId
        val submittedBeforeSeek = submitted.size

        assertEquals(TtsSeekResult.Repositioned, queue.seekTo("message", 2))

        assertEquals(sessionId, queue.state.value.sessionId)
        assertEquals(2, queue.state.value.sentenceIndexWithinMessage)
        assertEquals(submittedBeforeSeek + 1, submitted.size)
        assertEquals("Third.", submitted.last().text)
        assertFalse(submitted.last().text.startsWith("Alice:"))
    }
}
