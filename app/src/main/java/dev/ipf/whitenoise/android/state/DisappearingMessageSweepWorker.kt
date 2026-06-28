package dev.ipf.whitenoise.android.state

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import java.util.concurrent.TimeUnit

/**
 * Periodic background sweep that prunes expired disappearing messages for the
 * user's *closed* conversations (#745).
 *
 * The in-conversation sweep only runs while a chat is open, so a message that
 * expires while its conversation is closed lingers — its decrypted L2 media
 * isn't secure-deleted and a stale tray card can keep pointing at it — until
 * the user reopens that chat. This worker closes that gap: WorkManager runs it
 * on a coarse cadence independent of any open conversation (and survives
 * process death / device reboot), and it delegates the actual work to
 * [WhiteNoiseAppState.sweepExpiredDisappearingMessages], which mirrors the
 * foreground sweep per group.
 *
 * The cadence is intentionally coarse ([SWEEP_INTERVAL_HOURS]) so the sweep
 * never becomes a hot loop or a battery drain — the engine owns the precise
 * expiry decision; Android only needs to nudge it periodically. WorkManager's
 * own minimum periodic interval (15 minutes) further guards against a hot loop.
 */
class DisappearingMessageSweepWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? WhiteNoiseApplication ?: return Result.success()
        return runCatching {
            app.appState.sweepExpiredDisappearingMessages()
            Result.success()
        }.getOrElse { error ->
            if (error is kotlin.coroutines.cancellation.CancellationException) throw error
            // Transient failures (offline relay round-trip, engine still
            // booting) just retry on WorkManager's backoff; the next coarse
            // tick would catch it anyway, so this is best-effort.
            if (BuildConfig.DEBUG) Log.w(TAG, "background sweep failed", error)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "DMSweepWorker"
        private const val UNIQUE_WORK_NAME = "disappearing_message_sweep"

        // Coarse on purpose: expiry is enforced precisely by the engine and by
        // the in-conversation sweep when a chat is open. The background pass
        // only needs to bound how long an expired message in a *closed*
        // conversation can linger, so an hourly cadence keeps decrypted media
        // and stale tray cards from outliving the retention window by much
        // while staying far off any hot path.
        private const val SWEEP_INTERVAL_HOURS = 1L

        /**
         * Enqueue the periodic sweep, keeping any already-scheduled instance so
         * app restarts don't reset its cadence. Safe to call from
         * [WhiteNoiseApplication.onCreate]; WorkManager persists the schedule
         * across process death and reboot.
         *
         * Best-effort: `WorkManager.getInstance` throws if the WorkManager
         * runtime isn't initialized (e.g. under a Robolectric unit test that
         * builds the real Application without the androidx.startup provider).
         * A missing background sweep must never crash app startup, so swallow
         * that — the worst case is the in-conversation sweep still enforces
         * expiry on open, and the next launch re-attempts the schedule.
         */
        fun schedule(context: Context) {
            runCatching {
                val request =
                    PeriodicWorkRequestBuilder<DisappearingMessageSweepWorker>(
                        SWEEP_INTERVAL_HOURS,
                        TimeUnit.HOURS,
                    ).build()
                WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                    UNIQUE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
            }.onFailure {
                if (BuildConfig.DEBUG) Log.w(TAG, "failed to schedule background sweep", it)
            }
        }
    }
}
