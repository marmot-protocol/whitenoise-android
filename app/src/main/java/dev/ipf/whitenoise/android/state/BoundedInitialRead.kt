package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs a resource-producing native read behind a bounded handoff.
 *
 * Native work can finish after the caller's budget or lifecycle expires. The
 * producer therefore owns the resource until the handoff succeeds; a late
 * result is closed rather than leaked. The small detached scope exists only
 * until that native call returns and is necessary because cancelling an FFI
 * await cannot reliably stop work already running on Rust's blocking pool.
 */
@Suppress("TooGenericExceptionCaught") // The detached owner must capture every producer outcome for handoff or cleanup.
internal suspend fun <T> awaitBoundedInitialResourceRead(
    budgetMillis: Long,
    producerDispatcher: CoroutineDispatcher = Dispatchers.IO,
    read: suspend () -> T,
    closeLate: suspend (T) -> Unit,
    onTimeout: () -> Nothing,
): T {
    val handoff = CompletableDeferred<Result<T>>()
    val producerScope = CoroutineScope(SupervisorJob() + producerDispatcher)
    producerScope.launch {
        // This scope is deliberately not lifecycle-cancelled: even a native
        // CancellationException is a completed producer outcome that must be
        // handed off (or have its resource closed), never an uncaught launch.
        val outcome =
            try {
                Result.success(read())
            } catch (throwable: Throwable) {
                Result.failure(throwable)
            }
        if (!handoff.complete(outcome)) {
            outcome.getOrNull()?.let { resource ->
                withContext(NonCancellable) { closeLate(resource) }
            }
        }
        producerScope.cancel()
    }

    suspend fun abandonHandoff() {
        handoff.cancel()
        // If completion won the race with cancel(), ownership transferred to
        // the handoff but the timed-out caller will not consume it. Close it
        // here; otherwise the producer observes complete()==false and closes.
        if (!handoff.isCancelled) {
            handoff.await().getOrNull()?.let { closeLate(it) }
        }
    }

    return try {
        val outcome = withTimeoutOrNull(budgetMillis.coerceAtLeast(1L)) { handoff.await() }
        if (outcome == null) {
            withContext(NonCancellable) { abandonHandoff() }
            onTimeout()
        }
        outcome.getOrThrow()
    } catch (cancel: java.util.concurrent.CancellationException) {
        withContext(NonCancellable) { abandonHandoff() }
        throw cancel
    }
}

/** Bounds a native value read whose late result needs no resource cleanup. */
internal suspend fun <T> awaitBoundedInitialSnapshotRead(
    budgetMillis: Long = INITIAL_TIMELINE_READ_BUDGET_MILLIS,
    producerDispatcher: CoroutineDispatcher = Dispatchers.IO,
    read: suspend () -> T,
): T =
    awaitBoundedInitialResourceRead(
        budgetMillis = budgetMillis,
        producerDispatcher = producerDispatcher,
        read = read,
        closeLate = {},
        onTimeout = { throw ConversationInitialLoadTimeoutException() },
    )

internal class ConversationInitialLoadTimeoutException : Exception("initial conversation load exceeded its budget")

internal const val INITIAL_TIMELINE_READ_BUDGET_MILLIS = 5_000L
