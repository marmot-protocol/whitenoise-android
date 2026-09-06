package dev.ipf.whitenoise.android.notifications

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ListenableWorker.Result
import androidx.work.NetworkType
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.SendMaintenanceDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi
import dev.ipf.whitenoise.android.state.NotificationBootstrapTestFixture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Executes [NotificationReplyWorker.doWork] through the WorkManager test
 * harness against a real app state with a controllable FFI boundary, pinning
 * the worker-boundary contracts behind the closed duplicate-send, attempt-cap,
 * failure-classification, identical-text, dropped-reply, and
 * network-constraint reports.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationReplyWorkerTest {
    private lateinit var realContext: Context
    private lateinit var cipher: NotificationReplyCipher

    @Volatile
    private var sendFailure: Throwable? = null

    @Volatile
    private var sendSummary: SendSummaryFfi = sentSummary()

    private lateinit var fixture: NotificationBootstrapTestFixture
    private lateinit var workerContext: NotificationWorkerTestApplication

    @Before
    fun setUp() {
        realContext = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(realContext)
        cipher = seedNotificationReplyCipherForTests()
        fixture =
            NotificationBootstrapTestFixture(
                context = realContext,
                accounts = listOf(signingAccount()),
                onSendText = { _, _, _ ->
                    sendFailure?.let { throw it }
                    sendSummary
                },
            )
        workerContext = NotificationWorkerTestApplication(realContext, fixture.appState)
    }

    @Test
    fun doWorkFailsWhenTheActionIsMalformed() =
        runTest {
            val worker = buildWorker(inputData = Data.EMPTY)

            assertEquals(Result.failure(), worker.doWork())
            assertEquals(0, fixture.sendTextCalls.get())
        }

    @Test
    fun doWorkFailsWhenTheReplyPayloadIsMalformed() =
        runTest {
            val worker = buildWorker(inputData = NotificationActionWorkData.encode(replyAction()))

            assertEquals(Result.failure(), worker.doWork())
            assertEquals(0, fixture.sendTextCalls.get())
        }

    @Test
    fun blankRepliesSucceedWithoutSending() =
        runTest {
            val workId = UUID.randomUUID()
            val worker = buildWorker(inputData = encryptedInput(workId, text = "   "), id = workId)

            assertEquals(Result.success(), worker.doWork())
            assertEquals(0, fixture.sendTextCalls.get())
        }

    @Test
    fun aCompletedReplyReplaysAsSuccessWithoutASecondSend() =
        runTest {
            fixture.bootstrap()
            val workId = UUID.randomUUID()
            NotificationReplyCompletionStore
                .create(realContext)
                .markCompleted(NotificationReplyWorker.notificationReplyCompletionKey(workId))
            val worker = buildWorker(inputData = encryptedInput(workId), id = workId)

            val result = pumpingMainLooper { worker.doWork() }

            assertEquals(Result.success(), result)
            assertEquals("a replayed completion must never send again", 0, fixture.sendTextCalls.get())
            assertTrue("the replay still finishes the mark-read cleanup", fixture.markReadCalls.get() >= 1)
        }

    @Test
    fun anAbandonedFailureIsResurfacedTerminally() =
        runTest {
            val workId = UUID.randomUUID()
            NotificationReplyCompletionStore
                .create(realContext)
                .markAbandoned(
                    NotificationReplyWorker.notificationReplyCompletionKey(workId),
                    NotificationReplyAbandonedOutcome.Failure,
                )
            val worker = buildWorker(inputData = encryptedInput(workId), id = workId)

            val result = pumpingMainLooper { worker.doWork() }

            assertEquals(Result.failure(), result)
            assertEquals(0, fixture.sendTextCalls.get())
        }

    @Test
    fun encryptedRepliesDeferBehindTheAppLock() =
        runTest {
            fixture.appState.setAppLockScreenVisibleForTest(true)
            val workId = UUID.randomUUID()
            val worker = buildWorker(inputData = encryptedInput(workId), id = workId)

            assertEquals(Result.retry(), worker.doWork())
            assertEquals(0, fixture.sendTextCalls.get())
        }

    @Test
    fun legacyPlaintextRepliesFailImmediatelyBehindTheAppLock() =
        runTest {
            fixture.appState.setAppLockScreenVisibleForTest(true)
            val worker = buildWorker(inputData = legacyPlaintextInput("hello"))

            assertEquals(Result.failure(), worker.doWork())
            assertEquals(0, fixture.sendTextCalls.get())
        }

    @Test
    fun legacyPlaintextRepliesStillSendOnce() =
        runTest {
            fixture.bootstrap()
            val worker = buildWorker(inputData = legacyPlaintextInput("hello"))

            val result = pumpingMainLooper { worker.doWork() }

            assertEquals(Result.success(), result)
            assertEquals(1, fixture.sendTextCalls.get())
        }

    @Test
    fun theDurableAttemptCapFailsTerminallyWithAnAbandonMarker() =
        runTest {
            val workId = UUID.randomUUID()
            val retryStore = NotificationActionRetryStore.create(realContext)
            repeat(3) { retryStore.recordOperationFailureAttempt(workId.toString()) }
            val worker = buildWorker(inputData = encryptedInput(workId), id = workId)

            val result = pumpingMainLooper { worker.doWork() }

            assertEquals(Result.failure(), result)
            assertEquals(0, fixture.sendTextCalls.get())
            assertEquals(
                "giving up must persist a durable failure marker for crash recovery",
                NotificationReplyAbandonedOutcome.Failure,
                NotificationReplyCompletionStore
                    .create(realContext)
                    .abandonedOutcome(NotificationReplyWorker.notificationReplyCompletionKey(workId)),
            )
        }

    @Test
    fun aSentReplySucceedsMarksCompletionAndMarksTheSourceRead() =
        runTest {
            fixture.bootstrap()
            val workId = UUID.randomUUID()
            val worker = buildWorker(inputData = encryptedInput(workId), id = workId)

            val result = pumpingMainLooper { worker.doWork() }

            assertEquals(Result.success(), result)
            assertEquals(1, fixture.sendTextCalls.get())
            assertTrue(
                "the durable completion marker must outlive the worker",
                NotificationReplyCompletionStore
                    .create(realContext)
                    .isCompleted(NotificationReplyWorker.notificationReplyCompletionKey(workId)),
            )
            assertTrue("a sent reply must mark the source message read", fixture.markReadCalls.get() >= 1)
        }

    @Test
    fun anAcceptedPendingReplyIsCompletedNotRetried() =
        runTest {
            fixture.bootstrap()
            sendSummary = sentSummary(acceptDisposition = SendAcceptDispositionFfi.ACCEPTED_PENDING)
            val workId = UUID.randomUUID()
            val worker = buildWorker(inputData = encryptedInput(workId), id = workId)

            val result = pumpingMainLooper { worker.doWork() }

            assertEquals(Result.success(), result)
            assertTrue(
                "an accepted-pending commit is durable proof the reply must never send again",
                NotificationReplyCompletionStore
                    .create(realContext)
                    .isCompleted(NotificationReplyWorker.notificationReplyCompletionKey(workId)),
            )
        }

    @Test
    fun aDroppedReplyIsNeverReportedAsSuccess() =
        runTest {
            fixture.bootstrap()
            sendSummary = sentSummary(messageIds = emptyList())
            val workId = UUID.randomUUID()
            val worker = buildWorker(inputData = encryptedInput(workId), id = workId)

            val result = pumpingMainLooper { worker.doWork() }

            assertEquals("a send with no committed message id must retry, not succeed", Result.retry(), result)
            assertFalse(
                NotificationReplyCompletionStore
                    .create(realContext)
                    .isCompleted(NotificationReplyWorker.notificationReplyCompletionKey(workId)),
            )
        }

    @Test
    fun transientSendFailuresRetryToTheCapThenFailTerminally() =
        runTest {
            fixture.bootstrap()
            sendFailure = RuntimeException("connection refused")
            val workId = UUID.randomUUID()

            val results =
                (1..3).map {
                    pumpingMainLooper { buildWorker(inputData = encryptedInput(workId), id = workId).doWork() }
                }

            assertEquals(listOf(Result.retry(), Result.retry(), Result.failure()), results)
            assertEquals(
                NotificationReplyAbandonedOutcome.Failure,
                NotificationReplyCompletionStore
                    .create(realContext)
                    .abandonedOutcome(NotificationReplyWorker.notificationReplyCompletionKey(workId)),
            )
        }

    @Test
    fun nonRetryableSendFailuresFailOnTheFirstAttempt() =
        runTest {
            fixture.bootstrap()
            sendFailure = RuntimeException("relay rejected event")
            val workId = UUID.randomUUID()
            val worker = buildWorker(inputData = encryptedInput(workId), id = workId)

            val result = pumpingMainLooper { worker.doWork() }

            assertEquals(Result.failure(), result)
            assertEquals(1, fixture.sendTextCalls.get())
        }

    @Test
    fun identicalConsecutiveTextsFromSeparateActionsBothSend() =
        runTest {
            fixture.bootstrap()
            val firstId = UUID.randomUUID()
            val secondId = UUID.randomUUID()

            val first =
                pumpingMainLooper {
                    buildWorker(inputData = encryptedInput(firstId, text = "same reply"), id = firstId).doWork()
                }
            val second =
                pumpingMainLooper {
                    buildWorker(inputData = encryptedInput(secondId, text = "same reply"), id = secondId).doWork()
                }

            assertEquals(Result.success(), first)
            assertEquals(Result.success(), second)
            assertEquals("identical text from a separate action is a distinct reply", 2, fixture.sendTextCalls.get())
        }

    @Test
    fun replyWorkRequiresConnectivityBeforeBurningAttempts() {
        val requestId = UUID.randomUUID()
        val request =
            NotificationReplyWorker.notificationReplyRequest(
                action = replyAction(),
                requestId = requestId,
                encryptedReply = cipher.encrypt("hello", requestId, replyAction()),
            )

        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
    }

    @Test
    fun aSealedReplyThatCannotBeAuthenticatedFailsTerminally() =
        runTest {
            val workId = UUID.randomUUID()
            val foreignId = UUID.randomUUID()
            val worker = buildWorker(inputData = encryptedInput(foreignId), id = workId)

            val result = pumpingMainLooper { worker.doWork() }

            assertEquals(Result.failure(), result)
            assertEquals(0, fixture.sendTextCalls.get())
        }

    @Test
    fun doWorkPropagatesCancellationInsteadOfRecordingAFailure() =
        runTest {
            fixture.bootstrap()
            sendFailure = CancellationException("stopped")
            val workId = UUID.randomUUID()
            val worker = buildWorker(inputData = encryptedInput(workId), id = workId)

            val outcome = pumpingMainLooper { runCatching { worker.doWork() } }

            assertTrue(
                "cancellation must propagate instead of becoming a result",
                outcome.exceptionOrNull() is CancellationException,
            )
        }

    private fun replyAction() = testNotificationAction(NotificationActionKind.REPLY)

    private fun encryptedInput(
        requestId: UUID,
        text: String = "hello",
    ): Data =
        NotificationReplyWorker.notificationReplyInputData(
            action = replyAction(),
            encryptedReply = cipher.encrypt(text, requestId, replyAction()),
        )

    private fun legacyPlaintextInput(text: String): Data =
        Data
            .Builder()
            .putAll(NotificationActionWorkData.encode(replyAction()))
            // Legacy rows persisted the reply under this plaintext key before
            // sealed inputs existed; upgrades still replay them once.
            .putString("reply", text)
            .build()

    private fun buildWorker(
        inputData: Data,
        id: UUID? = null,
    ): NotificationReplyWorker {
        val builder =
            TestListenableWorkerBuilder
                .from<NotificationReplyWorker>(workerContext, NotificationReplyWorker::class.java)
                .setInputData(inputData)
        if (id != null) builder.setId(id)
        return builder.build()
    }

    private fun signingAccount() =
        dev.ipf.marmotkit.AccountSummaryFfi(
            label = "account-a",
            accountIdHex = "aa".repeat(32),
            localSigning = true,
            externalSigning = false,
            signedOut = false,
            running = true,
        )
}

private fun sentSummary(
    messageIds: List<String> = listOf("ab".repeat(32)),
    acceptDisposition: SendAcceptDispositionFfi = SendAcceptDispositionFfi.PUBLISHED,
) = SendSummaryFfi(
    published = 1u,
    messageIds = messageIds,
    acceptDisposition = acceptDisposition,
    maintenanceDisposition = SendMaintenanceDispositionFfi.READY,
)
