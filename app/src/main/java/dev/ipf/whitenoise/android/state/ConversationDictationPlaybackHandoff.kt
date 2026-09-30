package dev.ipf.whitenoise.android.state

import android.content.Context
import dev.ipf.whitenoise.android.audio.ConversationDictationAudioFocusDenied
import dev.ipf.whitenoise.android.audio.ConversationDictationAudioFocusLease
import dev.ipf.whitenoise.android.audio.ConversationDictationTarget
import dev.ipf.whitenoise.android.audio.VoicePlaybackController
import dev.ipf.whitenoise.android.audio.tts.TtsController
import dev.ipf.whitenoise.android.audio.tts.TtsState

/** Wires the process-wide speech controllers into the dictation playback handoff. */
internal fun createConversationDictationPlaybackHandoff(ttsController: TtsController) =
    ConversationDictationPlaybackHandoff(
        ttsState = { ttsController.state.value },
        ttsGeneration = ttsController::playbackCallbackGeneration,
        pauseTts = ttsController::pause,
        resumeTts = ttsController::resume,
        pauseVoice = VoicePlaybackController::pauseForInterruption,
        resumeVoice = VoicePlaybackController::resumeInterrupted,
    )

/** Couples the existing app-owned playback handoff to optional external-media focus. */
internal fun createConversationDictationMediaHandoff(
    ttsController: TtsController,
    context: Context,
    endCapture: () -> Unit,
) = ConversationDictationMediaHandoff(
    playback = createConversationDictationPlaybackHandoff(ttsController),
    externalFocus = ConversationDictationAudioFocusLease.android(context, endCapture),
)

/** Ends external focus at physical capture close, before transcript validation or delivery. */
internal class ConversationDictationMediaHandoff(
    private val playback: ConversationDictationPlaybackHandoff,
    private val externalFocus: ConversationDictationAudioFocusLease,
) {
    fun beforeRecognition(target: ConversationDictationTarget) {
        playback.pauseActivePlayback()
        if (target.pauseOtherAudio && !externalFocus.acquire()) throw ConversationDictationAudioFocusDenied()
    }

    fun afterAudioCapture() {
        try {
            externalFocus.release()
        } finally {
            // Platform abandon errors must not strand White Noise-owned speech in Pause.
            playback.resumeInterruptedPlayback()
        }
    }
}

/** Pauses active app speech for dictation and restores only those exact sources afterward. */
internal class ConversationDictationPlaybackHandoff(
    private val ttsState: () -> TtsState,
    private val ttsGeneration: () -> Long,
    private val pauseTts: () -> Unit,
    private val resumeTts: () -> Unit,
    private val pauseVoice: () -> VoicePlaybackController.PausedPlayback?,
    private val resumeVoice: (VoicePlaybackController.PausedPlayback) -> Boolean,
) {
    private data class InterruptedPlayback(
        val ttsSessionId: Long?,
        val ttsGeneration: Long?,
        val voice: VoicePlaybackController.PausedPlayback?,
    )

    private var interruptedPlayback: InterruptedPlayback? = null

    /** Captures and pauses only speech sources that are actively playing. */
    fun pauseActivePlayback() {
        if (interruptedPlayback != null) return
        val currentTts = ttsState()
        val ttsSessionId = (currentTts as? TtsState.Speaking)?.sessionId
        val voice = runCatching(pauseVoice).getOrNull()
        val pausedTtsGeneration =
            ttsSessionId?.let {
                runCatching {
                    pauseTts()
                    ttsGeneration()
                }.getOrNull()
            }
        if (ttsSessionId == null && voice == null) return

        interruptedPlayback =
            InterruptedPlayback(
                ttsSessionId = ttsSessionId,
                ttsGeneration = pausedTtsGeneration,
                voice = voice,
            )
    }

    /** Restores retained sources only when no later user action invalidated them. */
    fun resumeInterruptedPlayback() {
        val interrupted = interruptedPlayback ?: return
        interruptedPlayback = null
        interrupted.ttsSessionId?.let { sessionId ->
            val current = ttsState()
            if (
                current is TtsState.Paused &&
                current.sessionId == sessionId &&
                ttsGeneration() == interrupted.ttsGeneration
            ) {
                resumeTts()
            }
        }
        interrupted.voice?.let(resumeVoice)
    }
}
