package dev.ipf.whitenoise.android.notifications

import androidx.work.ListenableWorker
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import dev.ipf.whitenoise.android.work.WorkerHarnessFixtures
import dev.ipf.whitenoise.android.work.WorkerTestHarness
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = WhiteNoiseApplication::class, sdk = [36])
class NotificationMarkReadWorkerHarnessTest {
    private lateinit var harness: WorkerTestHarness

    @Before
    fun setUp() {
        val application = RuntimeEnvironment.getApplication() as WhiteNoiseApplication
        harness = WorkerTestHarness(application)
        harness.appState.workerTestHooks?.ensureNotificationRuntimeStarted = {}
    }

    @After
    fun tearDown() {
        harness.tearDown()
    }

    @Test
    fun doWork_successPath() =
        runBlocking {
            val action = markReadAction()
            harness.appState.workerTestHooks?.markNotificationMessageRead = { _, _, _ -> true }

            val result = harness.runMarkReadWorker(action)

            assertTrue(result is ListenableWorker.Result.Success)
        }

    @Test
    fun doWork_markReadFalse_retriesThenFails() =
        runBlocking {
            val action = markReadAction()
            val requestId = UUID.randomUUID()
            harness.appState.workerTestHooks?.markNotificationMessageRead = { _, _, _ -> false }

            val first = harness.runMarkReadWorker(action, requestId)
            val second = harness.runMarkReadWorker(action, requestId)
            val terminal = harness.runMarkReadWorker(action, requestId)

            assertTrue(first is ListenableWorker.Result.Retry)
            assertTrue(second is ListenableWorker.Result.Retry)
            assertTrue(terminal is ListenableWorker.Result.Failure)
        }

    @Test
    fun doWork_malformedInput_failsWithoutCallingAppState() =
        runBlocking {
            val markCount = AtomicInteger(0)
            harness.appState.workerTestHooks?.markNotificationMessageRead = { _, _, _ ->
                markCount.incrementAndGet()
                true
            }

            val result =
                harness.runWorker(
                    NotificationMarkReadWorker::class.java,
                    androidx.work.workDataOf("wrong" to "kind"),
                )

            assertTrue(result is ListenableWorker.Result.Failure)
            assertEquals(0, markCount.get())
        }

    @Test
    fun doWork_appLockDefersMarkRead() =
        runBlocking {
            val action = markReadAction()
            val requestId = UUID.randomUUID()
            val markCount = AtomicInteger(0)
            harness.appState.setAppLockScreenVisibleForWorkerTests(true)
            harness.appState.workerTestHooks?.markNotificationMessageRead = { _, _, _ ->
                markCount.incrementAndGet()
                true
            }

            val deferred = harness.runMarkReadWorker(action, requestId)

            assertTrue(deferred is ListenableWorker.Result.Retry)
            assertEquals(0, markCount.get())
        }

    private fun markReadAction(): NotificationAction =
        NotificationAction(
            kind = NotificationActionKind.MARK_READ,
            target =
                NotificationTarget(
                    WorkerHarnessFixtures.ACCOUNT_REF,
                    WorkerHarnessFixtures.GROUP_ID_HEX,
                    WorkerHarnessFixtures.MESSAGE_ID_HEX,
                    NotificationTargetKind.MESSAGE,
                ),
            notificationTag = "${WorkerHarnessFixtures.ACCOUNT_REF}|group",
            notificationId = 31,
        )

    private suspend fun WorkerTestHarness.runMarkReadWorker(
        action: NotificationAction,
        requestId: UUID = UUID.randomUUID(),
        runAttemptCount: Int = 0,
    ): ListenableWorker.Result {
        val input = NotificationActionWorkData.encode(action)
        return runWorker(NotificationMarkReadWorker::class.java, input, requestId, runAttemptCount)
    }
}
