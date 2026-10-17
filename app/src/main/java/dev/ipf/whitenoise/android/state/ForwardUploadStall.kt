package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/**
 * How long a forward's destination upload may go without its byte counter advancing before the forward gives up on
 * it (#2556). The counter covers snapshot, encryption and body transfer, so only a transfer that has truly stopped,
 * or a server that never answers, stands still this long.
 */
internal const val FORWARD_UPLOAD_STALL_MILLIS = 60_000L

// How often the watchdog reads the byte counter.
private const val FORWARD_UPLOAD_STALL_CHECK_MILLIS = 1_000L

/**
 * The destination upload stopped making progress and was cancelled. Nothing was published, so the forward fails at
 * its upload stage and Retry uploads again without any risk of a duplicate message.
 */
internal class ForwardUploadStalledException(
    cause: Throwable?,
) : IOException("forward upload made no progress", cause)

/**
 * Runs [upload] while reading [progress]. When the counter stands still for [stallMillis], [cancel] stops the native
 * transfer and the upload ends with [ForwardUploadStalledException], whatever error the cancelled transfer reports.
 * An upload that finishes first returns normally, a counter that cannot be read stops the watchdog rather than the
 * upload, and cancelling the caller stays an ordinary cancellation. [pollContext] keeps the counter reads, which are
 * binding calls, off the caller's thread. Whatever error the cancelled native transfer reports becomes the stall,
 * so every failure type is caught.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun <T> withUploadStallWatchdog(
    stallMillis: Long,
    progress: () -> Long,
    cancel: () -> Unit,
    pollContext: CoroutineContext = Dispatchers.Default,
    upload: suspend () -> T,
): T {
    val stalled = AtomicBoolean(false)
    return coroutineScope {
        val watchdog =
            launch(pollContext) {
                var last = runCatching(progress).getOrNull() ?: return@launch
                var stillMillis = 0L
                while (stillMillis < stallMillis) {
                    delay(FORWARD_UPLOAD_STALL_CHECK_MILLIS)
                    val now = runCatching(progress).getOrNull() ?: return@launch
                    stillMillis = if (now == last) stillMillis + FORWARD_UPLOAD_STALL_CHECK_MILLIS else 0L
                    last = now
                }
                stalled.set(true)
                cancel()
            }
        try {
            upload()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw if (stalled.get()) ForwardUploadStalledException(failure) else failure
        } finally {
            watchdog.cancel()
        }
    }
}
