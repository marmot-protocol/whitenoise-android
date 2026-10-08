package dev.ipf.whitenoise.android.audio.tts

import dev.ipf.whitenoise.android.state.StalenessGuard
import kotlinx.coroutines.Job

/** One history/seek request lifetime; invalidate before cancellation so queued completions stay stale. */
internal class TtsHistoryPendingRequest(
    private val clearEdge: () -> Unit,
) {
    val requests = StalenessGuard()
    var job: Job? = null
    var targetSeek = false
    var playbackDeferral = false
    var renderedSeek = false

    fun invalidate() {
        requests.advance()
        job?.cancel()
        job = null
        targetSeek = false
        playbackDeferral = false
        renderedSeek = false
        clearEdge()
    }
}
