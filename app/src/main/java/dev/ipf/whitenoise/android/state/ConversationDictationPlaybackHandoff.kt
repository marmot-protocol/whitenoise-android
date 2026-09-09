package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.audio.VoicePlaybackController
import dev.ipf.whitenoise.android.audio.tts.TtsController
import dev.ipf.whitenoise.android.audio.tts.TtsState

/** Wires the process-wide speech controllers into the dictation playback handoff. */
internal fun createConversationDictationPlaybackHandoff(ttsController: TtsController): ConversationDictationPlaybackHandoff =
    ConversationDictationPlaybackHandoff(
        ttsState = { ttsController.state.value },
        pauseTts = ttsController::pause,
        resumeTts = ttsController::resume,
        pauseVoice = VoicePlaybackController::pauseForInterruption,
        resumeVoice = VoicePlaybackController::resumeInterrupted,
    )

/** Pauses active app speech for dictation and restores only those exact sources afterward. */
internal class ConversationDictationPlaybackHandoff(
    private val ttsState: () -> TtsState,
    private val pauseTts: () -> Unit,
    private val resumeTts: () -> Unit,
    private val pauseVoice: () -> VoicePlaybackController.PausedPlayback?,
    private val resumeVoice: (VoicePlaybackController.PausedPlayback) -> Boolean,
) {
    private data class InterruptedPlayback(
        val ttsSessionId: Long?,
        val voice: VoicePlaybackController.PausedPlayback?,
    )

    private var interruptedPlayback: InterruptedPlayback? = null

    fun pauseActivePlayback() {
        if (interruptedPlayback != null) return
        val currentTts = ttsState()
        val ttsSessionId = (currentTts as? TtsState.Speaking)?.sessionId
        val voice = runCatching(pauseVoice).getOrNull()
        if (ttsSessionId != null) runCatching(pauseTts)
        if (ttsSessionId == null && voice == null) return

        interruptedPlayback = InterruptedPlayback(ttsSessionId = ttsSessionId, voice = voice)
    }

    fun resumeInterruptedPlayback() {
        val interrupted = interruptedPlayback ?: return
        interruptedPlayback = null
        interrupted.ttsSessionId?.let { sessionId ->
            val current = ttsState()
            if (current is TtsState.Paused && current.sessionId == sessionId) {
                resumeTts()
            }
        }
        interrupted.voice?.let(resumeVoice)
    }
}
