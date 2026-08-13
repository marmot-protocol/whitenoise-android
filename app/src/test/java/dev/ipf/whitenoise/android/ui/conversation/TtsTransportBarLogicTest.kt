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
    fun rateLabelsFormatWithTheActiveLocale() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.US)
            assertEquals(
                "0,75\u00d7",
                dev.ipf.whitenoise.android.ui.settings
                    .ttsRateLabel(0.75f, java.util.Locale.GERMANY),
            )
            assertEquals(
                "1\u00d7",
                dev.ipf.whitenoise.android.ui.settings
                    .ttsRateLabel(1.0f, java.util.Locale.GERMANY),
            )
            assertEquals(
                "2,5\u00d7",
                dev.ipf.whitenoise.android.ui.settings
                    .ttsRateLabel(2.5f, java.util.Locale.GERMANY),
            )
            assertEquals(
                "3\u00d7",
                dev.ipf.whitenoise.android.ui.settings
                    .ttsRateLabel(3.0f, java.util.Locale.GERMANY),
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

    @Test
    fun sentencePositionWithinTheMessageReadsEveryStateVariant() {
        val speaking = speakingTts(3, 8, 2, 5, "Preview", sentenceIndex = 2, sentenceCount = 4)
        assertEquals(2, ttsSentenceIndex(speaking))
        assertEquals(4, ttsSentenceCount(speaking))
        val paused = pausedTts(3, 8, 2, 5, "Preview", sentenceIndex = 1, sentenceCount = 4)
        assertEquals(1, ttsSentenceIndex(paused))
        assertEquals(4, ttsSentenceCount(paused))
        val error = errorTts(TtsError.Synthesis, 4, 8, 3, 5, "Preview", sentenceIndex = 3, sentenceCount = 6)
        assertEquals(3, ttsSentenceIndex(error))
        assertEquals(6, ttsSentenceCount(error))
        val idle = idleTts(8, 8, 4, 5, "Preview", sentenceIndex = 2, sentenceCount = 2)
        assertEquals(2, ttsSentenceIndex(idle))
        assertEquals(2, ttsSentenceCount(idle))
    }

    @Test
    fun navigationIsEnabledOnlyWhileSpeakingOrPaused() {
        assertEquals(true, ttsNavigationEnabled(speakingTts(0, 2)))
        assertEquals(true, ttsNavigationEnabled(pausedTts(0, 2)))
        assertEquals(false, ttsNavigationEnabled(errorTts(TtsError.Network, 0, 2)))
        assertEquals(false, ttsNavigationEnabled(idleTts(0, 0)))
    }

    @Test
    fun messageProgressFractionReadsEveryStateVariant() {
        val speaking =
            speakingTts(3, 8, 2, 5, "Preview", messageProgressFraction = 0.35f)
        assertEquals(0.35f, ttsMessageProgressFraction(speaking))
        val paused =
            pausedTts(3, 8, 2, 5, "Preview", messageProgressFraction = 0.35f)
        assertEquals(0.35f, ttsMessageProgressFraction(paused))
        assertEquals(
            0.5f,
            ttsMessageProgressFraction(
                errorTts(TtsError.Synthesis, 4, 8, 3, 5, "Preview", messageProgressFraction = 0.5f),
            ),
        )
        assertEquals(1f, ttsMessageProgressFraction(idleTts(8, 8, 4, 5, "Preview", messageProgressFraction = 1f)))
    }

    @Test
    fun messageProgressFractionIsClampedToTheUnitInterval() {
        assertEquals(1f, ttsMessageProgressFraction(speakingTts(0, 2, messageProgressFraction = 1.5f)))
        assertEquals(0f, ttsMessageProgressFraction(speakingTts(0, 2, messageProgressFraction = -0.2f)))
    }

    @Test
    fun progressAnimationRestartsOnlyWhenTheMessageChanges() {
        val firstUpdate =
            speakingTts(0, 2, 0, 3, messageProgressFraction = 0.2f, messageProgressGeneration = 1L)
        val sameMessage =
            speakingTts(0, 2, 0, 4, messageProgressFraction = 0.4f, messageProgressGeneration = 1L)
        val nextMessage =
            speakingTts(1, 2, 1, 4, messageProgressFraction = 0f, messageProgressGeneration = 1L)
        val editedMessage =
            speakingTts(
                0,
                2,
                0,
                3,
                messagePreview = "Edited",
                messageProgressFraction = 0f,
                messageProgressGeneration = 2L,
            )

        assertEquals(ttsProgressAnimationKey(firstUpdate), ttsProgressAnimationKey(sameMessage))
        assertEquals(false, ttsProgressAnimationKey(firstUpdate) == ttsProgressAnimationKey(nextMessage))
        assertEquals(
            false,
            ttsProgressAnimationKey(firstUpdate) == ttsProgressAnimationKey(editedMessage),
        )
    }

    @Test
    fun sameSlotSamePreviewReplacementChangesProgressAnimationKey() {
        val sharedPreview = "A".repeat(120)
        val before =
            speakingTts(
                0,
                2,
                0,
                3,
                messagePreview = sharedPreview,
                messageProgressFraction = 0.6f,
                messageProgressGeneration = 4L,
            )
        val after =
            speakingTts(
                0,
                2,
                0,
                3,
                messagePreview = sharedPreview,
                messageProgressFraction = 0f,
                messageProgressGeneration = 5L,
            )

        assertEquals(false, ttsProgressAnimationKey(before) == ttsProgressAnimationKey(after))
    }
}
