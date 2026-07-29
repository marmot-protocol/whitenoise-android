package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.whitenoise.android.audio.tts.TtsError
import dev.ipf.whitenoise.android.audio.tts.errorTts
import dev.ipf.whitenoise.android.audio.tts.idleTts
import dev.ipf.whitenoise.android.audio.tts.pausedTts
import dev.ipf.whitenoise.android.audio.tts.speakingTts
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
    fun messagePositionReadsEveryStateVariant() {
        assertEquals(2, ttsMessageIndex(speakingTts(3, 8, 2, 5, "Preview")))
        assertEquals(5, ttsMessageCount(speakingTts(3, 8, 2, 5, "Preview")))
        assertEquals(2, ttsMessageIndex(pausedTts(3, 8, 2, 5, "Preview")))
        assertEquals(5, ttsMessageCount(pausedTts(3, 8, 2, 5, "Preview")))
        assertEquals(3, ttsMessageIndex(errorTts(TtsError.Synthesis, 4, 8, 3, 5, "Preview")))
        assertEquals(5, ttsMessageCount(errorTts(TtsError.Synthesis, 4, 8, 3, 5, "Preview")))
        assertEquals(4, ttsMessageIndex(idleTts(8, 8, 4, 5, "Preview")))
        assertEquals(5, ttsMessageCount(idleTts(8, 8, 4, 5, "Preview")))
    }
}
