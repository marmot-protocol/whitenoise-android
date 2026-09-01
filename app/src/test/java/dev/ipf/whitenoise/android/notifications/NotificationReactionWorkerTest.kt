package dev.ipf.whitenoise.android.notifications

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ListenableWorker.Result
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

/**
 * Executes [NotificationReactionWorker.doWork] through the WorkManager test
 * harness: input validation, app-lock deferral, success with idempotent
 * cleanup, transient-versus-terminal send classification, and the attempt cap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationReactionWorkerTest {
    private lateinit var realContext: Context
    private lateinit var cipher: NotificationReplyCipher

    @Volatile
    private var reactionFailure: Throwable? = null

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
                onReactToMessage = {
                    reactionFailure?.let { throw it }
                    sentSummary()
                },
            )
        workerContext = NotificationWorkerTestApplication(realContext, fixture.appState)
    }

    @Test
    fun doWorkFailsMalformedInputWithoutSending() =
        runTest {
            val worker = buildWorker(inputData = NotificationActionWorkData.encode(reactAction()))

            assertEquals(Result.failure(), worker.doWork())
            assertEquals(0, fixture.reactToMessageCalls.get())
        }

    @Test
    fun doWorkFailsTerminallyWhenTheSealedReactionCannotBeAuthenticated() =
        runTest {
            val workId = UUID.randomUUID()
            val foreignId = UUID.randomUUID()
            val worker =
                buildWorker(
                    // Sealed against a different request id: AEAD authentication
                    // must fail and the mismatch is terminal, not retried.
                    inputData = reactionInput(requestId = foreignId),
                    id = workId,
                )

            assertEquals(Result.failure(), worker.doWork())
            assertEquals(0, fixture.reactToMessageCalls.get())
        }

    @Test
    fun doWorkDefersBehindTheAppLockWithinTheBoundedWaitWindow() =
        runTest {
            fixture.appState.setAppLockScreenVisibleForTest(true)
            val workId = UUID.randomUUID()
            val worker = buildWorker(inputData = reactionInput(requestId = workId), id = workId)

            assertEquals(Result.retry(), worker.doWork())
            assertEquals(0, fixture.reactToMessageCalls.get())
        }

    @Test
    fun doWorkFailsAtTheDurableAttemptCap() =
        runTest {
            val workId = UUID.randomUUID()
            val store = NotificationActionRetryStore.create(realContext)
            repeat(3) { store.recordOperationFailureAttempt(workId.toString()) }
            val worker = buildWorker(inputData = reactionInput(requestId = workId), id = workId)

            assertEquals(Result.failure(), worker.doWork())
            assertEquals(0, fixture.reactToMessageCalls.get())
        }

    @Test
    fun doWorkSendsTheReactionAndMarksTheSourceMessageRead() =
        runTest {
            fixture.bootstrap()
            val workId = UUID.randomUUID()
            val worker = buildWorker(inputData = reactionInput(requestId = workId), id = workId)

            val result = pumpingMainLooper { worker.doWork() }

            assertEquals(Result.success(), result)
            assertEquals(1, fixture.reactToMessageCalls.get())
            assertTrue(
                "a successful reaction must mark the source message read",
                fixture.markReadCalls.get() >= 1,
            )
        }

    @Test
    fun transientSendFailuresRetryUpToTheCapThenFailTerminally() =
        runTest {
            fixture.bootstrap()
            reactionFailure = RuntimeException("connection refused")
            val workId = UUID.randomUUID()

            val results =
                (1..3).map {
                    pumpingMainLooper {
                        buildWorker(inputData = reactionInput(requestId = workId), id = workId).doWork()
                    }
                }

            assertEquals(listOf(Result.retry(), Result.retry(), Result.failure()), results)
        }

    @Test
    fun nonRetryableSendFailuresFailWithoutBurningRetries() =
        runTest {
            fixture.bootstrap()
            reactionFailure = RuntimeException("relay rejected event")
            val workId = UUID.randomUUID()
            val worker = buildWorker(inputData = reactionInput(requestId = workId), id = workId)

            val result = pumpingMainLooper { worker.doWork() }

            assertEquals(Result.failure(), result)
            assertEquals(1, fixture.reactToMessageCalls.get())
        }

    @Test
    fun doWorkPropagatesCancellationInsteadOfRecordingAFailure() =
        runTest {
            fixture.bootstrap()
            reactionFailure = CancellationException("stopped")
            val workId = UUID.randomUUID()
            val worker = buildWorker(inputData = reactionInput(requestId = workId), id = workId)

            val outcome = pumpingMainLooper { runCatching { worker.doWork() } }

            assertTrue(
                "cancellation must propagate instead of becoming a result",
                outcome.exceptionOrNull() is CancellationException,
            )
        }

    @Test
    fun enqueueKeepsOneUniqueReactionPerTargetAndReportsTheLoser() =
        runTest {
            val action = reactAction()
            val first =
                NotificationReactionWorker.enqueue(
                    context = realContext,
                    action = action,
                    reaction = "👍",
                    actionStartedAtMs = System.currentTimeMillis(),
                    requestId = UUID.randomUUID(),
                    cipherFactory = { cipher },
                )
            val second =
                NotificationReactionWorker.enqueue(
                    context = realContext,
                    action = action,
                    reaction = "❤️",
                    actionStartedAtMs = System.currentTimeMillis(),
                    requestId = UUID.randomUUID(),
                    cipherFactory = { cipher },
                )

            assertTrue(first)
            assertFalse("a queued duplicate must report that it was not persisted", second)
            val unfinished =
                WorkManager
                    .getInstance(realContext)
                    .getWorkInfosForUniqueWork(NotificationReactionWorker.notificationReactionWorkName(action))
                    .get()
                    .count { !it.state.isFinished }
            assertEquals(1, unfinished)
        }

    @Test
    fun enqueueRejectsAnUnusableReactionUpFront() =
        runTest {
            val enqueued =
                NotificationReactionWorker.enqueue(
                    context = realContext,
                    action = reactAction(),
                    reaction = "   ",
                    actionStartedAtMs = System.currentTimeMillis(),
                    requestId = UUID.randomUUID(),
                    cipherFactory = { cipher },
                )

            assertFalse(enqueued)
        }

    private fun reactAction() = testNotificationAction(NotificationActionKind.REACT)

    private fun reactionInput(
        requestId: UUID,
        reaction: String = "👍",
    ): Data =
        NotificationReactionWorker.reactionInputData(
            action = reactAction(),
            encryptedReaction = cipher.encrypt(reaction, requestId, reactAction()),
            actionStartedAtMs = System.currentTimeMillis(),
        )

    private fun buildWorker(
        inputData: Data,
        id: UUID? = null,
    ): NotificationReactionWorker {
        val builder =
            TestListenableWorkerBuilder
                .from<NotificationReactionWorker>(workerContext, NotificationReactionWorker::class.java)
                .setInputData(inputData)
        if (id != null) builder.setId(id)
        return builder.build()
    }

    private fun sentSummary() =
        SendSummaryFfi(
            published = 1u,
            messageIds = listOf("ab".repeat(32)),
            acceptDisposition = SendAcceptDispositionFfi.PUBLISHED,
            maintenanceDisposition = SendMaintenanceDispositionFfi.READY,
        )

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
