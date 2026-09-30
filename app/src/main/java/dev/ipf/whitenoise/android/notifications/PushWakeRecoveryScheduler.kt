package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Serializes query plus committed enqueue across all callback/lifecycle callers.
 * RUNNING includes the interval between doWork returning and its database completion.
 * A queued leaf reads the newest marker; otherwise append exactly one successor.
 * No worker returns failure: exhaustion is success with an unresolved durable marker.
 */
internal object PushWakeRecoveryScheduler {
    internal const val WORK_NAME = "push-wake-recovery"
    private val lock = Any()

    /** Confirms a durable queued owner, or reports failure while retaining the obligation. Call off main. */
    fun schedule(
        context: Context,
        expedited: Boolean = false,
    ): Boolean =
        runCatching {
            schedule(
                store = PushTokenStore.create(context),
                states = {
                    WorkManager
                        .getInstance(context)
                        .getWorkInfosForUniqueWork(WORK_NAME)
                        .get()
                        .map { it.state }
                },
                enqueue = { delayMs ->
                    WorkManager
                        .getInstance(context)
                        .enqueueUniqueWork(
                            WORK_NAME,
                            ExistingWorkPolicy.APPEND_OR_REPLACE,
                            pushWakeWorkRequest(expedited, Build.VERSION.SDK_INT, delayMs),
                        ).result
                        .get()
                },
            )
        }.getOrElse {
            PushWakeDiagnostics.event(PushWakeEvent.ScheduleFailed)
            false
        }

    /** Injectable transaction boundary used by both real database integration and deterministic race tests. */
    internal fun schedule(
        store: PushTokenStore,
        states: () -> List<WorkInfo.State>,
        enqueue: (Long) -> Unit,
    ): Boolean =
        synchronized(lock) {
            runCatching {
                val exhausted = store.pushWakeAttempts() >= PUSH_WAKE_MAX_ATTEMPTS
                if (!store.pushWakeCatchUpPending() || exhausted) return@synchronized true
                if (!hasQueuedPushWake(states())) enqueue(store.pushWakeRetryDelay(System.currentTimeMillis()))
                PushWakeDiagnostics.event(PushWakeEvent.Scheduled)
            }.onFailure { PushWakeDiagnostics.event(PushWakeEvent.ScheduleFailed) }.isSuccess
        }
}

/** A running or finishing worker cannot promise to observe a newer generation. */
internal fun hasQueuedPushWake(states: List<WorkInfo.State>): Boolean =
    states.any { state ->
        state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.BLOCKED
    }

/** Builds one connected-network request; quota rejection downgrades to regular work. */
internal fun pushWakeWorkRequest(
    expedited: Boolean,
    sdk: Int,
    delayMs: Long = 0L,
): OneTimeWorkRequest =
    OneTimeWorkRequestBuilder<PushWakeRecoveryWorker>()
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, PUSH_WAKE_BACKOFF_MS, TimeUnit.MILLISECONDS)
        .apply {
            if (delayMs > 0L) {
                setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            } else if (mayExpeditePushWake(expedited, sdk)) {
                setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
        }.build()
