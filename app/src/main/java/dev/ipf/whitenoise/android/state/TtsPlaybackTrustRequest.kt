package dev.ipf.whitenoise.android.state

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
