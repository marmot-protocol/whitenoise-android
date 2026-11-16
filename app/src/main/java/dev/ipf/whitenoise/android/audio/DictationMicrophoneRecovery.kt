package dev.ipf.whitenoise.android.audio

private const val MICROPHONE_UNBLOCK_TIMEOUT_MS = 30_000L

/** One original failure owns both settings return and the finite native reminder lifetime. */
internal class DictationMicrophoneRecovery(
    val failure: ConversationDictationState.Failed,
    val native: Boolean = false,
    var resumed: Boolean = true,
) {
    var dispatched = false
    var paused = false
    var unregister: (() -> Unit)? = null
    var timeout: ConversationDictationTimeoutHandle? = null
}

internal fun ConversationDictationController.clearMicrophoneRecovery() {
    val pending = microphoneSettingsRecovery
    microphoneSettingsRecovery = null
    pending?.timeout?.cancel()
    pending?.unregister?.invoke()
}

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
    clearMicrophoneRecovery()
    microphoneSettingsRecovery = DictationMicrophoneRecovery(failed)
}

internal fun ConversationDictationController.resumeMicrophoneSettingsRecovery(): Boolean {
    val pending = microphoneSettingsRecovery ?: return false
    return when {
        pending.native && !pending.resumed -> true
        else -> {
            val restored =
                runCatching(platform::microphoneAccess).getOrNull() == ConversationDictationMicrophoneAccess.Granted
            // Consume refusal before lock gating: a cancelled return must never arm a later unmute.
            when {
                state !== pending.failure || !restored -> {
                    if (!pending.native || pending.paused) clearMicrophoneRecovery()
                    false
                }
                !microphoneRecoveryAllowed() || (pending.native && !microphoneHostReady()) -> true
                else -> {
                    clearMicrophoneRecovery()
                    retry()
                    true
                }
            }
        }
    }
}

/** Called only from a fresh user gesture or its runtime permission continuation. */
internal fun ConversationDictationController.requestNativeMicrophoneUnblock(
    sessionId: Long,
    target: ConversationDictationTarget,
) {
    val failure =
        if (platform.microphoneAccess() == ConversationDictationMicrophoneAccess.AppOpDenied) {
            ConversationDictationFailure.PermissionPermanentlyDenied
        } else {
            ConversationDictationFailure.MicrophoneMuted
        }
    fail(sessionId, target, failure)
    if (!microphoneRecoveryAllowed()) return
    armMicrophoneSettingsRecovery()
    val failed = microphoneSettingsRecovery?.failure ?: return
    val pending = DictationMicrophoneRecovery(failed, native = true, resumed = microphoneHostReady())
    microphoneSettingsRecovery = pending
    pending.timeout = scheduleTimeout(MICROPHONE_UNBLOCK_TIMEOUT_MS) {
        if (microphoneSettingsRecovery === pending) clearMicrophoneRecovery()
    }
    dispatchNativeMicrophoneUnblock(pending)
}

private fun ConversationDictationController.dispatchNativeMicrophoneUnblock(pending: DictationMicrophoneRecovery) {
    if (pending.dispatched || !pending.resumed) return
    if (!microphoneHostReady() || !microphoneRecoveryAllowed()) return
    if (platform.microphoneAccess() == ConversationDictationMicrophoneAccess.Granted) {
        resumeMicrophoneSettingsRecovery()
    } else {
        registerNativeMicrophoneUnblock(pending)
    }
}

private fun ConversationDictationController.registerNativeMicrophoneUnblock(pending: DictationMicrophoneRecovery) {
    pending.paused = false
    pending.dispatched = true
    val unregister =
        runCatching {
            platform.requestMicrophoneUnblock {
                if (microphoneSettingsRecovery === pending && pending.resumed) resumeMicrophoneSettingsRecovery()
            }
        }.getOrNull()
    when {
        unregister == null -> clearMicrophoneRecovery()
        microphoneSettingsRecovery !== pending -> unregister()
        else -> pending.unregister = unregister
    }
}

/** A pending prompt belongs to the visible origin, unlike an already running durable capture. */
internal fun ConversationDictationController.onMicrophoneOriginChanged(accountRef: String?, groupIdHex: String?) {
    val pending = microphoneSettingsRecovery ?: return
    if (!pending.native) return
    if (
        accountRef == null || groupIdHex == null ||
        !pending.failure.target.matchesConversation(accountRef, groupIdHex)
    ) {
        clearMicrophoneRecovery()
    }
}

/** A synthetic initial RESUME is not a dialog return; only a real pause can cancel. */
internal fun ConversationDictationController.onMicrophoneHostPaused() {
    microphoneSettingsRecovery?.takeIf { it.native }?.let {
        it.paused = true
        it.resumed = false
    }
}

internal fun ConversationDictationController.onMicrophoneHostResumed() {
    val pending = microphoneSettingsRecovery ?: return
    if (!pending.native) return
    pending.resumed = true
    if (!pending.dispatched) {
        dispatchNativeMicrophoneUnblock(pending)
    } else if (pending.paused) {
        resumeMicrophoneSettingsRecovery()
    }
}

internal fun ConversationDictationController.resumeAfterMicrophonePermission(
    pending: ConversationDictationState.PermissionRequired,
) {
    when (platform.microphoneAccess()) {
        ConversationDictationMicrophoneAccess.Granted -> startWhenProviderAvailable(pending.sessionId, pending.target)
        ConversationDictationMicrophoneAccess.RuntimePermissionRequired ->
            fail(pending.sessionId, pending.target, ConversationDictationFailure.PermissionDenied)
        ConversationDictationMicrophoneAccess.AppOpDenied,
        ConversationDictationMicrophoneAccess.MicrophoneMuted,
        -> requestNativeMicrophoneUnblock(pending.sessionId, pending.target)
    }
}
