package dev.ipf.darkmatter.updates

import android.content.Context
import android.net.ConnectivityManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.IOException
import java.util.concurrent.TimeUnit

class AppUpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (shouldSkipForMeteredDataSaver(applicationContext)) return Result.success()
        return try {
            val repository = AppUpdateRepository(applicationContext)
            val info = repository.refresh()
            if (info.shouldShowBanner) {
                AppUpdateNotifier(applicationContext).show(info)
            }
            Result.success()
        } catch (error: IOException) {
            Result.retry()
        } catch (error: RuntimeException) {
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "darkmatter-zapstore-update-check"

        fun schedule(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<AppUpdateWorker>(24, TimeUnit.HOURS)
                    .setConstraints(
                        Constraints
                            .Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    ).build()
            val workManager =
                try {
                    WorkManager.getInstance(context.applicationContext)
                } catch (_: IllegalStateException) {
                    // Robolectric/unit-test application contexts may not install WorkManager's
                    // initializer. The real app process gets it from the manifest provider.
                    return
                }
            workManager.enqueueUniquePeriodicWork(
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
