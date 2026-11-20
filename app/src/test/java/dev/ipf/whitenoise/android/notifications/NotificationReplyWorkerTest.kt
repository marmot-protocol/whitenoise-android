package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ListenableWorker.Result
import androidx.work.NetworkType
import androidx.work.WorkManager
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    @Volatile
    private var onRecoveryRead: () -> Unit = {}

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
                onTimelineMessages = { _, _ ->
                    onRecoveryRead()
                    null
                },
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

    /** An accepted reply recovered after a restart completes even when App Lock already covers the app. */
    @Test
    fun committedReplyCompletesWhileLockedWithoutSendingAgain() =
        runTest {
            fixture.bootstrap()
            fixture.appState.setAppLockScreenVisibleForTest(true)
            val workId = UUID.randomUUID()
            val completionKey = NotificationReplyWorker.notificationReplyCompletionKey(workId)
            val store = NotificationReplyCompletionStore.create(realContext)
            val boundary = NotificationReplyRecoveryBoundary(1uL, "a".repeat(64))
            checkNotNull(store.markStarted(completionKey, "scope", boundary))
            assertTrue(store.markCommittedMessage(completionKey, "b".repeat(64)))
            val worker = buildWorker(inputData = encryptedInput(workId), id = workId)

            assertEquals(Result.success(), pumpingMainLooper { worker.doWork() })
            assertTrue(store.isCompleted(completionKey))
            assertEquals(0, fixture.sendTextCalls.get())
            assertEquals(0, fixture.markReadCalls.get())

            val read = replyAction().copy(kind = NotificationActionKind.MARK_READ)
            val workName = NotificationMarkReadWorker.notificationMarkReadWorkName(read, dismissCard = false)
            val queued = WorkManager.getInstance(realContext).getWorkInfosForUniqueWork(workName).get()
            assertEquals(1, queued.size)
        }

    /** Once the read-only probe rules out acceptance, expiration clears the active receipt. */
    @Test
    fun expiredLockedReplyRetiresAnUncommittedAttempt() =
        runTest {
            fixture.bootstrap()
            fixture.appState.setAppLockScreenVisibleForTest(true)
            val workId = UUID.randomUUID()
            val completionKey = NotificationReplyWorker.notificationReplyCompletionKey(workId)
            val store = NotificationReplyCompletionStore.create(realContext)
            val boundary = NotificationReplyRecoveryBoundary(1uL, "a".repeat(64))
            checkNotNull(store.markStarted(completionKey, "scope", boundary))
            expireLockWait(workId)
            val worker = buildWorker(inputData = encryptedInput(workId), id = workId)

            assertEquals(Result.failure(), pumpingMainLooper { worker.doWork() })
            assertEquals(NotificationReplyAbandonedOutcome.Failure, store.abandonedOutcome(completionKey))
            assertFalse(store.hasStarted(completionKey))
            assertEquals(0, fixture.sendTextCalls.get())
        }

    /** Authentication during the recovery read admits the reply even after its lock wait has expired. */
    @Test
    fun unlockDuringRecoveryAdmitsAnUncommittedReply() =
        runTest {
            fixture.bootstrap()
            fixture.appState.setAppLockScreenVisibleForTest(true)
            val workId = UUID.randomUUID()
            val completionKey = NotificationReplyWorker.notificationReplyCompletionKey(workId)
            val store = NotificationReplyCompletionStore.create(realContext)
            val boundary = NotificationReplyRecoveryBoundary(1uL, "a".repeat(64))
            checkNotNull(store.markStarted(completionKey, "scope", boundary))
            expireLockWait(workId)
            onRecoveryRead = {
                val unlocked = CountDownLatch(1)
                Handler(Looper.getMainLooper()).post {
                    fixture.appState.setAppLockScreenVisibleForTest(false)
                    unlocked.countDown()
                }
                check(unlocked.await(5L, TimeUnit.SECONDS))
            }
            val worker = buildWorker(inputData = encryptedInput(workId), id = workId)

            assertEquals(Result.success(), pumpingMainLooper { worker.doWork() })
            assertTrue(store.isCompleted(completionKey))
            assertEquals(1, fixture.sendTextCalls.get())
        }

    /** Legacy WorkManager input terminates immediately and explains how to retry from an unlocked session. */
    @Test
    fun legacyPlaintextRepliesFailImmediatelyBehindTheAppLock() =
        runTest {
            fixture.appState.setAppLockScreenVisibleForTest(true)
            val workId = UUID.randomUUID()
            val worker = buildWorker(inputData = legacyPlaintextInput("hello"), id = workId)

            assertEquals(Result.failure(), pumpingMainLooper { worker.doWork() })
            val store = NotificationReplyCompletionStore.create(realContext)
            val key = NotificationReplyWorker.notificationReplyCompletionKey(workId)
            assertEquals(NotificationReplyAbandonedOutcome.Failure, store.abandonedOutcome(key))
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

    /** Seeds WorkManager's lock-wait clock far enough in the past to reach its terminal branch. */
    private fun expireLockWait(workId: UUID) {
        val delay = NotificationActionRetryStore.MAXIMUM_LOCK_WAIT_MILLIS
        val retryPrefs =
            realContext.getSharedPreferences("whitenoise.notification_action_retries", Context.MODE_PRIVATE)
        val expiredAt = System.currentTimeMillis() - delay - 60_000L
        NotificationActionRetryStore(retryPrefs) { expiredAt }.shouldDeferForLock(workId.toString(), delay)
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

    /** A running account `account-a` that signs locally. */
    private fun signingAccount() =
        dev.ipf.marmotkit.AccountSummaryFfi(
            label = "account-a",
            accountIdHex = "aa".repeat(32),
            localSigning = true,
            externalSigning = false,
            signedOut = false,
            running = true,
            unlinked = false,
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
