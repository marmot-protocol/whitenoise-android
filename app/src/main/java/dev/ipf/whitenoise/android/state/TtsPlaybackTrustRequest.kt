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
    private var approvedUnverifiedHandle: TtsEngineHandle? = null
    private var approvedUnverifiedGeneration: Long? = null

    var pending by mutableStateOf<TtsPlaybackTrustRequest?>(null)
        private set

    fun needsAcknowledgment(): Boolean = attachedHandle()?.let(::needsAcknowledgment) == true

    private fun needsAcknowledgment(handle: TtsEngineHandle): Boolean =
        if (handle.connectedPackageVerified) {
            requiresTtsTrustWarning(handle.enginePackage, handle.trust, appState.ttsWarningPreferences)
        } else {
            // Approval covers one connection within one account session; any account switch revokes it.
            approvedUnverifiedHandle !== handle ||
                approvedUnverifiedGeneration?.let(appState.accountSwitchHandoff::isCurrent) != true
        }

    /** A replacement connection cannot inherit consent from an inferred package. */
    fun onHandleReplaced() {
        approvedUnverifiedHandle = null
        approvedUnverifiedGeneration = null
        cancel()
    }

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
        val accountGeneration = appState.accountSwitchHandoff.capture()
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
                if (handle.connectedPackageVerified) {
                    appState.acknowledgeTtsTrustWarning(handle.enginePackage)
                } else {
                    approvedUnverifiedHandle = handle
                    approvedUnverifiedGeneration = accountGeneration
                }
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
