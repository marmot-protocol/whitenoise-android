package dev.ipf.whitenoise.android.notifications

import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.testing.WorkManagerTestInitHelper
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
class NotificationReplyWorkerHarnessTest {
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
    fun doWork_successPath_marksCompletedAndDoesNotResendOnRerun() =
        runBlocking {
            val action = replyAction()
            val requestId = UUID.randomUUID()
            val sendCount = AtomicInteger(0)
            harness.appState.workerTestHooks?.sendNotificationReply = { _, _, _, _, _, _, _ ->
                sendCount.incrementAndGet()
                NotificationReplySendOutcome.Sent
            }
            harness.appState.workerTestHooks?.markNotificationMessageRead = { _, _, _ -> true }

            val first = harness.runReplyWorker(action, "hello", requestId, legacyPlaintext = true)
            val second = harness.runReplyWorker(action, "hello", requestId, legacyPlaintext = true)

            assertTrue(first is ListenableWorker.Result.Success)
            assertTrue(second is ListenableWorker.Result.Success)
            assertEquals(1, sendCount.get())
        }

    @Test
    fun doWork_identicalTextWithDistinctRequestIds_sendsTwice() =
        runBlocking {
            val action = replyAction()
            val sendCount = AtomicInteger(0)
            harness.appState.workerTestHooks?.sendNotificationReply = { _, _, _, _, _, _, _ ->
                sendCount.incrementAndGet()
                NotificationReplySendOutcome.Sent
            }
            harness.appState.workerTestHooks?.markNotificationMessageRead = { _, _, _ -> true }

            val first = harness.runReplyWorker(action, "same reply", UUID.randomUUID())
            val second = harness.runReplyWorker(action, "same reply", UUID.randomUUID())

            assertTrue(first is ListenableWorker.Result.Success)
            assertTrue(second is ListenableWorker.Result.Success)
            assertEquals(2, sendCount.get())
        }

    @Test
    fun doWork_retryableSendFailure_retriesUntilAttemptCap() =
        runBlocking {
            val action = replyAction()
            val requestId = UUID.randomUUID()
            harness.appState.workerTestHooks?.sendNotificationReply = { _, _, _, _, _, _, _ ->
                NotificationReplySendOutcome.RetryableFailure
            }

            val first = harness.runReplyWorker(action, "retry me", requestId)
            val second = harness.runReplyWorker(action, "retry me", requestId)
            val terminal = harness.runReplyWorker(action, "retry me", requestId)

            assertTrue(first is ListenableWorker.Result.Retry)
            assertTrue(second is ListenableWorker.Result.Retry)
            assertTrue(terminal is ListenableWorker.Result.Failure)
        }

    @Test
    fun doWork_nonRetryableSendFailure_failsWithoutRetry() =
        runBlocking {
            val action = replyAction()
            harness.appState.workerTestHooks?.sendNotificationReply = { _, _, _, _, _, _, _ ->
                NotificationReplySendOutcome.NonRetryableFailure
            }

            val result = harness.runReplyWorker(action, "bad target", UUID.randomUUID())

            assertTrue(result is ListenableWorker.Result.Failure)
        }

    @Test
    fun doWork_abandonedFailureOutcome_resurfacesFailureInsteadOfSuccess() =
        runBlocking {
            val action = replyAction()
            val requestId = UUID.randomUUID()
            val completionKey = NotificationReplyWorker.notificationReplyCompletionKey(requestId)
            val completionStore = NotificationReplyCompletionStore.create(harness.context)
            completionStore.markAbandoned(completionKey, NotificationReplyAbandonedOutcome.Failure)

            val sendCount = AtomicInteger(0)
            harness.appState.workerTestHooks?.sendNotificationReply = { _, _, _, _, _, _, _ ->
                sendCount.incrementAndGet()
                NotificationReplySendOutcome.Sent
            }

            val result = harness.runReplyWorker(action, "lost reply", requestId)

            assertTrue(result is ListenableWorker.Result.Failure)
            assertEquals(0, sendCount.get())
        }

    @Test
    fun enqueueRequest_requiresConnectedNetworkBeforeWorkRuns() =
        runBlocking {
            val action = replyAction()
            val requestId = UUID.randomUUID()
            val encrypted =
                NotificationReplyCipher
                    .create()
                    .encrypt("offline reply", requestId, action)
            val request = NotificationReplyWorker.notificationReplyRequest(action, requestId, encrypted)

            assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)

            harness.workManager
                .enqueue(request)
                .result
                .get()
            val blocked = checkNotNull(harness.workManager.getWorkInfoById(request.id).get())
            assertTrue(blocked.state == WorkInfo.State.ENQUEUED || blocked.state == WorkInfo.State.BLOCKED)

            val testDriver = WorkManagerTestInitHelper.getTestDriver(harness.context)!!
            testDriver.setAllConstraintsMet(request.id)

            val running = checkNotNull(harness.workManager.getWorkInfoById(request.id).get())
            assertTrue(
                running.state == WorkInfo.State.RUNNING ||
                    running.state == WorkInfo.State.SUCCEEDED ||
                    running.state == WorkInfo.State.FAILED,
            )
        }

    @Test
    fun doWork_malformedInput_failsClosed() =
        runBlocking {
            val result =
                harness.runWorker(
                    NotificationReplyWorker::class.java,
                    androidx.work.workDataOf("reply" to "orphan text"),
                )

            assertTrue(result is ListenableWorker.Result.Failure)
        }

    @Test
    fun doWork_throwableFromSend_retriesThenCaps() =
        runBlocking {
            val action = replyAction()
            val requestId = UUID.randomUUID()
            harness.appState.workerTestHooks?.sendNotificationReply = { _, _, _, _, _, _, _ ->
                throw java.io.IOException("relay offline")
            }

            val first = harness.runReplyWorker(action, "boom", requestId)
            val terminal = harness.runReplyWorker(action, "boom", requestId)
            val third = harness.runReplyWorker(action, "boom", requestId)

            assertTrue(first is ListenableWorker.Result.Retry)
            assertTrue(terminal is ListenableWorker.Result.Retry)
            assertTrue(third is ListenableWorker.Result.Failure)
        }

    private fun replyAction(): NotificationAction =
        NotificationAction(
            kind = NotificationActionKind.REPLY,
            target =
                NotificationTarget(
                    WorkerHarnessFixtures.ACCOUNT_REF,
                    WorkerHarnessFixtures.GROUP_ID_HEX,
                    WorkerHarnessFixtures.MESSAGE_ID_HEX,
                    NotificationTargetKind.MESSAGE,
                ),
            notificationTag = "${WorkerHarnessFixtures.ACCOUNT_REF}|group",
            notificationId = 17,
        )

    private suspend fun WorkerTestHarness.runReplyWorker(
        action: NotificationAction,
        reply: String,
        requestId: UUID,
        runAttemptCount: Int = 0,
        legacyPlaintext: Boolean = false,
    ): ListenableWorker.Result {
        val input =
            if (legacyPlaintext) {
                androidx.work.Data
                    .Builder()
                    .putAll(NotificationActionWorkData.encode(action))
                    .putString("reply", reply)
                    .build()
            } else {
                val encrypted =
                    NotificationReplyCipher
                        .create()
                        .encrypt(reply, requestId, action)
                NotificationReplyWorker.notificationReplyInputData(action, encrypted)
            }
        return runWorker(NotificationReplyWorker::class.java, input, requestId, runAttemptCount)
    }
}
