package dev.ipf.whitenoise.android.state

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Gives MDK's account history backups a regular chance to run while the device is charging on an unmetered network
 * (#3655). Scheduled with the app's other periodic work; like the expiry sweep, a visit checks MDK's account inventory
 * before starting the runtime, and MDK answers NotStarted for accounts with backups off. Stopping the work
 * (constraints lost, runtime closed) detaches the wait; MDK settles any pass it already admitted.
 */
class HistoryBackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? WhiteNoiseApplication ?: return Result.success()
        val settled =
            runCatchingCancellable {
                withContext(Dispatchers.Main.immediate) {
                    !hasSignedInAccount(app) || app.appState.runDueHistoryBackups(PASS_BUDGET_MILLIS)
                }
            }.onFailure { Log.w(TAG, "backup_visit_failed ${it.javaClass.simpleName}") }.getOrDefault(false)
        return if (settled) Result.success() else Result.retry()
    }

    companion object {
        private const val TAG = "HistoryBackupWorker"
        private const val UNIQUE_WORK_NAME = "history_backup"

        /** MDK writes at most daily; a twice-daily visit catches a due pass soon after it becomes due. */
        private const val VISIT_INTERVAL_HOURS = 12L

        /** Stays under WorkManager's ten-minute execution limit. */
        private const val PASS_BUDGET_MILLIS = 8L * 60L * 1000L

        internal fun constraints(): Constraints =
            Constraints
                .Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresCharging(true)
                .setRequiresBatteryNotLow(true)
                .build()

        /** Enqueues the visit, keeping an existing schedule so app restarts do not reset its cadence. */
        fun schedule(context: Context): Operation {
            val request =
                PeriodicWorkRequestBuilder<HistoryBackupWorker>(VISIT_INTERVAL_HOURS, TimeUnit.HOURS)
                    .setConstraints(constraints())
                    .build()
            return WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
