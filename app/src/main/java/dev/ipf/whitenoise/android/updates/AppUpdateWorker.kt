package dev.ipf.whitenoise.android.updates

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.ipf.whitenoise.android.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

class AppUpdateWorker : CoroutineWorker {
    private val repository: AppUpdateRepository

    constructor(
        appContext: Context,
        params: WorkerParameters,
    ) : this(appContext, params, AppUpdateRepository(appContext))

    /** Test seam mirroring the sweep worker: inject the repository to control the update-check boundary. */
    internal constructor(
        appContext: Context,
        params: WorkerParameters,
        repository: AppUpdateRepository,
    ) : super(appContext, params) {
        this.repository = repository
    }

    override suspend fun doWork(): Result {
        if (shouldSkipForMeteredDataSaver(applicationContext)) return Result.success()
        return try {
            val info = repository.refresh()
            if (info.shouldShowBanner && AppUpdateForegroundState.shouldPostBackgroundNotification()) {
                AppUpdateNotifier(applicationContext).show(info)
            }
            Result.success()
        } catch (error: IOException) {
            logRefreshFailure()
            resultForRefreshFailure(error)
        } catch (error: RuntimeException) {
            logRefreshFailure()
            resultForRefreshFailure(error)
        }
    }

    private fun logRefreshFailure() {
        Log.w(TAG, "update_check_failed")
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "darkmatter-zapstore-update-check"
        private const val TAG = "AppUpdateWorker"

        internal fun resultForRefreshFailure(error: Throwable): Result =
            when (error) {
                is CancellationException -> throw error
                is IOException -> Result.retry()
                is RuntimeException -> Result.failure()
                else -> Result.failure()
            }

        fun schedule(context: Context): Operation? {
            // No update polling on builds that don't self-update — the
            // distributing store (e.g. Google Play) owns updates there.
            if (!BuildConfig.SELF_UPDATE_ENABLED) return null
            val request =
                PeriodicWorkRequestBuilder<AppUpdateWorker>(24, TimeUnit.HOURS)
                    .setConstraints(
                        Constraints
                            .Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    ).build()
            return WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        private fun shouldSkipForMeteredDataSaver(context: Context): Boolean {
            val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
            return manager.isActiveNetworkMetered &&
                manager.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        }
    }
}
