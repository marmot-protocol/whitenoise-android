package dev.ipf.whitenoise.android.state

import androidx.work.ListenableWorker
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import dev.ipf.whitenoise.android.work.WorkerTestHarness
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

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

    @Test
    @Suppress("TooGenericExceptionThrown")
    fun doWork_permanentFailure_stillRetriesAtHighAttemptCount() =
        runBlocking {
            // Current production behavior: every non-cancellation Throwable maps to
            // Result.retry with no terminal path, even for permanent failures.
            harness.appState.workerTestHooks?.sweepExpiredDisappearingMessages = {
                throw RuntimeException("permanent configuration bug")
            }

            val result =
                harness.runWorker(
                    DisappearingMessageSweepWorker::class.java,
                    androidx.work.workDataOf(),
                    runAttemptCount = 50,
                )

            assertTrue(result is ListenableWorker.Result.Retry)
        }

    @Test
    fun doWork_cancellationPropagates() {
        harness.appState.workerTestHooks?.sweepExpiredDisappearingMessages = {
            throw CancellationException("sweep cancelled")
        }

        val thrown =
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                runBlocking {
                    harness.runWorker(
                        DisappearingMessageSweepWorker::class.java,
                        androidx.work.workDataOf(),
                    )
                }
            }
        // CoroutineWorker cancels its ListenableFuture; Future.get() surfaces that as
        // java.util.concurrent.CancellationException rather than Result.retry.
        assertTrue(thrown.message?.contains("cancelled", ignoreCase = true) == true)
    }
}
