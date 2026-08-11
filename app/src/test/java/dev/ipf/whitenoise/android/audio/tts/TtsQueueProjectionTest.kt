package dev.ipf.whitenoise.android.audio.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsQueueProjectionTest {
    @Test
    fun equivalentMessagesProduceEqualQueueProjections() {
        val message =
            TtsQueuedMessage(
                senderKey = "alice",
                senderDisplayName = "Alice",
                preview = "One.",
                chunks = listOf(TtsChunk(text = "One.", index = 0)),
                messageIdHex = "m1",
            )

        assertEquals(
            TtsQueueProjection.from(listOf(message)),
            TtsQueueProjection.from(listOf(message)),
        )
    }
}
