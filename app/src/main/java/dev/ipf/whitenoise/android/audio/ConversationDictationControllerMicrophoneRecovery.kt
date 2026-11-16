package dev.ipf.whitenoise.android.audio

/** Arms one return from settings only for the failure whose recovery the user chose. */
internal fun ConversationDictationController.armMicrophoneSettingsRecovery() {
    val failed = state as? ConversationDictationState.Failed ?: return
    if (
        failed.reason !in
            setOf(
                ConversationDictationFailure.MicrophoneMuted,
                ConversationDictationFailure.PermissionDenied,
                ConversationDictationFailure.PermissionPermanentlyDenied,
            ) ||
        !failed.retainedTranscript.isNullOrBlank() ||
        runCatching(platform::callerAudioHasPending).getOrDefault(true)
    ) {
        return
    }
    microphoneSettingsRecovery = failed
}

/** Keeps a settings return bound to its original failure and the host's unlocked state. */
internal fun ConversationDictationController.resumeMicrophoneSettingsRecovery(): Boolean {
    val microphoneRecovery = microphoneSettingsRecovery
    // A foreground callback can precede app-lock evaluation; wait for the host's unlocked return.
    if (microphoneRecovery != null && state === microphoneRecovery && !microphoneRecoveryAllowed()) return true
    microphoneSettingsRecovery = null
    val restored =
        microphoneRecovery != null &&
            state === microphoneRecovery &&
            runCatching(platform::microphoneAccess).getOrNull() == ConversationDictationMicrophoneAccess.Granted
    if (restored) retry()
    return restored
}

/** Rechecks microphone access after a runtime-permission grant before starting capture. */
internal fun ConversationDictationController.resumeAfterMicrophonePermission(
    pending: ConversationDictationState.PermissionRequired,
) {
    when (platform.microphoneAccess()) {
        ConversationDictationMicrophoneAccess.Granted ->
            startWhenProviderAvailable(pending.sessionId, pending.target)
        ConversationDictationMicrophoneAccess.RuntimePermissionRequired ->
            fail(pending.sessionId, pending.target, ConversationDictationFailure.PermissionDenied)
        ConversationDictationMicrophoneAccess.AppOpDenied ->
            fail(pending.sessionId, pending.target, ConversationDictationFailure.PermissionPermanentlyDenied)
        ConversationDictationMicrophoneAccess.MicrophoneMuted ->
            fail(pending.sessionId, pending.target, ConversationDictationFailure.MicrophoneMuted)
    }
}
