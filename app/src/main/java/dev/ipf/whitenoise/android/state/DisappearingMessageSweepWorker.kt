package dev.ipf.whitenoise.android.state

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import dev.ipf.whitenoise.android.core.MarmotClient
import dev.ipf.whitenoise.android.core.MarmotClientRootGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Test seam for the existing account sweep. */
internal typealias PerformDisappearingMessageSweep = suspend (WhiteNoiseApplication) -> Unit

/** Test seam for the authoritative account preflight. */
internal typealias HasRetentionSweepAccount = suspend (WhiteNoiseApplication) -> Boolean

/** Only an authoritative, locally signed-in signing identity needs an expiry sweep. */
internal fun needsRetentionSweep(rows: List<AccountSummaryFfi>): Boolean = rows.any { it.isSignedInSigningAccount() }

/**
 * Reads MDK's account inventory before starting its notification and relay runtime. Reuse an
 * already-open client when the app is active; a cold worker opens a temporary unstarted client
 * and closes it before returning. The root lease excludes foreground construction, which is
 * rechecked after waiting. A failed read propagates so WorkManager retries instead of mistaking
 * an unknown account set for an empty one.
 */
internal suspend fun needsRetentionSweep(app: WhiteNoiseApplication): Boolean {
    return withContext(Dispatchers.IO) {
        MarmotClientRootGate.withLease {
            val existing = app.initializedAppState()?.retentionSweepRuntimeOrNull()
            if (existing != null) return@withLease needsRetentionSweep(existing.listAccounts())
            val temporary = MarmotClient(app.applicationContext).marmot
            try {
                needsRetentionSweep(temporary.listAccounts())
            } finally {
                withContext(NonCancellable) { temporary.shutdownAndClose() }
            }
        }
    }
}

/**
 * Periodic background sweep for expired messages in closed conversations (#745).
 * A cold worker checks MDK's account inventory before starting notification or relay work,
 * then delegates eligible accounts to [WhiteNoiseAppState.sweepExpiredDisappearingMessages].
 * WorkManager's hourly schedule survives process death and reboot.
 */
class DisappearingMessageSweepWorker : CoroutineWorker {
    private val sweepOverride: PerformDisappearingMessageSweep?
    private val accountOverride: HasRetentionSweepAccount?

    /** WorkManager entry point using the authoritative MDK preflight and app-owned sweep. */
    constructor(
        appContext: Context,
        params: WorkerParameters,
    ) : this(appContext, params, null, null)

    /** Test seam for account reads and sweep execution without opening the native root. */
    internal constructor(
        appContext: Context,
        params: WorkerParameters,
        sweepOverride: PerformDisappearingMessageSweep?,
        accountOverride: HasRetentionSweepAccount? = null,
    ) : super(appContext, params) {
        this.sweepOverride = sweepOverride
        this.accountOverride = accountOverride
    }

    /** Skips ineligible inventories, retries uncertain reads or sweep failures, and preserves cancellation. */
    override suspend fun doWork(): Result {
        val app = applicationContext as? WhiteNoiseApplication ?: return Result.success()
        return runCatching {
            withContext(Dispatchers.Main.immediate) {
                if ((accountOverride ?: ::needsRetentionSweep)(app)) {
                    val override = sweepOverride
                    if (override != null) {
                        override(app)
                    } else {
                        app.appState.sweepExpiredDisappearingMessages()
                    }
                }
            }
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
         * app restarts don't reset its cadence. WorkManager persists the schedule
         * across process death and reboot. Initialization and enqueue failures
         * propagate to the application-level gate so a later handoff can retry.
         */
        fun schedule(context: Context): Operation {
            val request =
                PeriodicWorkRequestBuilder<DisappearingMessageSweepWorker>(
                    SWEEP_INTERVAL_HOURS,
                    TimeUnit.HOURS,
                ).build()
            return WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
