package dev.ipf.whitenoise.android.audio

import dev.ipf.whitenoise.android.audio.ConversationDictationState.PermissionRequired

private const val MICROPHONE_UNBLOCK_TIMEOUT_MS = 30_000L

/** One original failure owns both settings return and the finite native reminder lifetime. */
internal class DictationMicrophoneRecovery(
    val failure: ConversationDictationState.Failed,
    val native: Boolean,
    val expiresAtElapsedRealtime: Long,
    var resumed: Boolean = true,
) {
    var dispatched = false
    var paused = false
    var returned = false
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
internal fun ConversationDictationController.armMicrophoneSettingsRecovery(native: Boolean = false) {
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
    val pending =
        DictationMicrophoneRecovery(
            failed,
            native = native,
            expiresAtElapsedRealtime = elapsedRealtime() + MICROPHONE_UNBLOCK_TIMEOUT_MS,
            resumed = microphoneHostReady(),
        )
    microphoneSettingsRecovery = pending
    pending.timeout =
        scheduleTimeout(MICROPHONE_UNBLOCK_TIMEOUT_MS) {
            if (microphoneSettingsRecovery === pending) clearMicrophoneRecovery()
        }
}

internal fun ConversationDictationController.resumeMicrophoneSettingsRecovery(): Boolean {
    val pending = microphoneSettingsRecovery ?: return false
    return when {
        elapsedRealtime() >= pending.expiresAtElapsedRealtime -> {
            clearMicrophoneRecovery()
            false
        }
        !pending.resumed -> true
        else -> {
            val restored =
                runCatching(platform::microphoneAccess).getOrNull() == ConversationDictationMicrophoneAccess.Granted
            // Consume refusal before lock gating: a cancelled return must never arm a later unmute.
            when {
                !microphoneOriginValid() -> {
                    clearMicrophoneRecovery()
                    false
                }
                state !== pending.failure || !restored -> {
                    if (!pending.native || pending.paused) clearMicrophoneRecovery()
                    false
                }
                !microphoneRecoveryAllowed() || !microphoneHostReady() -> true
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
    armMicrophoneSettingsRecovery(native = true)
    val pending = microphoneSettingsRecovery ?: return
    dispatchNativeMicrophoneUnblock(pending)
}

private fun ConversationDictationController.dispatchNativeMicrophoneUnblock(pending: DictationMicrophoneRecovery) {
    when {
        elapsedRealtime() >= pending.expiresAtElapsedRealtime -> clearMicrophoneRecovery()
        pending.dispatched || !pending.resumed -> Unit
        !microphoneHostReady() || !microphoneRecoveryAllowed() -> Unit
        platform.microphoneAccess() == ConversationDictationMicrophoneAccess.Granted -> resumeMicrophoneSettingsRecovery()
        else -> registerNativeMicrophoneUnblock(pending)
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
internal fun ConversationDictationController.onMicrophoneOriginChanged(
    accountRef: String?,
    groupIdHex: String?,
) {
    val pending = microphoneSettingsRecovery ?: return
    if (
        accountRef == null ||
        groupIdHex == null ||
        !pending.failure.target.matchesConversation(accountRef, groupIdHex)
    ) {
        clearMicrophoneRecovery()
    }
}

/** Keep the outbound settings excursion, but never a second, unrelated foreground entry. */
internal fun ConversationDictationController.onMicrophoneBackgrounded() {
    microphoneSettingsRecovery?.let {
        if (it.native || it.returned) clearMicrophoneRecovery()
    }
}

/** A synthetic initial RESUME is not a dialog return; only a real pause can cancel. */
internal fun ConversationDictationController.onMicrophoneHostPaused() {
    microphoneSettingsRecovery?.let {
        it.paused = true
        it.resumed = false
    }
}

internal fun ConversationDictationController.onMicrophoneHostResumed() {
    val pending = microphoneSettingsRecovery ?: return
    pending.returned = pending.returned || pending.paused
    pending.resumed = true
    if (pending.native && !pending.dispatched) {
        dispatchNativeMicrophoneUnblock(pending)
    } else if (pending.paused || !pending.native) {
        resumeMicrophoneSettingsRecovery()
    }
}

internal fun ConversationDictationController.resumeAfterMicrophonePermission(pending: PermissionRequired) {
    when (platform.microphoneAccess()) {
        ConversationDictationMicrophoneAccess.Granted -> startWhenProviderAvailable(pending.sessionId, pending.target)
        ConversationDictationMicrophoneAccess.RuntimePermissionRequired ->
            fail(pending.sessionId, pending.target, ConversationDictationFailure.PermissionDenied)
        ConversationDictationMicrophoneAccess.AppOpDenied,
        ConversationDictationMicrophoneAccess.MicrophoneMuted,
        -> requestNativeMicrophoneUnblock(pending.sessionId, pending.target)
    }
}
