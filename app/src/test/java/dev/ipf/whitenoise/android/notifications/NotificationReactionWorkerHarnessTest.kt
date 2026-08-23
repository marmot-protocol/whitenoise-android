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
class NotificationReactionWorkerHarnessTest {
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
            val action = reactionAction()
            val requestId = UUID.randomUUID()
            harness.appState.workerTestHooks?.sendNotificationReaction = { _, _, _, _ ->
                NotificationReactionSendOutcome.Sent
            }
            harness.appState.workerTestHooks?.markNotificationMessageRead = { _, _, _ -> true }

            val result = harness.runReactionWorker(action, "🔥", requestId)

            assertTrue(result is ListenableWorker.Result.Success)
        }

    @Test
    fun doWork_retryableFailure_retriesUntilCap() =
        runBlocking {
            val action = reactionAction()
            val requestId = UUID.randomUUID()
            harness.appState.workerTestHooks?.sendNotificationReaction = { _, _, _, _ ->
                NotificationReactionSendOutcome.RetryableFailure
            }

            val first = harness.runReactionWorker(action, "🔥", requestId)
            val terminal = harness.runReactionWorker(action, "🔥", requestId)
            val third = harness.runReactionWorker(action, "🔥", requestId)

            assertTrue(first is ListenableWorker.Result.Retry)
            assertTrue(terminal is ListenableWorker.Result.Retry)
            assertTrue(third is ListenableWorker.Result.Failure)
        }

    @Test
    fun doWork_nonRetryableFailure_failsImmediately() =
        runBlocking {
            val action = reactionAction()
            harness.appState.workerTestHooks?.sendNotificationReaction = { _, _, _, _ ->
                NotificationReactionSendOutcome.NonRetryableFailure
            }

            val result = harness.runReactionWorker(action, "🔥", UUID.randomUUID())

            assertTrue(result is ListenableWorker.Result.Failure)
        }

    @Test
    fun doWork_appLockDefersReactionSend() =
        runBlocking {
            val action = reactionAction()
            val requestId = UUID.randomUUID()
            val sendCount = AtomicInteger(0)
            harness.appState.setAppLockScreenVisibleForWorkerTests(true)
            harness.appState.workerTestHooks?.sendNotificationReaction = { _, _, _, _ ->
                sendCount.incrementAndGet()
                NotificationReactionSendOutcome.Sent
            }

            val deferred = harness.runReactionWorker(action, "🔥", requestId)

            assertTrue(deferred is ListenableWorker.Result.Retry)
            assertEquals(0, sendCount.get())
        }

    private fun reactionAction(): NotificationAction =
        NotificationAction(
            kind = NotificationActionKind.REACT,
            target =
                NotificationTarget(
                    WorkerHarnessFixtures.ACCOUNT_REF,
                    WorkerHarnessFixtures.GROUP_ID_HEX,
                    WorkerHarnessFixtures.MESSAGE_ID_HEX,
                    NotificationTargetKind.MESSAGE,
                ),
            notificationTag = "${WorkerHarnessFixtures.ACCOUNT_REF}|group",
            notificationId = 21,
            reaction = "🔥",
        )

    private suspend fun WorkerTestHarness.runReactionWorker(
        action: NotificationAction,
        reaction: String,
        requestId: UUID,
        runAttemptCount: Int = 0,
    ): ListenableWorker.Result {
        val routingAction = action.copy(reaction = null)
        val encrypted =
            NotificationReplyCipher
                .create()
                .encrypt(reaction, requestId, routingAction)
        val input = NotificationReactionWorker.reactionInputData(routingAction, encrypted)
        return runWorker(NotificationReactionWorker::class.java, input, requestId, runAttemptCount)
    }
}
