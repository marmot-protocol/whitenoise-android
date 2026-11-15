package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.marmotkit.AttachmentTransferStatusFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.IOException

private const val PROGRESS_REDRAW_INTERVAL_NANOS = 100_000_000L

/** A transient rendering of native state; attempt identifies the current HTTP body, not a retry budget. */
internal data class NativeAttachmentProgress(
    val phase: AttachmentTransferStateFfi,
    val attempt: ULong,
    val received: ULong,
    val total: ULong?,
    val retryAt: ULong?,
    val reference: String?,
) {
    /** Known body size supports determinate progress only while receiving that body. */
    val fraction: Float?
        get() =
            total
                ?.takeIf { it > 0uL && received <= it && phase == AttachmentTransferStateFfi.DOWNLOADING }
                ?.let { (received.toDouble() / it.toDouble()).toFloat() }
}

/** Keeps byte display monotonic within one body while allowing an honest reset for a replacement body. */
internal class NativeAttachmentProgressReducer {
    private var latest: NativeAttachmentProgress? = null

    /** Processes every native replacement, including byte-only updates suppressed by the rendering throttle. */
    fun update(status: AttachmentTransferStatusFfi): NativeAttachmentProgress {
        val previous = latest
        val sameBody =
            previous?.takeIf {
                it.attempt == status.attempt && it.total == status.total && it.reference == status.reference
            }
        return NativeAttachmentProgress(
            status.state,
            status.attempt,
            if (sameBody != null) maxOf(status.received, sameBody.received) else status.received,
            status.total,
            status.retryAt,
            status.reference,
        ).also { latest = it }
    }
}

/** Observes presentation without requesting, restarting or cancelling acquisition. */
internal fun WhiteNoiseAppState.nativeProgress(request: AttachmentTransferRequest): Flow<NativeAttachmentProgress?> =
    nativeAttachmentProgressFlow {
        if (hasHostCachedAttachmentAfterHydration(request)) {
            null
        } else {
            resolveNativeAttachmentTarget(request)?.let { openNativeAttachmentFeed(request, it) }
        }
    }

/** Clears the previous source on replacement and drops stale phases when its observer ends or fails. */
internal fun nativeAttachmentProgressFlow(open: suspend () -> NativeTransferFeed?): Flow<NativeAttachmentProgress?> =
    flow<NativeAttachmentProgress?> {
        emit(null)
        observeNativeAttachmentProgress(open = open) { emit(it) }
        emit(null)
    }.catch { failure ->
        if (failure is CancellationException) throw failure
        emit(null)
    }.distinctUntilChanged()
        .flowOn(Dispatchers.IO)

/** Installs ownership even if cancellation races subscription creation, and closes the handle off the main thread. */
internal suspend fun observeNativeAttachmentProgress(
    open: suspend () -> NativeTransferFeed?,
    publish: suspend (NativeAttachmentProgress) -> Unit,
) {
    var owned: NativeTransferFeed? = null
    try {
        withContext(NonCancellable) { owned = open() }
        owned?.let { observeNativeAttachmentProgress(it, publish = publish) }
    } finally {
        withContext(NonCancellable + Dispatchers.IO) { owned?.close() }
    }
}

/** Limits byte-only redraws to ten per second; phase, body and deadline changes are never throttled. */
internal suspend fun observeNativeAttachmentProgress(
    feed: NativeTransferFeed,
    nowNanos: () -> Long = System::nanoTime,
    publish: suspend (NativeAttachmentProgress) -> Unit,
) {
    val reducer = NativeAttachmentProgressReducer()
    var displayed: NativeAttachmentProgress? = null
    var lastPublished = 0L
    while (true) {
        val snapshot = feed.next() ?: return
        val status = snapshot.items.singleOrNull() ?: throw IOException("native attachment progress target missing")
        val update = reducer.update(status)
        val now = nowNanos()
        if (displayed?.copy(received = update.received) != update ||
            now - lastPublished >= PROGRESS_REDRAW_INTERVAL_NANOS
        ) {
            publish(update)
            displayed = update
            lastPublished = now
        }
    }
}
