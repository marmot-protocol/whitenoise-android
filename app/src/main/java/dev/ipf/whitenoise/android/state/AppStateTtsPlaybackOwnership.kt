package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.audio.tts.TtsEngineHandle
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** Clears the account, auto-read, history and attachment ownership of a retired speech session. */
internal fun WhiteNoiseAppState.clearTtsPlaybackOwnership() {
    ttsSpeechAccountRef = null
    ttsAutoReadSessionKey = null
    ttsHistorySession.onSessionCleared()
    attachmentSpeechDestination.value = null
}

/** Clears approval and releases a suspect connection before discovering an engine again. */
internal fun WhiteNoiseAppState.retireFailedTtsConnection(handle: TtsEngineHandle) {
    mutationsScope.launch {
        // The synchronous adapter is already retired. Defer controller replacement until its current call unwinds.
        yield()
        if (attachedTtsHandle !== handle) return@launch
        publishTtsResolution(ttsResolution?.copy(handle = null))
        runCatching { handle.release() }
        refreshTtsAvailability()
    }
}
