package dev.ipf.whitenoise.android.state

import android.app.job.JobScheduler
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

private typealias DownloadOverride = PerformDurableAttachmentDownload

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = WhiteNoiseApplication::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AttachmentDownloadWorkerClassTest {
    private lateinit var application: WhiteNoiseApplication
    private lateinit var appContext: Context

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        appContext = application.applicationContext
        WorkManagerTestInitHelper.initializeTestWorkManager(application)
    }

    @Test
    fun doWorkFailsWhenInputDataIsInvalid() =
        runTest {
            val worker =
                TestListenableWorkerBuilder
                    .from<AttachmentDownloadWorker>(appContext, AttachmentDownloadWorker::class.java)
                    .build()

            assertTrue(worker.doWork() is Result.Failure)
        }

    @Test
    fun doWorkFailsWhenApplicationIsNotWhiteNoiseApplication() =
        runTest {
            val wrapped = NonWhiteNoiseApplicationContext(appContext)
            val worker =
                TestListenableWorkerBuilder
                    .from<AttachmentDownloadWorker>(wrapped, AttachmentDownloadWorker::class.java)
                    .setInputData(AttachmentDownloadWorkData.encode(testRequest()))
                    .build()

            assertTrue(worker.doWork() is Result.Failure)
        }

    @Test
    fun doWorkSucceedsWhenAutomaticDownloadsArePausedForTheAccount() =
        runTest {
            val request = testRequest()
            val preferences = appContext.getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
            AttachmentDownloadIntentStore(preferences).apply {
                markOpenIntent(AttachmentOpenRequest(request, navigationGeneration = 0L))
                pauseAutomatic(request.accountRef)
            }

            val worker =
                TestListenableWorkerBuilder
                    .from<AttachmentDownloadWorker>(appContext, AttachmentDownloadWorker::class.java)
                    .setInputData(AttachmentDownloadWorkData.encode(request))
                    .build()

            assertEquals(Result.success(), worker.doWork())
        }

    @Test
    fun doWorkSucceedsWhenDurableDownloadCompletes() =
        runTest {
            val worker =
                buildWorkerWithDownloadOverride(downloadOverride = { _, _, _ -> true })

            assertEquals(Result.success(), worker.doWork())
        }

    /** Interactive WorkManager fallback must never start its service with type none. */
    @Test
    fun interactiveForegroundInfoUsesDataSyncServiceType() {
        val info = attachmentWorkForegroundInfo(appContext, testRequest())

        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, info.foregroundServiceType)
    }

    @Test
    fun doWorkRetriesTransientDownloadFailuresOnce() =
        runTest {
            var attempts = 0
            val download: DownloadOverride = { _, _, _ ->
                attempts += 1
                throw java.io.IOException("synthetic transport interruption")
            }

            assertEquals(Result.retry(), buildWorkerWithDownloadOverride(download, runAttemptCount = 0).doWork())
            assertEquals(Result.failure(), buildWorkerWithDownloadOverride(download, runAttemptCount = 1).doWork())
            assertEquals(2, attempts)
        }

    @Test
    fun completedBodyWithoutRetentionIsTerminalForAutomaticAndInteractiveWork() =
        runTest {
            val request = testRequest()
            val intents =
                AttachmentDownloadIntentStore(appContext.getSharedPreferences("whitenoise", Context.MODE_PRIVATE))
            for (interactive in listOf(false, true)) {
                intents.setInteractive(request, interactive)
                val expectedPriority =
                    if (interactive) AttachmentDownloadPriority.Interactive else AttachmentDownloadPriority.Automatic
                var completedBodies = 0
                val worker =
                    buildWorkerWithDownloadOverride(
                        downloadOverride = { _, _, priority ->
                            assertEquals(expectedPriority, priority)
                            completedBodies += 1
                            false
                        },
                        runAttemptCount = 0,
                    )

                val startedAt = currentTime
                assertEquals(Result.failure(), worker.doWork())
                assertEquals(startedAt, currentTime)
                assertEquals(1, completedBodies)
                assertFalse(intents.isInteractive(request))
                // A failed retention result must not be stored as acquired,
                // nor impersonate the user's durable cancellation intent.
                assertFalse(intents.isAutomaticSuppressed(request))
            }
        }

    @Test
    fun explicitRequestCanRetryAfterBodyWasNotRetained() =
        runTest {
            assertEquals(Result.failure(), buildWorkerWithDownloadOverride({ _, _, _ -> false }).doWork())
            AttachmentDownloadIntentStore(appContext.getSharedPreferences("whitenoise", Context.MODE_PRIVATE))
                .setInteractive(testRequest(), interactive = true)
            val retry =
                buildWorkerWithDownloadOverride(
                    downloadOverride = { _, _, priority ->
                        assertEquals(AttachmentDownloadPriority.Interactive, priority)
                        true
                    },
                )
            assertEquals(Result.success(), retry.doWork())
        }

    @Test
    fun nonRetentionAfterTheLastAttemptFailsImmediately() =
        runTest {
            val worker = buildWorkerWithDownloadOverride({ _, _, _ -> false }, runAttemptCount = 1)
            val startedAt = currentTime
            assertEquals(Result.failure(), worker.doWork())
            assertEquals(startedAt, currentTime)
        }

    @Test
    fun cancelledFetchEndsTheWorkerAndClearsInteractiveIntent() =
        runTest {
            val request = testRequest()
            val intents =
                AttachmentDownloadIntentStore(appContext.getSharedPreferences("whitenoise", Context.MODE_PRIVATE))
            for (interactive in listOf(false, true)) {
                intents.setInteractive(request, interactive)
                val worker =
                    buildWorkerWithDownloadOverride(
                        downloadOverride = { _, _, _ -> CompletableDeferred<Boolean>().apply { cancel() }.await() },
                    )

                assertEquals(Result.failure(), worker.doWork())
                assertFalse(intents.isInteractive(request))
                assertFalse(intents.isAutomaticSuppressed(request))
            }
        }

    /** Platform interruption preserves retry ownership; diagnostics contain only coarse reason and attempt count. */
    @Test
    @Config(sdk = [30, 36])
    fun stoppedWorkerPropagatesCancellationAndRetainsInteractiveIntent() =
        runTest {
            val request = testRequest()
            val intents =
                AttachmentDownloadIntentStore(appContext.getSharedPreferences("whitenoise", Context.MODE_PRIVATE))
            intents.setInteractive(request, true)
            val enteredDownload = CompletableDeferred<Unit>()
            val worker =
                buildWorkerWithDownloadOverride(
                    downloadOverride = { _, _, _ ->
                        enteredDownload.complete(Unit)
                        awaitCancellation()
                    },
                    runAttemptCount = 2,
                )
            val run = async { worker.doWork() }
            runCurrent()
            enteredDownload.await()
            run.cancel(CancellationException("fixture://private-stop-context"))
            runCurrent()

            assertTrue(run.isCancelled)
            assertTrue(runCatching { run.await() }.exceptionOrNull() is CancellationException)
            assertTrue(intents.isInteractive(request))
            val stopped =
                ShadowLog.getLogsForTag("DMAttachmentWorker").filter { it.msg.startsWith("attachment_work_stopped") }
            assertEquals(1, stopped.size)
            val expectedReason =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) worker.stopReason.toString() else "unavailable"
            assertEquals("attachment_work_stopped reason=$expectedReason run_attempt=2", stopped.single().msg)
            val privateValues =
                listOf(request.accountRef, request.groupIdHex, request.messageIdHex, "fixture://private-stop-context")
            privateValues.forEach { value ->
                assertFalse("stop diagnostics exposed private context", stopped.single().msg.contains(value))
            }
        }

    @Test
    fun doWorkFailsTerminalDownloadErrorsWithoutRetry() =
        runTest {
            val worker =
                buildWorkerWithDownloadOverride(
                    downloadOverride = { _, _, _ ->
                        throw MarmotKitException.InvalidMediaReference("media decryption failed")
                    },
                )

            assertEquals(Result.failure(), worker.doWork())
        }

    @Test
    fun cancelForRequestRevokesTheDurableIntentAndTheQueuedUniqueWork() {
        val request = testRequest()
        val other = request.copy(attachmentIndex = 1)
        val intentStore =
            AttachmentDownloadIntentStore(
                appContext.getSharedPreferences("whitenoise", Context.MODE_PRIVATE),
            )
        AttachmentDownloadWorker.enqueue(appContext, request, AttachmentDownloadPriority.Interactive)
        AttachmentDownloadWorker.enqueue(appContext, other, AttachmentDownloadPriority.Interactive)
        assertTrue(intentStore.isInteractive(request))
        assertEquals(1, enqueuedWorkCount(request))

        AttachmentDownloadWorker.cancelForRequest(appContext, request)

        assertFalse(
            "process restoration must not find an interactive intent to resurrect",
            intentStore.isInteractive(request),
        )
        assertEquals(0, enqueuedWorkCount(request))
        assertTrue("cancel is per attachment, not per account", intentStore.isInteractive(other))
        assertEquals(1, enqueuedWorkCount(other))
    }

    @Test
    fun aRetapAfterCancelStartsExactlyOneFreshDurableTransfer() {
        val request = testRequest()
        AttachmentDownloadWorker.enqueue(appContext, request, AttachmentDownloadPriority.Interactive)
        AttachmentDownloadWorker.cancelForRequest(appContext, request)

        AttachmentDownloadWorker.enqueue(appContext, request, AttachmentDownloadPriority.Interactive)
        AttachmentDownloadWorker.enqueue(appContext, request, AttachmentDownloadPriority.Interactive)

        assertEquals("KEEP still dedupes repeated taps into one transfer", 1, enqueuedWorkCount(request))
    }

    @Test
    fun aCancelledAttachmentIsNotResurrectedByLaterAutomaticWork() {
        val request = testRequest()
        val intentStore =
            AttachmentDownloadIntentStore(
                appContext.getSharedPreferences("whitenoise", Context.MODE_PRIVATE),
            )
        AttachmentDownloadWorker.enqueue(appContext, request, AttachmentDownloadPriority.Interactive)
        AttachmentDownloadWorker.cancelForRequest(appContext, request)
        assertTrue(intentStore.isAutomaticSuppressed(request))

        // Receipt-pipeline and composition fast paths both enqueue automatic
        // work; neither may restart what the user cancelled.
        AttachmentDownloadWorker.enqueue(appContext, request, AttachmentDownloadPriority.Automatic)

        assertEquals(0, enqueuedWorkCount(request))
    }

    @Test
    fun anExplicitRequestOutranksAnEarlierCancel() {
        val request = testRequest()
        val intentStore =
            AttachmentDownloadIntentStore(
                appContext.getSharedPreferences("whitenoise", Context.MODE_PRIVATE),
            )
        AttachmentDownloadWorker.cancelForRequest(appContext, request)
        assertTrue(intentStore.isAutomaticSuppressed(request))

        AttachmentDownloadWorker.enqueue(appContext, request, AttachmentDownloadPriority.Interactive)

        assertFalse(intentStore.isAutomaticSuppressed(request))
        assertEquals(1, enqueuedWorkCount(request))
    }

    @Test
    fun aSuppressedAutomaticWorkerSucceedsWithoutDownloading() =
        runTest {
            val request = testRequest()
            AttachmentDownloadWorker.cancelForRequest(appContext, request)
            var downloads = 0
            val worker =
                buildWorkerWithDownloadOverride(
                    downloadOverride = { _, _, _ ->
                        downloads += 1
                        true
                    },
                )

            assertEquals(Result.success(), worker.doWork())
            assertEquals("a cancelled attachment must not be downloaded by a queued worker", 0, downloads)
        }

    /** Only a reader-requested transfer is elevated; automatic work stays ordinary on every API level. */
    @Test
    fun onlyInteractiveTransfersLeaveOrdinaryBackgroundWork() {
        for (sdk in listOf(30, 33, 34, 36)) {
            for (visible in listOf(false, true)) {
                assertEquals(
                    AttachmentExecutionClass.OrdinaryWork,
                    attachmentExecutionClass(AttachmentDownloadPriority.Automatic, visible, sdk),
                )
            }
        }
        assertEquals(
            AttachmentExecutionClass.ForegroundWork,
            attachmentExecutionClass(AttachmentDownloadPriority.Interactive, userVisible = false, sdkInt = 36),
        )
        assertEquals(
            AttachmentExecutionClass.ForegroundWork,
            attachmentExecutionClass(AttachmentDownloadPriority.Interactive, userVisible = true, sdkInt = 33),
        )
        assertEquals(
            AttachmentExecutionClass.UserInitiatedJob,
            attachmentExecutionClass(AttachmentDownloadPriority.Interactive, userVisible = true, sdkInt = 34),
        )
    }

    /** An automatic request, even one that claims to be visible, never reaches the user-initiated scheduler. */
    @Test
    fun anAutomaticRequestIsOrdinaryWorkAndNeverAUserInitiatedJob() {
        val request = testRequest()
        val intents = AttachmentDownloadIntentStore(appContext.getSharedPreferences("whitenoise", Context.MODE_PRIVATE))

        AttachmentDownloadWorker.enqueue(appContext, request, AttachmentDownloadPriority.Automatic, userVisible = true)

        assertEquals(1, enqueuedWorkCount(request))
        assertFalse(intents.isInteractive(request))
        val scheduler = appContext.getSystemService(JobScheduler::class.java)
        assertTrue(scheduler.allPendingJobs.isEmpty())
    }

    private fun enqueuedWorkCount(request: AttachmentTransferRequest): Int =
        WorkManager
            .getInstance(appContext)
            .getWorkInfosForUniqueWork(attachmentDownloadWorkName(request))
            .get()
            .count { !it.state.isFinished }

    private fun buildWorkerWithDownloadOverride(
        downloadOverride: DownloadOverride,
        runAttemptCount: Int? = null,
    ): AttachmentDownloadWorker {
        val builder =
            TestListenableWorkerBuilder
                .from<AttachmentDownloadWorker>(appContext, AttachmentDownloadWorker::class.java)
                .setWorkerFactory(downloadWorkerFactory(downloadOverride))
                .setInputData(AttachmentDownloadWorkData.encode(testRequest()))
        if (runAttemptCount != null) {
            builder.setRunAttemptCount(runAttemptCount)
        }
        return builder.build()
    }

    private fun downloadWorkerFactory(downloadOverride: DownloadOverride): WorkerFactory =
        object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker? {
                if (workerClassName != AttachmentDownloadWorker::class.java.name) return null
                return AttachmentDownloadWorker(appContext, workerParameters, downloadOverride)
            }
        }

    private fun testRequest(): AttachmentTransferRequest =
        AttachmentTransferRequest(
            accountRef = "account-a",
            groupIdHex = "ab".repeat(16),
            messageIdHex = "cd".repeat(32),
            attachmentIndex = 0,
        )

    private class NonWhiteNoiseApplicationContext(
        base: Context,
    ) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
    }
}
