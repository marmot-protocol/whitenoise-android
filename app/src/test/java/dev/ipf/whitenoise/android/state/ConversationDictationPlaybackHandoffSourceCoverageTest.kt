package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConversationDictationPlaybackHandoffSourceCoverageTest {
    @Test
    fun appStateUsesPairedPauseAndResumeCallbacksForDictationCapture() {
        val source = source("state/AppState.kt")
        val wiring =
            source.substring(
                source.indexOf("onBeforeRecognition = {"),
                source.indexOf("tryAcquireMicrophone =", source.indexOf("onBeforeRecognition = {")),
            )

        assertTrue(
            wiring.contains("conversationDictationPlaybackHandoff.pauseActivePlayback()") &&
                wiring.contains(
                    "conversationDictationPlaybackHandoff.resumeInterruptedPlayback()",
                ),
        )
        assertTrue(!wiring.contains("stopSpeaking()"))
    }

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

    private fun source(relativePath: String): String =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/$relativePath"),
            File("app/src/main/java/dev/ipf/whitenoise/android/$relativePath"),
        ).firstOrNull { it.exists() }
            ?.readText()
            ?: error("Missing source file: $relativePath")
}
