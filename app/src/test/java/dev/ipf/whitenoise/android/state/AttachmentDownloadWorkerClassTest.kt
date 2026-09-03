package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.ContextWrapper
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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private typealias DownloadOverride = PerformDurableAttachmentDownload

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = WhiteNoiseApplication::class)
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

    @Test
    fun doWorkRetriesTransientDownloadFailuresOnce() =
        runTest {
            val worker =
                buildWorkerWithDownloadOverride(
                    downloadOverride = { _, _, _ -> false },
                    runAttemptCount = 0,
                )

            assertEquals(Result.retry(), worker.doWork())
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
