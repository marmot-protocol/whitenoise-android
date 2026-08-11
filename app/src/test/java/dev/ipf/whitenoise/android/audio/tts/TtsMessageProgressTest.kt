package dev.ipf.whitenoise.android.audio.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TtsMessageProgressTest {
    @Test
    fun sentenceFallbackUsesTheActiveSentenceIndex() {
        assertEquals(0f, TtsMessageProgress.sentenceFallback(0, 4))
        assertEquals(0.5f, TtsMessageProgress.sentenceFallback(2, 4))
    }

    @Test
    fun rangeProgressMapsSubmittedPayloadOffsetsWithoutTheSenderPrefix() {
        val progress =
            TtsMessageProgress.rangeProgress(
                messageOffsetBeforeChunk = 10,
                rangeStart = 14,
                prefixLength = 4,
                messageTotalLength = 40,
            )

        assertEquals(0.5f, progress)
    }

    @Test
    fun rangeProgressRejectsOffsetsThatPointIntoTheSenderPrefix() {
        assertNull(
            TtsMessageProgress.rangeProgress(
                messageOffsetBeforeChunk = 0,
                rangeStart = 2,
                prefixLength = 4,
                messageTotalLength = 10,
            ),
        )
    }
}
