package dev.ipf.whitenoise.android.audio.tts

import dev.ipf.whitenoise.android.appModuleSource
import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every read-aloud focus interruption silences preparation as well as speech (#3708). */
class TtsFocusOwnershipCoverageTest {
    private val controller = appModuleSource("src/main/java/dev/ipf/whitenoise/android/audio/tts/TtsController.kt")

    /** Transient loss and owner surrender both use the preparation-aware silencer, never a speaking-only pause. */
    @Test
    fun focusCallbacksSilencePreparation() {
        // acquireAudioFocus has an expression body, so slice it up to the next declaration.
        val acquire =
            controller
                .substringAfter("private fun acquireAudioFocus(")
                .substringBefore("\n    /**")
        assertTrue("transient loss silences preparation", "onFocusLoss = ::pauseForFocusLoss" in acquire)
        assertTrue("owner surrender silences preparation", "onOwnerSurrender = ::pauseForFocusLoss" in acquire)
        assertFalse("no focus callback uses the speaking-only pause", "::pause," in acquire)
    }

    /** Focus loss and a headphone disconnect share one silencer, which also stops a loading seek from resuming. */
    @Test
    fun focusLossAndNoisyRouteShareTheSilencer() {
        assertTrue("private fun pauseForFocusLoss() = silencePlayback()" in controller)
        assertTrue("private fun pauseForNoisyRoute() = silencePlayback()" in controller)
        val silence = controller.functionBody("silencePlayback")
        assertTrue("a silenced seek cannot resume", "playbackSilences.advance()" in silence)
        assertTrue("a first start still preparing is cancelled", "stop()" in silence)
        assertTrue("a parked session pauses", "queue.pauseParked()" in silence)
    }
}
