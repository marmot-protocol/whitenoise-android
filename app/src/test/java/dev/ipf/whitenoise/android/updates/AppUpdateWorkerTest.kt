package dev.ipf.whitenoise.android.updates

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** Executes [AppUpdateWorker.doWork] through the WorkManager test harness for every reachable result branch. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppUpdateWorkerTest {
    private lateinit var appContext: Context

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
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

    private fun buildWorker(fetchLatestRelease: suspend (String, String?) -> ZapstoreLatestRelease?): AppUpdateWorker {
        val repository = AppUpdateRepository(appContext, fetchLatestRelease = fetchLatestRelease)
        return TestListenableWorkerBuilder
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
}
