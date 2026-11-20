package dev.ipf.whitenoise.android.audio

import dev.ipf.whitenoise.android.audio.ConversationDictationController.Companion.GENERATION_RESTART_DELAY_MILLIS
import dev.ipf.whitenoise.android.audio.ConversationDictationController.Companion.ORDINARY_SILENCE_MILLIS

/**
 * Recycles a caller-audio generation whose provider ended empty before it claimed any chunk.
 *
 * A provider may idle out a request whose pipe has not received audio yet while White Noise is
 * still sealing the chunk. No captured audio belonged to that request, so the recycle consumes no
 * chunk retry budget and no no-speech accounting. An empty end faster than ordinary silence keeps
 * the bounded failure route, so a provider that rejects every request cannot loop unbounded.
 * Returns true when the generation was recycled.
 */
@Suppress("ReturnCount")
internal fun ConversationDictationController.recycleIdleCallerAudioGeneration(
    sessionId: Long,
    target: ConversationDictationTarget,
    error: ConversationDictationFailure,
    generationElapsedMillis: Long,
): Boolean {
    if (!error.isEmptyRecognition || generationElapsedMillis < ORDINARY_SILENCE_MILLIS) return false
    val session = generation.session ?: return false
    if (!runCatching(session::usesCallerAudioCapture).getOrDefault(false)) return false
    if (runCatching(session::callerAudioChunkId).getOrElse { return false } != null) return false
    conversationDictationDiagnostic(
        "event=caller_audio_idle_recycled failure=${error.name} finish_requested=$finishRequested",
    )
    clearRecognitionGeneration(cancel = false)
    when {
        !finishRequested -> scheduleRestart(sessionId, target, GENERATION_RESTART_DELAY_MILLIS, "caller_audio_idle")
        runCatching(platform::callerAudioHasPending).getOrDefault(false) -> startRecognition(sessionId, target)
        else -> finalizeAccumulatedTranscript(sessionId, target)
    }
    return true
}
