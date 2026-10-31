package dev.ipf.whitenoise.android.media.sandbox

import android.content.Context
import android.net.Uri
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** A paused seek acknowledges its bounded attempt; a refused attempt never invents a new frame. */
internal class RawAvPausedSeekRequest(
    val target: RawAvPosterTarget,
    val completion: SettableFuture<Void>,
) {
    fun finish(accepted: Boolean) {
        if (accepted) completion.set(null) else completion.cancel(false)
    }
}

internal interface RawAvPausedPosterOwner {
    fun awaitPrepared()

    fun posterCurrent(target: RawAvPosterTarget): Boolean

    /** True transfers ownership of any returned poster; false leaves recycling to the helper. */
    fun posterResult(
        request: RawAvPausedSeekRequest,
        probe: MediaProbe?,
    ): Boolean
}

/** One in-flight automatic request and one conflated target; no playback lane or focus ownership. */
internal class RawAvPausedPoster(
    private val context: Context,
    private val uri: Uri,
    scope: CoroutineScope,
    private val owner: RawAvPausedPosterOwner,
) {
    private val requests = Channel<RawAvPausedSeekRequest>(Channel.CONFLATED)

    init {
        scope.launch {
            for (request in requests) attempt(request)
        }
    }

    fun submit(request: RawAvPausedSeekRequest) {
        requests.trySend(request)
    }

    @Suppress("TooGenericExceptionCaught") // Bounded optional poster refusal preserves the prior frame and duration.
    private fun attempt(request: RawAvPausedSeekRequest) {
        var probe: MediaProbe? = null
        var accepted = false
        try {
            // Initial preparation owns the same automatic lane; never race it with a second request.
            owner.awaitPrepared()
            if (!request.completion.isCancelled && owner.posterCurrent(request.target)) {
                probe = IsolatedMediaDecoder.probeUriAt(context.contentResolver, uri, request.target.positionMs)
            }
            accepted = owner.posterResult(request, probe)
        } catch (_: CancellationException) {
            // Stop may cancel the preparation future; that request is refused without killing this producer.
            accepted = owner.posterResult(request, null)
        } catch (_: Exception) {
            accepted = owner.posterResult(request, null)
        } finally {
            if (!accepted) probe?.poster?.recycle()
            if (!accepted) request.finish(false)
        }
    }
}
