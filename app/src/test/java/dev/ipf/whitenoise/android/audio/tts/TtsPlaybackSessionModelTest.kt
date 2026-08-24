package dev.ipf.whitenoise.android.audio.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsPlaybackSessionModelTest {
    @Test
    fun speakingIsAnActivePlayingSession() {
        assertEquals(
            TtsPlaybackSessionModel(isActive = true, isPlaying = true, navigationEnabled = true),
            TtsPlaybackSessionModel.from(speakingTts(chunkIndex = 0, chunkCount = 2)),
        )
    }

    @Test
    fun pausedKeepsTheSessionAliveWithoutPlaying() {
        assertEquals(
            TtsPlaybackSessionModel(isActive = true, isPlaying = false, navigationEnabled = true),
            TtsPlaybackSessionModel.from(pausedTts(chunkIndex = 1, chunkCount = 2)),
        )
    }

    @Test
    fun idleAndErrorAreTerminalForTheService() {
        assertEquals(
            TtsPlaybackSessionModel(isActive = false, isPlaying = false, navigationEnabled = false),
            TtsPlaybackSessionModel.from(TtsState.Idle()),
        )
        assertEquals(
            TtsPlaybackSessionModel(isActive = false, isPlaying = false, navigationEnabled = false),
            TtsPlaybackSessionModel.from(
                TtsState.Error(
                    error = TtsError.Synthesis,
                    chunkIndex = 0,
                    chunkCount = 1,
                    messageIndex = 0,
                    messageCount = 1,
                    sentenceIndexWithinMessage = 0,
                    sentenceCountWithinMessage = 1,
                    messagePreview = "",
                ),
            ),
        )
    }
}
