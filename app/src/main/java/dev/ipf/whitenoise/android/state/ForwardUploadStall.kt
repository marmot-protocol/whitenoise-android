package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/**
 * How long a forward's destination upload may wait before MDK starts it (#2556). Once a transfer runs, MDK bounds
 * every attempt itself, with a deadline sized to the body and a limit on a silent body or response. Its file upload
 * first waits, without a deadline, for the account worker and a shared media transfer slot, and does not see a
 * cancel while it waits, so only that wait is bounded here.
 */
internal const val FORWARD_UPLOAD_START_MILLIS = 60_000L

// How often the watchdog reads the byte counter.
private const val FORWARD_UPLOAD_START_CHECK_MILLIS = 1_000L

/**
 * The destination upload never started and was abandoned. Nothing was published, so the forward fails at its upload
 * stage and Retry uploads again without any risk of a duplicate message.
 */
internal class ForwardUploadStalledException(
    cause: Throwable?,
) : IOException("forward upload did not start", cause)

/**
 * Runs [upload] while reading [progress], MDK's byte counter for this transfer. If the counter has not moved after
 * [startMillis], [cancel] tells MDK to skip the transfer and [upload] itself is cancelled, because a queued native
 * call does not return on a cancel alone, and the upload ends with [ForwardUploadStalledException]. Once the counter
 * moves the watchdog stops: MDK raises it to a per-file high-water mark, so it stands still while a smaller album
 * item or a fallback server catches up, and MDK's own limits cover that time. An upload that finishes first returns
 * normally, a counter that cannot be read stops the watchdog rather than the upload, and cancelling the caller stays
 * an ordinary cancellation. [pollContext] keeps the counter reads and the cancel, which are binding calls, off the
 * caller's thread. Whatever exception the abandoned transfer reports, a cancellation included, becomes the stall.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun <T> withUploadStartWatchdog(
    startMillis: Long,
    progress: () -> Long,
    cancel: () -> Unit,
    pollContext: CoroutineContext = Dispatchers.Default,
    upload: suspend () -> T,
): T {
    val stalled = AtomicBoolean(false)
    return supervisorScope {
        val work = async { upload() }
        val watchdog =
            launch(pollContext) {
                if (startsWithin(startMillis, progress)) return@launch
                stalled.set(true)
                runCatching(cancel)
                work.cancel(CancellationException("forward upload did not start"))
            }
        try {
            work.await()
        } catch (failure: Exception) {
            // The abandoned transfer unwinds as a cancellation. Only the caller's own cancellation stays one.
            val isStall = stalled.get() && (failure !is CancellationException || isActive)
            throw if (isStall) ForwardUploadStalledException(failure) else failure
        } finally {
            watchdog.cancel()
        }
    }
}

/**
 * Whether the transfer starts within [limitMillis]: its counter is already above zero at the first reading, because
 * MDK has begun before the watchdog looked, or moves away from that reading in time. A counter that cannot be read
 * counts as started, so the watchdog never fails a transfer it cannot observe.
 */
private suspend fun startsWithin(
    limitMillis: Long,
    progress: () -> Long,
): Boolean {
    val first = runCatching(progress).getOrNull()
    var started = first == null || first > 0L
    var waitedMillis = 0L
    while (!started && waitedMillis < limitMillis) {
        delay(FORWARD_UPLOAD_START_CHECK_MILLIS)
        waitedMillis += FORWARD_UPLOAD_START_CHECK_MILLIS
        val now = runCatching(progress).getOrNull()
        started = now == null || now != first
    }
    return started
}
