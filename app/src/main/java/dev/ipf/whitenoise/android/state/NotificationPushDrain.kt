package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Arms the drain before startup so a fast notification cannot be missed.
 * Failed catch-up throws for one-shot service supervision. Keep connected mode preserves
 * its healthy receiver and leaves the durable wake for later catch-up instead.
 * A quiet drain returns false without indicating receiver or fetch failure.
 */
internal suspend fun awaitNotificationPushDrain(
    sequenceBeforeStart: Long,
    notificationDrainSignals: Flow<Long>,
    timeoutMs: Long,
    startRuntime: suspend () -> Boolean,
    keepConnected: () -> Boolean,
): Boolean =
    coroutineScope {
        val drain =
            async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(timeoutMs) {
                    notificationDrainSignals.first { it > sequenceBeforeStart }
                } != null
            }
        val catchUpSucceeded = startRuntime()
        check(catchUpSucceeded || keepConnected()) { "push wake account catch-up incomplete" }
        drain.await()
    }
