package dev.ipf.darkmatter.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.util.concurrent.atomic.AtomicReference

/** Initial backoff before reconnecting a live Marmot subscription (matches iOS). */
internal const val LIVE_SUBSCRIPTION_INITIAL_RETRY_DELAY_MS: Long = 500L

/** Maximum backoff between live subscription reconnect attempts (matches iOS). */
internal const val LIVE_SUBSCRIPTION_MAX_RETRY_DELAY_MS: Long = 8_000L

/**
 * Next delay for a live subscription retry loop: double [current], clamped to
 * [LIVE_SUBSCRIPTION_MAX_RETRY_DELAY_MS].
 */
internal fun nextLiveSubscriptionRetryDelayMillis(current: Long): Long = nextRetryBackoffMillis(current, LIVE_SUBSCRIPTION_MAX_RETRY_DELAY_MS)

/**
 * Run two live subscription consumers in parallel until either finishes
 * (normally or with failure). Cancels the sibling, then rethrows the first
 * recorded failure so callers can handle it in their retry loop.
 */
internal suspend fun CoroutineScope.runUntilFirstLiveSubscriptionEnds(
    first: suspend CoroutineScope.() -> Unit,
    second: suspend CoroutineScope.() -> Unit,
) {
    supervisorScope {
        val ended = CompletableDeferred<Unit>()
        val failure = AtomicReference<Throwable?>(null)
        val jobs =
            listOf(
                launch {
                    try {
                        first()
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (throwable: Throwable) {
                        failure.compareAndSet(null, throwable)
                    } finally {
                        ended.complete(Unit)
                    }
                },
                launch {
                    try {
                        second()
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (throwable: Throwable) {
                        failure.compareAndSet(null, throwable)
                    } finally {
                        ended.complete(Unit)
                    }
                },
            )
        try {
            ended.await()
        } finally {
            jobs.forEach { it.cancel() }
            joinAll(*jobs.toTypedArray())
        }
        failure.get()?.let { throw it }
    }
}
