package dev.ipf.whitenoise.android.updates

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.CompletableDeferred

/** Shares a single fetch across the worker and lifecycle repositories without creating an unowned scope. */
internal object AppUpdateAdmission {
    private val lock = Any()
    private var inFlight: CompletableDeferred<Unit>? = null

    /**
     * Only the first eligible caller owns the fetch. Cancelling a joiner leaves it running; cancelling
     * the owner cancels its waiters and releases admission for the next call. Timing is read under the
     * same lock as flight creation, so queued automatic callers cannot race a just-persisted outcome.
     */
    @Suppress("TooGenericExceptionCaught") // Every owner outcome must release and complete its waiters.
    suspend fun run(
        shouldStart: () -> Boolean,
        check: suspend () -> Unit,
    ): Boolean {
        var owner = false
        val flight =
            synchronized(lock) {
                inFlight?.takeUnless { it.isCompleted } ?: run {
                    if (!shouldStart()) return false
                    CompletableDeferred<Unit>().also {
                        inFlight = it
                        owner = true
                    }
                }
            }
        if (owner) {
            try {
                check()
                flight.complete(Unit)
            } catch (error: Throwable) {
                flight.completeExceptionally(error)
                throw error
            } finally {
                synchronized(lock) {
                    if (inFlight === flight) inFlight = null
                }
            }
        } else {
            flight.await()
        }
        return true
    }
}

/** Matches the worker's connected constraint and metered Data Saver restriction for every automatic caller. */
internal fun automaticUpdateNetworkAllowed(context: Context): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
    if (manager.activeNetwork == null) return false
    return !manager.isActiveNetworkMetered ||
        manager.restrictBackgroundStatus != ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
}

/** A wall-clock correction must not strand a persisted check in the future indefinitely. */
internal fun updateIntervalElapsed(
    nowMillis: Long,
    lastMillis: Long,
    intervalMillis: Long,
): Boolean = lastMillis <= 0L || nowMillis < lastMillis || nowMillis - lastMillis >= intervalMillis
