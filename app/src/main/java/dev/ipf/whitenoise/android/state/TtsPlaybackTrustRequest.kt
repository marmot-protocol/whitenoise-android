package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CompletableDeferred

/** One visible consent request, owned by the coroutine that would start playback. */
internal class TtsPlaybackTrustRequest(
    val isCurrent: () -> Boolean,
) {
    val answer = CompletableDeferred<Boolean>()

    fun respond(proceed: Boolean) {
        answer.complete(proceed && isCurrent())
    }
}
