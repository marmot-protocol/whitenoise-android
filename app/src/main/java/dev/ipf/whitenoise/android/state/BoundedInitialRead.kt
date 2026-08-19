package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bounded await for a detached initial read. A hung native call must not
 * strand the caller — budget exhaustion cancels the deferred handle and exits
 * through [onTimeout], and a late completion is discarded with the abandoned
 * deferred instead of being published into a superseded iteration.
 */
internal suspend fun <T> awaitBoundedInitialRead(
    read: Deferred<Result<T>>,
    budgetMillis: Long,
    onTimeout: () -> Nothing,
): T {
    val outcome = withTimeoutOrNull(budgetMillis.coerceAtLeast(1L)) { read.await() }
    if (outcome == null) {
        read.cancel()
        onTimeout()
    }
    return outcome.getOrThrow()
}

internal class ConversationInitialSnapshotTimeoutException : Exception("initial timeline snapshot exceeded its budget")

internal const val INITIAL_TIMELINE_SNAPSHOT_BUDGET_MILLIS = 5_000L
