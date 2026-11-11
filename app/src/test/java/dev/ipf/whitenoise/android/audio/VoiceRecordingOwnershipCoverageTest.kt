package dev.ipf.whitenoise.android.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Protect every shipped path into foreground voice capture, not a parallel test-only owner. */
class VoiceRecordingOwnershipCoverageTest {
    /** Registers production capture and review-owned feedback rather than a detached snackbar. */
    @Test
    fun conversationInstallsForegroundLifecycleAndRecordingBudgetFeedback() {
        val conversation = source("ui/conversation/ConversationScreen.kt")
        val lifecycle = source("ui/conversation/composer/VoiceRecordingLifecycle.kt")
        assertTrue(conversation.contains("VoiceRecordingLifecycleEffect(voiceRecordingController)"))
        assertTrue(lifecycle.contains("Lifecycle.Event.ON_PAUSE) controller.interrupt()"))
        assertTrue(lifecycle.contains("if (recording) view.keepScreenOn = true"))
        assertTrue(lifecycle.contains("onDispose { if (recording) view.keepScreenOn = previous }"))
        assertTrue(conversation.contains("rememberVoiceRecordingStopNotice("))
        assertTrue(conversation.contains("voiceRecordingStopNotice(reason, completedClip)"))
        assertTrue(lifecycle.contains("R.string.voice_recording_limit_reached"))
        assertTrue(lifecycle.contains("R.string.voice_recording_interrupted"))
        assertTrue(lifecycle.contains("appState.presentConversationTransient("))
        assertTrue(lifecycle.contains("appState::clearTransientNotice"))
        val automaticStop =
            conversation
                .substringAfter("onAutomaticStop = { reason ->")
                .substringBefore("\n                },")
        assertFalse(automaticStop.contains("appState.present("))
    }

    /** Checks that silencing and the time cap share finalization and recorder callback cleanup. */
    @Test
    fun platformSilencingAndCapUseTheSameReviewOnlyFinalizer() {
        val controller = source("audio/VoiceRecordingController.kt")
        val recorder = source("audio/VoiceRecorder.kt")
        assertTrue(controller.contains("r.captureInterrupted -> finish(VoiceRecordingStopReason.Interrupted)"))
        assertTrue(controller.contains("elapsed >= MAX_RECORDING_MS -> finish(VoiceRecordingStopReason.LimitReached)"))
        assertTrue(controller.contains("if (reason == null) withTimeoutOrNull(RECORDING_TAIL_MS)"))
        assertTrue(recorder.contains("configs.any { it.isClientSilenced }"))
        assertTrue(recorder.contains("unregisterAudioRecordingCallback(callback)"))
    }

    /**
     * A discarded take's native close is tracked, a new take waits for it before opening a recorder, and
     * its cleanup releases the shared lease only when no newer take holds it (#3707).
     */
    @Test
    fun discardedTakeCloseIsSerializedBeforeAReplacementTake() {
        val controller = source("audio/VoiceRecordingController.kt")
        val trackedDiscard = "discardJob =\n                recorderScope.launch {"
        assertTrue("the discard close is tracked", controller.contains(trackedDiscard))
        val join = controller.indexOf("discarding?.join()")
        assertTrue(
            "a new take waits for the discard before opening a recorder",
            join >= 0 && join < controller.indexOf("beginRecording(generation)\n            }"),
        )
        assertTrue(
            "the discard releases only an unclaimed lease",
            controller.contains(
                "if (recorder == null && startJob?.isActive != true && restartJob?.isActive != true) {",
            ),
        )
    }

    /** Reads production source from either the module or repository test working directory. */
    private fun source(path: String): String =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/$path"),
            File("app/src/main/java/dev/ipf/whitenoise/android/$path"),
        ).first { it.isFile }.readText()
}
