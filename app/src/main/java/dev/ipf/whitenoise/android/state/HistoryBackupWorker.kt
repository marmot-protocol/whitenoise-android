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
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/** What one background visit did, for the worker's result. */
internal enum class HistoryBackupVisit {
    /** No signed-in account has backups that this device writes; the schedule can stop. */
    NothingToBackUp,

    /** Each writing account's due pass was joined or found not due. */
    Ran,

    /** A pass failed or ran out of time; WorkManager tries again later. */
    Retry,
}

/**
 * Runs MDK's due backup pass for each signed-in account whose backups this device writes. MDK decides whether a pass
 * is due and what it writes; this only gives it time while WorkManager's constraints hold. A pass that outlives
 * [budgetMillis] is detached, not cancelled in MDK, and the next visit joins it again.
 */
internal suspend fun runDueHistoryBackups(
    marmot: MarmotInterface,
    accountRefs: List<String>,
    budgetMillis: Long,
): HistoryBackupVisit {
    var writers = 0
    var retry = false
    val deadline = System.currentTimeMillis() + budgetMillis
    for (account in accountRefs) {
        currentCoroutineContext().ensureActive()
        val writes =
            try {
                marmot.backupSettings(account).let { it.enabled && it.thisDeviceWrites }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                retry = true
                false
            }
        if (!writes) continue
        writers++
        val remaining = deadline - System.currentTimeMillis()
        val outcome =
            try {
                withTimeoutOrNull(remaining) { marmot.runDueBackupPass(account) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        if (outcome == null) retry = true
    }
    return when {
        retry -> HistoryBackupVisit.Retry
        writers == 0 -> HistoryBackupVisit.NothingToBackUp
        else -> HistoryBackupVisit.Ran
    }
}

private const val TAG = "HistoryBackupWorker"

/**
 * Gives MDK's account history backups a regular chance to run while the device is charging on an unmetered network
 * (#3655). The schedule starts when the user turns backups on and stops itself once no account needs it. Stopping
 * the work (constraints lost, runtime closed) detaches the wait; MDK settles any pass it already admitted.
 */
class HistoryBackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    @Suppress("TooGenericExceptionCaught")
    override suspend fun doWork(): Result {
        val app = applicationContext as? WhiteNoiseApplication ?: return Result.success()
        val startedAt = synchronized(scheduleLock) { scheduleGeneration }
        val visit =
            try {
                withContext(Dispatchers.Main.immediate) {
                    val state = app.appState
                    // Its result reports push catch-up, not readiness; an unready runtime throws or stays null.
                    state.ensureNotificationRuntimeStarted()
                    val marmot = state.retentionSweepRuntimeOrNull() ?: return@withContext HistoryBackupVisit.Retry
                    HistoryBackupConditions.attach(app, marmot)
                    // MDK admits no pass until it holds the facts; never run on the closed default.
                    if (!HistoryBackupConditions.reportNow(app, marmot)) return@withContext HistoryBackupVisit.Retry
                    val accounts = state.accounts.filter { it.isSignedInSigningAccount() }.map { it.label }
                    withContext(Dispatchers.IO) { runDueHistoryBackups(marmot, accounts, PASS_BUDGET_MILLIS) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "backup_visit_failed ${error.javaClass.simpleName}")
                HistoryBackupVisit.Retry
            }
        return when (visit) {
            HistoryBackupVisit.NothingToBackUp -> {
                // A writer the screen saw during this visit asked for the schedule; keep it.
                synchronized(scheduleLock) {
                    if (scheduleGeneration == startedAt) {
                        WorkManager.getInstance(applicationContext).cancelUniqueWork(UNIQUE_WORK_NAME)
                    }
                }
                Result.success()
            }
            HistoryBackupVisit.Ran -> Result.success()
            HistoryBackupVisit.Retry -> Result.retry()
        }
    }

    companion object {
        internal const val UNIQUE_WORK_NAME = "history_backup"

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

        private val scheduleLock = Any()
        private var scheduleGeneration = 0L

        /** Starts the schedule, keeping an existing one so its cadence is not reset. */
        fun schedule(context: Context): Operation =
            synchronized(scheduleLock) {
                scheduleGeneration++
                val request =
                    PeriodicWorkRequestBuilder<HistoryBackupWorker>(VISIT_INTERVAL_HOURS, TimeUnit.HOURS)
                        .setConstraints(constraints())
                        .build()
                WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                    UNIQUE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
            }
    }
}
