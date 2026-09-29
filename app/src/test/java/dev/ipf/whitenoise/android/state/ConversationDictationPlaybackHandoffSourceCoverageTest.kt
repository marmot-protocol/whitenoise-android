package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConversationDictationPlaybackHandoffSourceCoverageTest {
    /** Verifies AppState wires paired capture hooks through the app and external-media handoff. */
    @Test
    fun appStateUsesPairedPauseAndResumeCallbacksForDictationCapture() {
        val appState = source("state/AppState.kt")
        assertTrue(appState.contains("onBeforeRecognition = conversationDictationMediaHandoff::beforeRecognition"))
        assertTrue(appState.contains("onAfterAudioCapture = conversationDictationMediaHandoff::afterAudioCapture"))

        val source = source("state/ConversationDictationPlaybackHandoff.kt")
        val capture =
            source.substring(
                source.indexOf("internal class ConversationDictationMediaHandoff"),
                source.indexOf("internal class ConversationDictationPlaybackHandoff"),
            )
        assertTrue(capture.indexOf("playback.pauseActivePlayback()") < capture.indexOf("externalFocus.acquire()"))
        assertTrue(capture.indexOf("externalFocus.release()") < capture.indexOf("playback.resumeInterruptedPlayback()"))
        assertTrue(capture.contains("target.pauseOtherAudio && !externalFocus.acquire()"))
        assertTrue(!capture.contains("stopSpeaking()"))
    }

    /** Verifies voice restoration validates both the retained player and interruption token. */
    @Test
    fun voiceResumeRequiresTheSamePausedClipAndRetainsItsPlayer() {
        val source = source("audio/VoicePlaybackController.kt")
        val resume =
            source.substring(
                source.indexOf("internal fun resumeInterrupted"),
                source.indexOf("fun seekTo", source.indexOf("internal fun resumeInterrupted")),
            )

        assertTrue(
            resume.contains("pausedState.key != interruption.key") &&
                resume.contains("currentKey != interruption.key") &&
                resume.contains("activePlayer !== interruption.playerToken") &&
                resume.contains("pausedState.isPlaying") &&
                resume.contains("startCurrentPlayer(activePlayer)"),
        )
        assertTrue(!resume.contains("prepare"))
    }

    /** Reads production source for structural integration assertions. */
    private fun source(relativePath: String): String =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/$relativePath"),
            File("app/src/main/java/dev/ipf/whitenoise/android/$relativePath"),
        ).firstOrNull { it.exists() }
            ?.readText()
            ?: error("Missing source file: $relativePath")
}
