package dev.ipf.whitenoise.android.state

import androidx.work.ListenableWorker
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
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(application = WhiteNoiseApplication::class, sdk = [36])
class DisappearingMessageSweepWorkerHarnessTest {
    private lateinit var harness: WorkerTestHarness

    @Before
    fun setUp() {
        val application = RuntimeEnvironment.getApplication() as WhiteNoiseApplication
        harness = WorkerTestHarness(application)
    }

    @After
    fun tearDown() {
        harness.tearDown()
    }

    @Test
    fun doWork_successPath() =
        runBlocking {
            val swept = AtomicBoolean(false)
            harness.appState.workerTestHooks?.sweepExpiredDisappearingMessages = { swept.set(true) }

            val result =
                harness.runWorker(
                    DisappearingMessageSweepWorker::class.java,
                    androidx.work.workDataOf(),
                )

            assertTrue(result is ListenableWorker.Result.Success)
            assertTrue(swept.get())
        }

    @Test
    fun doWork_failureRetries() =
        runBlocking {
            harness.appState.workerTestHooks?.sweepExpiredDisappearingMessages = {
                throw java.io.IOException("offline")
            }

            val result =
                harness.runWorker(
                    DisappearingMessageSweepWorker::class.java,
                    androidx.work.workDataOf(),
                    runAttemptCount = 0,
                )

            assertTrue(result is ListenableWorker.Result.Retry)
        }
}
