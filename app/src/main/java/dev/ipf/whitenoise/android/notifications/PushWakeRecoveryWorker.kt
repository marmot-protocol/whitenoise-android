package dev.ipf.whitenoise.android.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Network-constrained one-shot owner; WorkManager supplies its wake lock and retry backoff. */
class PushWakeRecoveryWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    /** Performs at most one native attempt and leaves exhausted/unavailable work for an external trigger. */
    override suspend fun doWork(): Result =
        executePushWakeWork(
            runAttemptCount = runAttemptCount,
            loadStore = { withContext(Dispatchers.IO) { PushTokenStore.create(applicationContext) } },
            recoveryAllowed = {
                withContext(Dispatchers.Main.immediate) {
                    (applicationContext as? WhiteNoiseApplication)?.appState?.pushWakeRecoveryAllowed() == true
                }
            },
            recover = {
                withContext(Dispatchers.Main.immediate) {
                    (applicationContext as WhiteNoiseApplication).appState.runPushWakeRecoveryAttempt()
                }
            },
            schedule = { PushWakeRecoveryScheduler.schedule(applicationContext) },
        )
}

/**
 * Testable worker lifetime contract, including an independent finite bound on storage/bootstrap failures.
 * Every non-fatal initialization/runtime exception consumes the same bounded retry budget.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun executePushWakeWork(
    runAttemptCount: Int,
    loadStore: suspend () -> PushTokenStore,
    recoveryAllowed: suspend () -> Boolean,
    recover: suspend () -> Unit,
    schedule: () -> Boolean,
    nowMs: () -> Long = System::currentTimeMillis,
): Result {
    if (runAttemptCount >= PUSH_WAKE_MAX_ATTEMPTS) return Result.success()
    return try {
        val store = loadStore()
        when {
            !store.pushWakeCatchUpPending() || store.pushWakeAttempts() >= PUSH_WAKE_MAX_ATTEMPTS -> Result.success()
            store.pushWakeRetryDelay(nowMs()) > 0L -> Result.retry()
            !recoveryAllowed() -> Result.success()
            else -> finishPushWakeWork(store, recover, schedule, nowMs)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (fatal: Error) {
        throw fatal
    } catch (_: Exception) {
        PushWakeDiagnostics.event(PushWakeEvent.AttemptFailed)
        Result.retry()
    }
}

/** Converts one attempt into a terminal result or backoff without growing a chain on failure. */
private suspend fun finishPushWakeWork(
    store: PushTokenStore,
    recover: suspend () -> Unit,
    schedule: () -> Boolean,
    nowMs: () -> Long,
): Result {
    PushWakeDiagnostics.event(PushWakeEvent.WorkerStarted)
    recover()
    return when {
        !store.pushWakeCatchUpPending() || store.pushWakeAttempts() >= PUSH_WAKE_MAX_ATTEMPTS -> Result.success()
        store.pushWakeRetryDelay(nowMs()) > 0L -> Result.retry()
        // A newer wake after success needs an immediate fresh successor, not an artificial debounce.
        schedule() -> Result.success()
        else -> Result.retry()
    }
}
