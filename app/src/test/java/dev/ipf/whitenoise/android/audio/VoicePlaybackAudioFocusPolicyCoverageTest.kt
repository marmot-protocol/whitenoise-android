package dev.ipf.whitenoise.android.audio

import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VoicePlaybackAudioFocusPolicyCoverageTest {
    @Test
    fun transientFocusChangesDoNotUseTheUserPausePath() {
        val source = voicePlaybackSource().readText()
        val listener = source.functionBody("handleAudioFocusChange")

        assertTrue("permanent focus loss must pause normally", "AUDIOFOCUS_LOSS -> pause()" in listener)
        assertTrue(
            "transient focus loss must retain focus for automatic resume",
            "AUDIOFOCUS_LOSS_TRANSIENT -> pauseForTransientAudioFocusLoss()" in listener,
        )
        assertTrue(
            "duck requests must lower volume instead of pausing",
            "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> duckForTransientAudioFocusLoss()" in listener,
        )
        assertTrue("focus gain must restore interrupted playback", "AUDIOFOCUS_GAIN -> restoreAfterAudioFocusGain()" in listener)
        assertFalse("transient focus changes must not abandon the held request", "abandonFocus()" in listener)
    }

    @Test
    fun transientPauseDuckAndGainPathsPreserveTheirIntent() {
        val source = voicePlaybackSource().readText()
        val transientPause = source.functionBody("pauseForTransientAudioFocusLoss")
        val duck = source.functionBody("duckForTransientAudioFocusLoss")
        val gain = source.functionBody("restoreAfterAudioFocusGain")

        assertTrue("transient pause must remember to resume", "resumeOnAudioFocusGain = true" in transientPause)
        assertFalse("transient pause must retain audio focus", "abandonFocus()" in transientPause)
        assertTrue("duck must lower both channels", "setVolume(DUCK_VOLUME, DUCK_VOLUME)" in duck)
        assertTrue("gain must restore both channels", "setVolume(1f, 1f)" in gain)
        assertTrue("gain must restart only an interrupted clip", "if (!resumeOnAudioFocusGain) return" in gain)
    }

    private fun voicePlaybackSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/audio/VoicePlaybackController.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/audio/VoicePlaybackController.kt"),
        ).firstOrNull(File::exists) ?: error("Missing VoicePlaybackController.kt")
}
