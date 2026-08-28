package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull

internal data class NotificationFirstPost(
    val epoch: Long,
    val engineMuted: Boolean,
    val shouldPost: Boolean,
    val senderName: String?,
)

/**
 * Runs one best-effort local identity read without letting a slow synchronous
 * binding hold the first notification post. Timed-out work is cancelled and
 * retains the permit only until the underlying call actually returns, so a
 * stuck read cannot create an unbounded queue of blocked FFI calls.
 */
internal class NotificationLocalIdentityReader(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val timeoutMillis: Long = FIRST_POST_IDENTITY_TIMEOUT_MILLIS,
    private val readLocalDisplayName: suspend (String) -> String?,
) {
    private val gate = Semaphore(permits = 1)

    init {
        require(timeoutMillis > 0L)
    }

    suspend fun read(senderIdHex: String): String? {
        if (!gate.tryAcquire()) return null
        val read =
            scope.async(dispatcher) {
                readLocalDisplayName(senderIdHex)
            }
        read.invokeOnCompletion { gate.release() }
        return try {
            withTimeoutOrNull(timeoutMillis) { read.await() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        } finally {
            if (!read.isCompleted) read.cancel()
        }
    }

    private companion object {
        const val FIRST_POST_IDENTITY_TIMEOUT_MILLIS = 100L
    }
}
