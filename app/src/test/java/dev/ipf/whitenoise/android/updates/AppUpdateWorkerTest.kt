package dev.ipf.whitenoise.android.updates

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkInfo
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import dev.ipf.whitenoise.android.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkInfo
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** Executes [AppUpdateWorker.doWork] through the WorkManager test harness for every reachable result branch. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppUpdateWorkerTest {
    private lateinit var appContext: Context

    /** Gives each worker a clean scheduling history without resetting process-wide admission mid-request. */
    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        appContext.getSharedPreferences("darkmatter_app_updates", Context.MODE_PRIVATE).edit().clear().commit()
        WorkManagerTestInitHelper.initializeTestWorkManager(appContext)
    }

    @Test
    fun doWorkSucceedsWhenNoReleaseIsPublished() =
        runTest {
            val worker = buildWorker(fetchLatestRelease = { _, _ -> null })

            assertEquals(Result.success(), worker.doWork())
        }

    @Test
    fun doWorkSucceedsWhenAnUpdateIsAvailableInBackground() =
        runTest {
            val worker =
                buildWorker(
                    fetchLatestRelease = { _, _ ->
                        ZapstoreLatestRelease(version = "9999.12.31", releasesBehind = 4)
                    },
                )

            assertEquals(Result.success(), worker.doWork())
        }

    @Test
    fun doWorkRetriesNetworkFailures() =
        runTest {
            val worker = buildWorker(fetchLatestRelease = { _, _ -> throw IOException("offline") })

            assertEquals(Result.retry(), worker.doWork())
        }

    @Test
    fun doWorkFailsTerminalRuntimeFailures() =
        runTest {
            val worker = buildWorker(fetchLatestRelease = { _, _ -> throw IllegalStateException("malformed listing") })

            assertEquals(Result.failure(), worker.doWork())
        }

    @Test
    fun doWorkPropagatesCancellation() =
        runTest {
            val worker = buildWorker(fetchLatestRelease = { _, _ -> throw CancellationException("stopped") })

            val outcome = runCatching { worker.doWork() }

            assertTrue(
                "cancellation must propagate instead of becoming a result",
                outcome.exceptionOrNull() is CancellationException,
            )
        }

    @Test
    fun refreshFailureClassificationStaysCancellationAwareAndBounded() {
        assertEquals(Result.retry(), AppUpdateWorker.resultForRefreshFailure(IOException("offline")))
        assertEquals(Result.failure(), AppUpdateWorker.resultForRefreshFailure(IllegalArgumentException("bad json")))
        assertTrue(
            runCatching { AppUpdateWorker.resultForRefreshFailure(CancellationException("stopped")) }
                .exceptionOrNull() is CancellationException,
        )
    }

    /** Store-managed builds must skip even a previously enqueued worker after a distribution change. */
    @Test
    fun distributionDeniedWorkerDoesNotFetch() =
        runTest {
            var fetches = 0
            val worker =
                buildWorker(
                    enabled = false,
                    fetchLatestRelease = { _, _ ->
                        fetches++
                        null
                    },
                )
            assertEquals(Result.success(), worker.doWork())
            assertEquals(0, fetches)
        }

    /** The real BuildConfig default is checked in both hosted flavor suites. */
    @Test
    fun productionDistributionDefaultControlsWorkerFetches() =
        runTest {
            var fetches = 0
            val repository =
                AppUpdateRepository(
                    appContext,
                    fetchLatestRelease = { _, _ ->
                        fetches++
                        null
                    },
                    automaticNetworkAllowed = { true },
                    ioDispatcher = Dispatchers.Unconfined,
                )
            assertEquals(Result.success(), buildWorker(repository).doWork())
            assertEquals(if (BuildConfig.SELF_UPDATE_ENABLED) 1 else 0, fetches)
        }

    /** A worker retry and rapid lifecycle retries observe the same persisted failure deadline. */
    @Test
    fun workerAndLifecycleShareFailureCooldown() =
        runTest {
            var now = 1_700_000_000_000L
            var fetches = 0
            val repository =
                AppUpdateRepository(
                    appContext,
                    fetchLatestRelease = { _, _ ->
                        fetches++
                        throw IOException("offline")
                    },
                    currentTimeMillis = { now },
                    selfUpdateEnabled = true,
                    automaticNetworkAllowed = { true },
                    ioDispatcher = Dispatchers.Unconfined,
                )
            val worker = buildWorker(repository)
            assertEquals(Result.retry(), worker.doWork())
            assertEquals(Result.success(), worker.doWork())
            assertEquals(null, repository.refreshAutomatically())
            assertEquals(1, fetches)
            now += AppUpdateRepository.FAILURE_COOLDOWN_MS
            assertEquals(Result.retry(), worker.doWork())
            assertEquals(2, fetches)
        }

    /** Distinct worker and lifecycle repositories join the same active fetch. */
    @Test
    fun workerJoinsLifecycleFetchInsteadOfStartingAnother() =
        runTest {
            val release = CompletableDeferred<Unit>()
            var fetches = 0
            val repository =
                AppUpdateRepository(
                    appContext,
                    fetchLatestRelease = { _, _ ->
                        fetches++
                        release.await()
                        null
                    },
                    selfUpdateEnabled = true,
                    automaticNetworkAllowed = { true },
                    ioDispatcher = Dispatchers.Unconfined,
                )
            val lifecycle = async(start = CoroutineStart.UNDISPATCHED) { repository.refreshAutomatically() }
            val worker =
                buildWorker(
                    fetchLatestRelease = { _, _ ->
                        fetches++
                        null
                    },
                )
            val work = async(start = CoroutineStart.UNDISPATCHED) { worker.doWork() }
            assertEquals(1, fetches)
            release.complete(Unit)
            assertTrue(lifecycle.await() != null)
            assertEquals(Result.success(), work.await())
            assertEquals(1, fetches)
        }

    /** Actual Android network policy denies offline and restricted metered requests, then allows Wi-Fi. */
    @Suppress("DEPRECATION") // Robolectric's network fixture uses NetworkInfo to drive activeNetwork and metering.
    @Test
    fun automaticNetworkPolicyIsSharedByWorkerAndLifecycle() =
        runTest {
            val manager = appContext.getSystemService(ConnectivityManager::class.java)
            val shadow = shadowOf(manager)
            var fetches = 0
            val repository =
                AppUpdateRepository(
                    appContext,
                    fetchLatestRelease = { _, _ ->
                        fetches++
                        null
                    },
                    selfUpdateEnabled = true,
                    ioDispatcher = Dispatchers.Unconfined,
                )
            val worker = buildWorker(repository)
            shadow.setActiveNetworkInfo(null)
            assertEquals(Result.success(), worker.doWork())
            assertEquals(null, repository.refreshAutomatically())
            shadow.setActiveNetworkInfo(
                ShadowNetworkInfo.newInstance(
                    NetworkInfo.DetailedState.CONNECTED,
                    ConnectivityManager.TYPE_MOBILE,
                    0,
                    true,
                    true,
                ),
            )
            shadow.setRestrictBackgroundStatus(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED)
            assertEquals(Result.success(), worker.doWork())
            assertEquals(null, repository.refreshAutomatically())
            assertEquals(0, fetches)
            shadow.setActiveNetworkInfo(
                ShadowNetworkInfo.newInstance(
                    NetworkInfo.DetailedState.CONNECTED,
                    ConnectivityManager.TYPE_WIFI,
                    0,
                    true,
                    true,
                ),
            )
            assertEquals(Result.success(), worker.doWork())
            assertEquals(1, fetches)
        }

    /** Existing worker outcome tests run as a self-updating, network-eligible fixture in either flavor. */
    private fun buildWorker(
        fetchLatestRelease: suspend (String, String?) -> ZapstoreLatestRelease?,
        enabled: Boolean = true,
    ): AppUpdateWorker =
        buildWorker(
            AppUpdateRepository(
                appContext,
                fetchLatestRelease = fetchLatestRelease,
                selfUpdateEnabled = enabled,
                automaticNetworkAllowed = { true },
                ioDispatcher = Dispatchers.Unconfined,
            ),
        )

    /** Exercises the production WorkManager entry point with a controlled repository boundary. */
    private fun buildWorker(repository: AppUpdateRepository): AppUpdateWorker =
        TestListenableWorkerBuilder
            .from<AppUpdateWorker>(appContext, AppUpdateWorker::class.java)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker? {
                        if (workerClassName != AppUpdateWorker::class.java.name) return null
                        return AppUpdateWorker(appContext, workerParameters, repository)
                    }
                },
            ).build()
}
