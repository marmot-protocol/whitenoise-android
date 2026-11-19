package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.audio.tts.TtsEngineHandle
import dev.ipf.whitenoise.android.audio.tts.requiresTtsTrustWarning
import kotlinx.coroutines.CompletableDeferred

/** One visible consent request, used on the main thread by the dialog and playback. */
internal class TtsPlaybackTrustRequest(
    private val ownerIsCurrent: () -> Boolean,
) {
    private val answer = CompletableDeferred<Boolean>()
    private var dismissed = false

    fun isCurrent(): Boolean = !dismissed && ownerIsCurrent()

    fun respond(proceed: Boolean) {
        if (!proceed) dismissed = true
        answer.complete(proceed && isCurrent())
    }

    suspend fun awaitApproval(): Boolean = answer.await() && isCurrent()
}

/** Serializes the app's first-use warning without owning the engine or speech queue. */
internal class TtsPlaybackTrustConsent(
    private val appState: WhiteNoiseAppState,
    private val attachedHandle: () -> TtsEngineHandle?,
) {
    var pending by mutableStateOf<TtsPlaybackTrustRequest?>(null)
        private set

    fun needsAcknowledgment(): Boolean = attachedHandle()?.let(::needsAcknowledgment) == true

    private fun needsAcknowledgment(handle: TtsEngineHandle): Boolean =
        requiresTtsTrustWarning(handle.enginePackage, handle.trust, appState.ttsWarningPreferences)

    fun cancel() {
        pending?.respond(false)
    }

    suspend fun confirm(isCurrent: () -> Boolean): Boolean {
        if (!isCurrent()) return false
        val handle = attachedHandle()
        return if (handle == null || !needsAcknowledgment(handle)) true else requestApproval(handle, isCurrent)
    }

    private suspend fun requestApproval(
        handle: TtsEngineHandle,
        isCurrent: () -> Boolean,
    ): Boolean {
        val accountIsCurrent = appState.captureTtsAccountOwner(appState.activeAccountRef)
        val playbackIsCurrent = appState.ttsController.speechStartProjectionGuard()
        val question =
            TtsPlaybackTrustRequest {
                attachedHandle() === handle &&
                    accountIsCurrent() &&
                    !appState.appLock.screenVisible &&
                    playbackIsCurrent() &&
                    isCurrent()
            }
        if (pending != null || !question.isCurrent()) return false
        pending = question
        return try {
            if (!question.awaitApproval()) {
                false
            } else {
                appState.acknowledgeTtsTrustWarning(handle.enginePackage)
                true
            }
        } finally {
            if (pending === question) pending = null
        }
    }
}

/** Captures a conversation's account before any suspending speech projection. */
internal fun WhiteNoiseAppState.captureTtsAccountOwner(account: String?): () -> Boolean {
    val generation = accountSwitchHandoff.capture()
    return { account != null && account == activeAccountRef && accountSwitchHandoff.isCurrent(generation) }
}
