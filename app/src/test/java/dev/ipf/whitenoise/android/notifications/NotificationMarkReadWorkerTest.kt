package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker.Result
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import dev.ipf.whitenoise.android.state.NotificationBootstrapTestFixture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Executes [NotificationMarkReadWorker.doWork] through the WorkManager test
 * harness against a real [dev.ipf.whitenoise.android.state.WhiteNoiseAppState]
 * whose FFI boundary is a controllable fixture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = WhiteNoiseApplication::class)
class NotificationMarkReadWorkerTest {
    private lateinit var realContext: Context

    @Volatile
    private var markReadFailure: Throwable? = null

    private lateinit var fixture: NotificationBootstrapTestFixture
    private lateinit var workerContext: NotificationWorkerTestApplication

    @Before
    fun setUp() {
        realContext = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(realContext)
        fixture =
            NotificationBootstrapTestFixture(
                context = realContext,
                accounts = listOf(signingAccount()),
                onMarkTimelineMessageRead = {
                    markReadFailure?.let { throw it }
                    null
                },
            )
        workerContext = NotificationWorkerTestApplication(realContext, fixture.appState)
    }

    @Test
    fun doWorkSucceedsForANonWhiteNoiseApplication() =
        runTest {
            val worker =
                TestListenableWorkerBuilder
                    .from<NotificationMarkReadWorker>(
                        PlainApplicationContext(realContext),
                        NotificationMarkReadWorker::class.java,
                    ).build()

            assertEquals(Result.success(), worker.doWork())
        }

    @Test
    fun doWorkFailsMalformedInputWithoutTouchingTheEngine() =
        runTest {
            val worker = buildWorker(inputData = androidx.work.Data.EMPTY)

            assertEquals(Result.failure(), worker.doWork())
            assertEquals(0, fixture.markReadCalls.get())
        }

    @Test
    fun doWorkFailsAMisroutedReplyAction() =
        runTest {
            val worker =
                buildWorker(
                    inputData = NotificationActionWorkData.encode(testNotificationAction(NotificationActionKind.REPLY)),
                )

            assertEquals(Result.failure(), worker.doWork())
            assertEquals(0, fixture.markReadCalls.get())
        }

    @Test
    fun doWorkDefersBehindTheAppLockWithinTheBoundedWaitWindow() =
        runTest {
            fixture.appState.setAppLockScreenVisibleForTest(true)
            val worker = buildWorker()

            assertEquals(Result.retry(), worker.doWork())
            assertEquals(0, fixture.markReadCalls.get())
        }

    @Test
    fun doWorkFailsOnceTheAppLockWaitWindowExpires() =
        runTest {
            fixture.appState.setAppLockScreenVisibleForTest(true)
            val workId = UUID.randomUUID()
            expiredLockStore().shouldDeferForLock(
                workId.toString(),
                NotificationActionRetryStore.MAXIMUM_LOCK_WAIT_MILLIS,
            )
            val worker = buildWorker(id = workId)

            assertEquals(Result.failure(), worker.doWork())
            assertEquals(0, fixture.markReadCalls.get())
        }

    @Test
    fun doWorkFailsAtTheDurableAttemptCapWithoutAnotherEngineCall() =
        runTest {
            val workId = UUID.randomUUID()
            val store = NotificationActionRetryStore.create(realContext)
            repeat(3) { store.recordOperationFailureAttempt(workId.toString()) }
            val worker = buildWorker(id = workId)

            assertEquals(Result.failure(), worker.doWork())
            assertEquals(0, fixture.markReadCalls.get())
        }

    @Test
    fun doWorkSucceedsWhenTheEngineMarksTheMessageRead() =
        runTest {
            fixture.bootstrap()
            val worker = buildWorker()

            val result = pumpingMainLooper { worker.doWork() }

            assertEquals(Result.success(), result)
            assertEquals(1, fixture.markReadCalls.get())
        }

    @Test
    fun engineFailuresRetryUpToTheCapThenFailTerminally() =
        runTest {
            fixture.bootstrap()
            markReadFailure = RuntimeException("engine unavailable")
            val workId = UUID.randomUUID()

            val results =
                (1..3).map {
                    pumpingMainLooper { buildWorker(id = workId).doWork() }
                }

            assertEquals(listOf(Result.retry(), Result.retry(), Result.failure()), results)
        }

    @Test
    fun doWorkPropagatesCancellationInsteadOfRecordingAFailure() =
        runTest {
            fixture.bootstrap()
            markReadFailure = CancellationException("stopped")
            val worker = buildWorker()

            val outcome = pumpingMainLooper { runCatching { worker.doWork() } }

            assertTrue(
                "cancellation must propagate instead of becoming a result",
                outcome.exceptionOrNull() is CancellationException,
            )
        }

    @Test
    fun enqueueDeduplicatesRepeatedActionsIntoOneDurableUniqueWork() =
        runTest {
            val action = testNotificationAction(NotificationActionKind.MARK_READ)
            // Keep the synchronously-executed work in a retrying (unfinished)
            // state so KEEP semantics are observable: the app lock defers the
            // mark-read without consuming the durable work item.
            (realContext.applicationContext as WhiteNoiseApplication).appState.setAppLockScreenVisibleForTest(true)

            assertTrue(NotificationMarkReadWorker.enqueue(realContext, action))
            assertTrue(NotificationMarkReadWorker.enqueue(realContext, action))

            val unfinished =
                WorkManager
                    .getInstance(realContext)
                    .getWorkInfosForUniqueWork(NotificationMarkReadWorker.notificationMarkReadWorkName(action))
                    .get()
                    .count { !it.state.isFinished }
            assertEquals("repeated notification actions must keep one durable work item", 1, unfinished)
        }

    private fun buildWorker(
        inputData: androidx.work.Data =
            NotificationActionWorkData.encode(testNotificationAction(NotificationActionKind.MARK_READ)),
        id: UUID? = null,
    ): NotificationMarkReadWorker {
        val builder =
            TestListenableWorkerBuilder
                .from<NotificationMarkReadWorker>(workerContext, NotificationMarkReadWorker::class.java)
                .setInputData(inputData)
        if (id != null) builder.setId(id)
        return builder.build()
    }

    /**
     * A retry store over the same preferences file
     * [NotificationActionRetryStore.create] opens, whose clock reads far enough
     * in the past that a lock-wait it starts is already expired.
     */
    private fun expiredLockStore(): NotificationActionRetryStore {
        val preferences =
            realContext.getSharedPreferences("whitenoise.notification_action_retries", Context.MODE_PRIVATE)
        val expiredNow = System.currentTimeMillis() - NotificationActionRetryStore.MAXIMUM_LOCK_WAIT_MILLIS - 60_000L
        return NotificationActionRetryStore(preferences) { expiredNow }
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

    private class PlainApplicationContext(
        base: Context,
    ) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
    }
}
