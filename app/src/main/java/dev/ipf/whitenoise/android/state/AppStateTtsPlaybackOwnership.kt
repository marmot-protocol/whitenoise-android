package dev.ipf.whitenoise.android.state

/** Clears the account, auto-read, history and attachment ownership of a retired speech session. */
internal fun WhiteNoiseAppState.clearTtsPlaybackOwnership() {
    ttsSpeechAccountRef = null
    ttsAutoReadSessionKey = null
    ttsHistorySession.onSessionCleared()
    attachmentSpeechDestination.value = null
}
