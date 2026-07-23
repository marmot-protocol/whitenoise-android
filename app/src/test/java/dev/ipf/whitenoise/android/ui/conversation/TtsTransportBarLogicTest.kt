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
    fun rateLabelsFormatWithTheActiveLocale() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals(
                "0,75\u00d7",
                dev.ipf.whitenoise.android.ui.settings
                    .ttsRateLabel(0.75f),
            )
            assertEquals(
                "1\u00d7",
                dev.ipf.whitenoise.android.ui.settings
                    .ttsRateLabel(1.0f),
            )
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test
    fun chunkPositionReadsEveryStateVariant() {
        assertEquals(3, ttsChunkIndex(TtsState.Speaking(chunkIndex = 3, chunkCount = 8)))
        assertEquals(8, ttsChunkCount(TtsState.Paused(chunkIndex = 3, chunkCount = 8)))
    }
}
