package dev.ipf.whitenoise.android.updates

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import dev.ipf.whitenoise.android.work.WorkerTestHarness
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = WhiteNoiseApplication::class, sdk = [36])
class AppUpdateWorkerHarnessTest {
    private lateinit var harness: WorkerTestHarness

    @Before
    fun setUp() {
        val application = RuntimeEnvironment.getApplication() as WhiteNoiseApplication
        harness = WorkerTestHarness(application)
        harness.context
            .getSharedPreferences("darkmatter_app_updates", android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @After
    fun tearDown() {
        harness.tearDown()
    }

    @Test
    fun doWork_successPath() =
        runBlocking {
            val result =
                runAppUpdateWorker { context ->
                    AppUpdateRepository(
                        context,
                        fetchLatestRelease = { _, _ -> ZapstoreLatestRelease("2026.3.0", 1) },
                        currentTimeMillis = { 1_000L },
                    )
                }

            assertTrue(result is ListenableWorker.Result.Success)
        }

    @Test
    fun doWork_ioFailureRetries() =
        runBlocking {
            val result =
                runAppUpdateWorker { context ->
                    AppUpdateRepository(
                        context,
                        fetchLatestRelease = { _, _ -> throw IOException("network down") },
                        currentTimeMillis = { 1_000L },
                    )
                }

            assertTrue(result is ListenableWorker.Result.Retry)
        }

    @Test
    fun doWork_runtimeFailureFailsWithoutRetry() =
        runBlocking {
            val result =
                runAppUpdateWorker { context ->
                    AppUpdateRepository(
                        context,
                        fetchLatestRelease = { _, _ -> throw IllegalStateException("parse bug") },
                        currentTimeMillis = { 1_000L },
                    )
                }

            assertTrue(result is ListenableWorker.Result.Failure)
        }

    private suspend fun runAppUpdateWorker(repository: (Context) -> AppUpdateRepository): ListenableWorker.Result {
        val workerFactory =
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker? =
                    if (workerClassName == AppUpdateWorker::class.java.name) {
                        AppUpdateWorker(appContext, workerParameters, repository(appContext))
                    } else {
                        null
                    }
            }
        return harness.runWorker(
            AppUpdateWorker::class.java,
            androidx.work.workDataOf(),
            workerFactory = workerFactory,
        )
    }
}
