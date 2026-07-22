package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.whitenoise.android.audio.tts.TtsState
import org.junit.Assert.assertEquals
import org.junit.Test

class TtsTransportBarLogicTest {
    @Test
    fun ratePillCyclesPresetsStartingFromDefaultWhenFollowingTheSystem() {
        assertEquals(1.0f, nextTtsPresetRate(null))
        assertEquals(1.25f, nextTtsPresetRate(1.0f))
        assertEquals(0.5f, nextTtsPresetRate(2.0f))
    }

    @Test
    fun chunkPositionReadsEveryStateVariant() {
        assertEquals(3, ttsChunkIndex(TtsState.Speaking(chunkIndex = 3, chunkCount = 8)))
        assertEquals(8, ttsChunkCount(TtsState.Paused(chunkIndex = 3, chunkCount = 8)))
    }
}
